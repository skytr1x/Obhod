package com.skytr1x.obhod.diagnostics

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName

internal data class TransferTestConfig(
    val payloadKiB: Int,
    val idleTimeoutSeconds: Int
) {
    val hostTimeoutSeconds: Int = HOST_TIMEOUT_SECONDS

    init {
        require(payloadKiB in PAYLOAD_OPTIONS_KIB)
        require(idleTimeoutSeconds in IDLE_TIMEOUT_OPTIONS_SECONDS)
    }

    companion object {
        const val HOST_TIMEOUT_SECONDS = 15
        val PAYLOAD_OPTIONS_KIB = listOf(100, 150, 200)
        val IDLE_TIMEOUT_OPTIONS_SECONDS = listOf(2, 4, 8)
    }
}

internal data class TransferTestResult(
    val service: String,
    val outcome: String,
    val bytesReceived: Int,
    val elapsedMs: Long,
    val socksConnectMs: Long?,
    val tlsHandshakeMs: Long?,
    val transferMs: Long?,
    val httpStatus: Int?
) {
    fun asLogMessage(): String =
        "[SELFTEST] service=$service outcome=$outcome bytes=$bytesReceived " +
            "elapsed_ms=$elapsedMs socks_ms=${socksConnectMs ?: "-"} " +
            "tls_ms=${tlsHandshakeMs ?: "-"} transfer_ms=${transferMs ?: "-"} " +
            "http=${httpStatus ?: "-"}"
}

internal data class TransferTestSummary(
    val config: TransferTestConfig,
    val results: List<TransferTestResult>
)

internal object ConnectivitySelfTest {
    private const val SOCKS_PORT = 10800
    private const val MAX_HTTP_LINE_BYTES = 8192
    private const val MAX_HTTP_HEADER_BYTES = 65536

    private data class Target(
        val service: String,
        val host: String,
        val path: (Int) -> String
    )

    private enum class Phase {
        SOCKS,
        TLS,
        HTTP_HEADERS,
        BODY
    }

    private class SelfTestFailure(val outcome: String) : IOException()

    private val targets = listOf(
        Target("youtube", "www.youtube.com") { "/" },
        Target("discord", "discord.com") { "/" },
        Target("4pda", "4pda.to") { "/forum/index.php" },
        Target("cloudflare", "speed.cloudflare.com") { bytes ->
            "/__down?bytes=$bytes"
        }
    )

    fun run(
        config: TransferTestConfig,
        onProgress: (service: String) -> Unit,
        onResult: (TransferTestResult) -> Unit
    ): TransferTestSummary {
        val results = targets.map { target ->
            onProgress(target.service)
            measure(target, config).also(onResult)
        }
        return TransferTestSummary(config, results)
    }

    private fun measure(target: Target, config: TransferTestConfig): TransferTestResult {
        val startedAtNanos = System.nanoTime()
        val activeSocket = AtomicReference<Socket?>()
        val phase = AtomicReference(Phase.SOCKS)
        val bytesReceived = AtomicInteger()
        val socksConnectMs = AtomicLong(-1)
        val tlsHandshakeMs = AtomicLong(-1)
        val transferMs = AtomicLong(-1)
        val httpStatus = AtomicInteger(-1)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "obhod-transfer-test").apply { isDaemon = true }
        }
        val future = executor.submit<TransferTestResult> {
            try {
                transfer(
                    target,
                    config,
                    startedAtNanos,
                    activeSocket,
                    phase,
                    bytesReceived,
                    socksConnectMs,
                    tlsHandshakeMs,
                    transferMs,
                    httpStatus
                )
            } catch (error: SelfTestFailure) {
                result(
                    target,
                    error.outcome,
                    config,
                    startedAtNanos,
                    bytesReceived,
                    socksConnectMs,
                    tlsHandshakeMs,
                    transferMs,
                    httpStatus
                )
            } catch (_: SocketTimeoutException) {
                result(
                    target,
                    when (phase.get()) {
                        Phase.SOCKS -> "connect_timeout"
                        Phase.TLS -> "tls_timeout"
                        Phase.HTTP_HEADERS -> "response_timeout"
                        Phase.BODY -> "idle_timeout"
                    },
                    config,
                    startedAtNanos,
                    bytesReceived,
                    socksConnectMs,
                    tlsHandshakeMs,
                    transferMs,
                    httpStatus
                )
            } catch (_: IOException) {
                result(
                    target,
                    when (phase.get()) {
                        Phase.SOCKS -> "proxy_unavailable"
                        Phase.TLS -> "tls_failed"
                        Phase.HTTP_HEADERS -> "http_response_failed"
                        Phase.BODY -> "transfer_failed"
                    },
                    config,
                    startedAtNanos,
                    bytesReceived,
                    socksConnectMs,
                    tlsHandshakeMs,
                    transferMs,
                    httpStatus
                )
            }
        }

        return try {
            future.get(config.hostTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
            future.cancel(true)
            result(
                target,
                "overall_timeout",
                config,
                startedAtNanos,
                bytesReceived,
                socksConnectMs,
                tlsHandshakeMs,
                transferMs,
                httpStatus
            )
        } catch (error: InterruptedException) {
            activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("Self-test interrupted", error)
        } catch (error: ExecutionException) {
            throw error.cause as? RuntimeException
                ?: IOException("Self-test worker failed", error.cause)
        } finally {
            activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }

    private fun transfer(
        target: Target,
        config: TransferTestConfig,
        startedAtNanos: Long,
        activeSocket: AtomicReference<Socket?>,
        phase: AtomicReference<Phase>,
        bytesReceived: AtomicInteger,
        socksConnectMs: AtomicLong,
        tlsHandshakeMs: AtomicLong,
        transferMs: AtomicLong,
        httpStatus: AtomicInteger
    ): TransferTestResult {
        val socket = Socket()
        activeSocket.set(socket)
        try {
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress("127.0.0.1", SOCKS_PORT),
                minOf(config.hostTimeoutSeconds * 1000, 5000)
            )
            socket.soTimeout = config.idleTimeoutSeconds * 1000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            val greeting = readExact(input, socket, config, startedAtNanos, 2)
            if (greeting[0] != 5.toByte() || greeting[1] != 0.toByte()) {
                throw SelfTestFailure("socks_auth_unsupported")
            }

            val hostBytes = target.host.toByteArray(StandardCharsets.US_ASCII)
            val port = 443
            output.write(byteArrayOf(
                5, 1, 0, 3, hostBytes.size.toByte()
            ))
            output.write(hostBytes)
            output.write(byteArrayOf((port ushr 8).toByte(), port.toByte()))
            output.flush()
            val reply = readExact(input, socket, config, startedAtNanos, 4)
            if (reply[0] != 5.toByte()) throw SelfTestFailure("invalid_socks_reply")
            if (reply[1] != 0.toByte()) throw SelfTestFailure("socks_connect_failed")
            val boundAddressLength = when (reply[3].toInt() and 0xff) {
                1 -> 4
                3 -> readExact(input, socket, config, startedAtNanos, 1)[0].toInt() and 0xff
                4 -> 16
                else -> throw SelfTestFailure("invalid_socks_reply")
            }
            readExact(input, socket, config, startedAtNanos, boundAddressLength + 2)
            socksConnectMs.set(elapsedMs(startedAtNanos))

            phase.set(Phase.TLS)
            val tlsStarted = System.nanoTime()
            val tlsSocket = SSLContext.getDefault().socketFactory
                .createSocket(socket, target.host, port, true) as SSLSocket
            activeSocket.set(tlsSocket)
            tlsSocket.soTimeout = config.idleTimeoutSeconds * 1000
            val parameters = tlsSocket.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.serverNames = listOf(SNIHostName(target.host))
            tlsSocket.sslParameters = parameters
            tlsSocket.startHandshake()
            tlsHandshakeMs.set(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tlsStarted)
            )

            phase.set(Phase.HTTP_HEADERS)
            writeHttpRequest(tlsSocket, target, config)
            val reader = BufferedInputStream(tlsSocket.inputStream)
            val statusLine = readHttpLine(reader, tlsSocket, config, startedAtNanos)
                ?: throw SelfTestFailure("empty_http_response")
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw SelfTestFailure("invalid_http_response")
            httpStatus.set(statusCode)

            var contentLength: Long? = null
            var chunked = false
            var headerBytes = statusLine.length + 2
            while (true) {
                val line = readHttpLine(reader, tlsSocket, config, startedAtNanos)
                    ?: throw SelfTestFailure("truncated_http_headers")
                headerBytes += line.length + 2
                if (headerBytes > MAX_HTTP_HEADER_BYTES) {
                    throw SelfTestFailure("http_headers_too_large")
                }
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                when (line.substring(0, separator).trim().lowercase()) {
                    "content-length" -> {
                        val parsedLength = line.substring(separator + 1).trim().toLongOrNull()
                            ?: throw SelfTestFailure("invalid_content_length")
                        if (parsedLength < 0 ||
                            (contentLength != null && contentLength != parsedLength)
                        ) {
                            throw SelfTestFailure("invalid_content_length")
                        }
                        contentLength = parsedLength
                    }
                    "transfer-encoding" ->
                        chunked = line.substring(separator + 1)
                            .contains("chunked", ignoreCase = true)
                }
            }

            if (statusCode !in 200..299) return result(
                target,
                "http_status",
                config,
                startedAtNanos,
                bytesReceived,
                socksConnectMs,
                tlsHandshakeMs,
                transferMs,
                httpStatus
            )

            phase.set(Phase.BODY)
            transferMs.set(0)
            val outcome = when {
                chunked -> readChunkedBody(
                    reader, tlsSocket, config, startedAtNanos, bytesReceived, transferMs
                )
                else -> readBody(
                    reader,
                    tlsSocket,
                    config,
                    startedAtNanos,
                    bytesReceived,
                    transferMs,
                    contentLength
                )
            }
            return result(
                target,
                outcome,
                config,
                startedAtNanos,
                bytesReceived,
                socksConnectMs,
                tlsHandshakeMs,
                transferMs,
                httpStatus
            )
        } finally {
            activeSocket.compareAndSet(activeSocket.get(), null)
            runCatching { socket.close() }
        }
    }

    private fun writeHttpRequest(
        socket: SSLSocket,
        target: Target,
        config: TransferTestConfig
    ) {
        val bytes = config.payloadKiB * 1024
        val request = "GET ${target.path(bytes)} HTTP/1.1\r\n" +
            "Host: ${target.host}\r\n" +
            "Accept: */*\r\n" +
            "Accept-Encoding: identity\r\n" +
            "Range: bytes=0-${bytes - 1}\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        socket.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
        socket.outputStream.flush()
    }

    private fun readBody(
        input: BufferedInputStream,
        socket: Socket,
        config: TransferTestConfig,
        startedAtNanos: Long,
        bytesReceived: AtomicInteger,
        transferMs: AtomicLong,
        contentLength: Long?
    ): String {
        val maxBytes = config.payloadKiB * 1024
        val buffer = ByteArray(8192)
        val transferStartedAt = System.nanoTime()
        var remainingContent = contentLength
        try {
            while (bytesReceived.get() < maxBytes) {
                setReadTimeout(socket, config, startedAtNanos)
                val remainingBytes = maxBytes - bytesReceived.get()
                val requested = minOf(buffer.size, remainingBytes)
                val count = if (remainingContent == null) {
                    input.read(buffer, 0, requested)
                } else {
                    val contentBytesLeft = remainingContent.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    if (contentBytesLeft == 0) -1
                    else input.read(buffer, 0, minOf(requested, contentBytesLeft))
                }
                if (count < 0) return "short_response"
                bytesReceived.addAndGet(count)
                if (remainingContent != null) remainingContent -= count
            }
            return "complete"
        } finally {
            transferMs.set(TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - transferStartedAt
            ))
        }
    }

    private fun readChunkedBody(
        input: BufferedInputStream,
        socket: Socket,
        config: TransferTestConfig,
        startedAtNanos: Long,
        bytesReceived: AtomicInteger,
        transferMs: AtomicLong
    ): String {
        val maxBytes = config.payloadKiB * 1024
        val buffer = ByteArray(8192)
        val transferStartedAt = System.nanoTime()
        try {
            while (bytesReceived.get() < maxBytes) {
                val sizeLine = readHttpLine(input, socket, config, startedAtNanos)
                    ?: throw SelfTestFailure("truncated_chunked_body")
                val chunkSize = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                    ?: throw SelfTestFailure("invalid_chunk_size")
                if (chunkSize == 0L) {
                    while (readHttpLine(input, socket, config, startedAtNanos)?.isNotEmpty() == true) {
                        Unit
                    }
                    return "short_response"
                }
                var chunkRemaining = chunkSize
                while (chunkRemaining > 0 && bytesReceived.get() < maxBytes) {
                    setReadTimeout(socket, config, startedAtNanos)
                    val remainingBytes = maxBytes - bytesReceived.get()
                    val count = input.read(
                        buffer,
                        0,
                        minOf(buffer.size.toLong(), chunkRemaining, remainingBytes.toLong()).toInt()
                    )
                    if (count < 0) throw SelfTestFailure("truncated_chunked_body")
                    bytesReceived.addAndGet(count)
                    chunkRemaining -= count
                }
                if (bytesReceived.get() >= maxBytes) return "complete"
                if (readExact(input, socket, config, startedAtNanos, 2)
                        .contentEquals(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
                        .not()
                ) {
                    throw SelfTestFailure("invalid_chunk_terminator")
                }
            }
            return "complete"
        } finally {
            transferMs.set(TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - transferStartedAt
            ))
        }
    }

    private fun readHttpLine(
        input: BufferedInputStream,
        socket: Socket,
        config: TransferTestConfig,
        startedAtNanos: Long
    ): String? {
        val line = StringBuilder()
        while (line.length < MAX_HTTP_LINE_BYTES) {
            setReadTimeout(socket, config, startedAtNanos)
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) {
                if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                return line.toString()
            }
            line.append(byte.toChar())
        }
        throw SelfTestFailure("http_line_too_long")
    }

    private fun readExact(
        input: BufferedInputStream,
        socket: Socket,
        config: TransferTestConfig,
        startedAtNanos: Long,
        count: Int
    ): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            setReadTimeout(socket, config, startedAtNanos)
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw SelfTestFailure("unexpected_proxy_close")
            offset += read
        }
        return result
    }

    private fun setReadTimeout(
        socket: Socket,
        config: TransferTestConfig,
        startedAtNanos: Long
    ) {
        val remainingMs = config.hostTimeoutSeconds * 1000 - elapsedMs(startedAtNanos)
        if (remainingMs <= 0) throw SelfTestFailure("overall_timeout")
        socket.soTimeout = minOf(config.idleTimeoutSeconds * 1000, remainingMs.toInt())
    }

    private fun result(
        target: Target,
        outcome: String,
        config: TransferTestConfig,
        startedAtNanos: Long,
        bytesReceived: AtomicInteger,
        socksConnectMs: AtomicLong,
        tlsHandshakeMs: AtomicLong,
        transferMs: AtomicLong,
        httpStatus: AtomicInteger
    ): TransferTestResult = TransferTestResult(
        service = target.service,
        outcome = outcome,
        bytesReceived = bytesReceived.get(),
        elapsedMs = elapsedMs(startedAtNanos),
        socksConnectMs = socksConnectMs.get().takeIf { it >= 0 },
        tlsHandshakeMs = tlsHandshakeMs.get().takeIf { it >= 0 },
        transferMs = transferMs.get().takeIf { it >= 0 },
        httpStatus = httpStatus.get().takeIf { it >= 0 }
    )

    private fun elapsedMs(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
}
