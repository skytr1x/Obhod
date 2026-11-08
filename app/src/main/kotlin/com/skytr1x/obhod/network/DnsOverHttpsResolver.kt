package com.skytr1x.obhod.network

import android.net.Network
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName

internal object DnsOverHttpsResolver {
    private data class Provider(val host: String, val ips: List<String>)
    private data class Endpoint(val provider: Provider, val ip: String) {
        val key: String get() = "${provider.host}@$ip"
    }
    private data class CachedAnswer(val bytes: ByteArray, val expiresAtNanos: Long)
    private data class ConnectionKey(val networkHandle: Long, val host: String)
    private data class DnsHttpsConnection(
        val host: String,
        val socket: SSLSocket,
        val input: BufferedInputStream,
        val output: OutputStream,
        var lastUsedAtNanos: Long
    )
    private data class HttpDnsResponse(
        val body: ByteArray?,
        val reusable: Boolean,
        val retryOnNewConnection: Boolean = false
    )
    private data class ProviderState(
        var failures: Int = 0,
        var openUntilNanos: Long = 0,
        var preferredEndpoint: String? = null
    )

    private val providers = listOf(
        Provider("cloudflare-dns.com", listOf("1.1.1.1", "1.0.0.1")),
        Provider("dns.google", listOf("8.8.8.8", "8.8.4.4")),
        Provider("dns.quad9.net", listOf("9.9.9.9", "149.112.112.112"))
    )
    private val cache = ConcurrentHashMap<String, CachedAnswer>()
    private val threadConnections =
        ThreadLocal.withInitial { mutableMapOf<ConnectionKey, DnsHttpsConnection>() }
    private val providerStates = LinkedHashMap<Long, ProviderState>(16, 0.75f, true)
    private val providerStateLock = Any()
    private const val TOTAL_TIMEOUT_MS = 2500L
    private const val CONNECT_TIMEOUT_MS = 700
    private const val READ_TIMEOUT_MS = 1000
    private const val MAX_DNS_MESSAGE_SIZE = 65535
    private const val CIRCUIT_FAILURE_THRESHOLD = 3
    private const val CIRCUIT_OPEN_MS = 60_000L
    private const val CONNECTION_IDLE_TIMEOUT_MS = 30_000L
    private const val MAX_CONNECTIONS_PER_THREAD = 3
    private const val MAX_CACHE_ENTRIES = 512

    fun resolve(
        query: ByteArray,
        network: Network?,
        protectSocket: (Socket) -> Boolean
    ): ByteArray? {
        if (!isDnsQuery(query)) return null

        val now = System.nanoTime()
        val key = "${network?.networkHandle ?: 0L}:${cacheKey(query)}"
        cache[key]?.let { cached ->
            if (cached.expiresAtNanos > now) return withQueryId(cached.bytes, query)
            cache.remove(key, cached)
        }

        val networkHandle = network?.networkHandle ?: 0L
        if (!canAttemptDoh(networkHandle, now)) return null

        val deadline = now + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS)
        val preferredEndpoint = preferredProvider(networkHandle)
        val endpoints = providers.flatMap { provider ->
            provider.ips.map { ip -> Endpoint(provider, ip) }
        }.let { all ->
            if (preferredEndpoint == null) all else
                all.sortedBy { if (it.key == preferredEndpoint) 0 else 1 }
        }
        for (endpoint in endpoints) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(
                deadline - System.nanoTime()
            ).toInt()
            if (remainingMs <= 0) break
            val response = try {
                queryProvider(
                    endpoint.provider.host,
                    endpoint.ip,
                    query,
                    network,
                    protectSocket,
                    remainingMs
                )
            } catch (_: IOException) {
                null
            }
            if (response != null && isDnsResponse(response, query)) {
                cacheTtlMillis(response)?.let { ttl ->
                    trimCache(System.nanoTime())
                    cache[key] = CachedAnswer(
                        response.copyOf(),
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttl)
                    )
                }
                recordDohSuccess(networkHandle, endpoint.key)
                return response
            }
        }
        recordDohFailure(networkHandle)
        return null
    }

    private fun trimCache(now: Long) {
        if (cache.size < MAX_CACHE_ENTRIES) return
        cache.entries.removeIf { it.value.expiresAtNanos <= now }
        if (cache.size >= MAX_CACHE_ENTRIES) {
            val excess = cache.size - MAX_CACHE_ENTRIES + 1
            cache.keys.iterator().let { keys ->
                repeat(excess) {
                    if (keys.hasNext()) cache.remove(keys.next())
                }
            }
        }
    }

    private fun canAttemptDoh(networkHandle: Long, now: Long): Boolean =
        synchronized(providerStateLock) {
            val state = providerState(networkHandle)
            if (state.openUntilNanos > now) {
                false
            } else {
                if (state.openUntilNanos != 0L) {
                    state.openUntilNanos = 0
                    state.failures = 0
                }
                true
            }
        }

    private fun preferredProvider(networkHandle: Long): String? =
        synchronized(providerStateLock) {
            providerState(networkHandle).preferredEndpoint
        }

    private fun recordDohSuccess(networkHandle: Long, endpointKey: String) {
        synchronized(providerStateLock) {
            val state = providerState(networkHandle)
            state.failures = 0
            state.openUntilNanos = 0
            state.preferredEndpoint = endpointKey
        }
    }

    private fun recordDohFailure(networkHandle: Long) {
        synchronized(providerStateLock) {
            val state = providerState(networkHandle)
            state.failures += 1
            if (state.failures >= CIRCUIT_FAILURE_THRESHOLD) {
                state.openUntilNanos =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CIRCUIT_OPEN_MS)
            }
        }
    }

    private fun providerState(networkHandle: Long): ProviderState {
        providerStates[networkHandle]?.let { return it }
        val state = ProviderState()
        providerStates[networkHandle] = state
        while (providerStates.size > 32) {
            val oldestKey = providerStates.entries.iterator().next().key
            providerStates.remove(oldestKey)
        }
        return state
    }

    fun createAQuery(host: String): ByteArray? {
        val asciiHost = try {
            IDN.toASCII(host.trimEnd('.'))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (asciiHost.length > 253) return null
        val labels = asciiHost.split('.')
        if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return null
        val question = ByteArrayOutputStream()
        for (label in labels) {
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            if (bytes.any { it.toInt() < 0 }) return null
            question.write(bytes.size)
            question.write(bytes)
        }
        question.write(0)
        question.write(byteArrayOf(0, 1, 0, 1))
        val id = SecureRandom().nextInt(65536)
        return byteArrayOf(
            (id shr 8).toByte(), id.toByte(),
            1, 0, 0, 1, 0, 0, 0, 0, 0, 0
        ) + question.toByteArray()
    }

    fun ipAnswers(response: ByteArray): Set<String> {
        if (response.size < 12 || response[2].toInt() and 0x80 == 0) return emptySet()
        val questions = readU16(response, 4)
        val answers = readU16(response, 6)
        var offset = 12
        repeat(questions) {
            offset = skipName(response, offset) ?: return emptySet()
            if (offset + 4 > response.size) return emptySet()
            offset += 4
        }
        val addresses = linkedSetOf<String>()
        repeat(answers) {
            offset = skipName(response, offset) ?: return emptySet()
            if (offset + 10 > response.size) return emptySet()
            val type = readU16(response, offset)
            val recordClass = readU16(response, offset + 2)
            val dataLength = readU16(response, offset + 8)
            offset += 10
            if (offset + dataLength > response.size) return emptySet()
            val addressLength = when (type) {
                1 -> 4
                28 -> 16
                else -> 0
            }
            if (recordClass == 1 && dataLength == addressLength && addressLength > 0) {
                addresses += InetAddress.getByAddress(
                    response.copyOfRange(offset, offset + addressLength)
                ).hostAddress.orEmpty().substringBefore('%').lowercase()
            }
            offset += dataLength
        }
        return addresses
    }

    private fun queryProvider(
        host: String,
        ip: String,
        query: ByteArray,
        network: Network?,
        protectSocket: (Socket) -> Boolean,
        remainingMs: Int
    ): ByteArray? {
        val networkHandle = network?.networkHandle ?: 0L
        val key = ConnectionKey(networkHandle, host)
        val connections = checkNotNull(threadConnections.get())
        val now = System.nanoTime()
        val staleKeys = connections.filter { (connectionKey, connection) ->
            connectionKey.networkHandle != networkHandle ||
                connection.socket.isClosed ||
                now - connection.lastUsedAtNanos >
                    TimeUnit.MILLISECONDS.toNanos(CONNECTION_IDLE_TIMEOUT_MS)
        }.keys
        staleKeys.forEach { staleKey -> closeConnection(connections.remove(staleKey)) }

        val deadline = now + TimeUnit.MILLISECONDS.toNanos(remainingMs.toLong())
        val pooled = connections.remove(key)
        if (pooled != null) {
            try {
                val response = exchange(pooled, query, deadline)
                if (response.body != null) {
                    if (response.reusable) {
                        pooled.lastUsedAtNanos = System.nanoTime()
                        connections[key] = pooled
                    } else {
                        closeConnection(pooled)
                    }
                    return response.body
                }
                closeConnection(pooled)
                if (!response.retryOnNewConnection) return null
            } catch (_: IOException) {
                closeConnection(pooled)
            }
        }

        val connectTimeoutMs = remainingTimeoutMs(deadline)
        if (connectTimeoutMs <= 0) return null
        val connection = connect(
            host,
            ip,
            network,
            protectSocket,
            connectTimeoutMs
        )
        try {
            val response = exchange(connection, query, deadline)
            if (response.reusable && connections.size < MAX_CONNECTIONS_PER_THREAD) {
                connection.lastUsedAtNanos = System.nanoTime()
                connections[key] = connection
            } else {
                closeConnection(connection)
            }
            return response.body
        } catch (error: IOException) {
            closeConnection(connection)
            throw error
        }
    }

    private fun connect(
        host: String,
        ip: String,
        network: Network?,
        protectSocket: (Socket) -> Boolean,
        remainingMs: Int
    ): DnsHttpsConnection {
        val rawSocket = Socket()
        try {
            network?.bindSocket(rawSocket)
            if (!protectSocket(rawSocket)) throw IOException("VPN socket protection failed")
            rawSocket.keepAlive = true
            rawSocket.connect(
                InetSocketAddress(ip, 443),
                minOf(CONNECT_TIMEOUT_MS, remainingMs)
            )
            rawSocket.soTimeout = minOf(READ_TIMEOUT_MS, remainingMs)

            val ssl = SSLContext.getDefault().socketFactory
                .createSocket(rawSocket, host, 443, true) as SSLSocket
            val parameters = ssl.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.serverNames = listOf(SNIHostName(host))
            ssl.sslParameters = parameters
            ssl.soTimeout = minOf(READ_TIMEOUT_MS, remainingMs)
            ssl.startHandshake()
            return DnsHttpsConnection(
                host,
                ssl,
                BufferedInputStream(ssl.inputStream),
                ssl.outputStream,
                System.nanoTime()
            )
        } catch (error: IOException) {
            runCatching { rawSocket.close() }
            throw error
        } catch (error: RuntimeException) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private fun exchange(
        connection: DnsHttpsConnection,
        query: ByteArray,
        deadlineNanos: Long
    ): HttpDnsResponse {
        updateReadTimeout(connection.socket, deadlineNanos)
        connection.output.write(
            ("POST /dns-query HTTP/1.1\r\n" +
                "Host: ${connection.host}\r\n" +
                "Accept: application/dns-message\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Content-Length: ${query.size}\r\n" +
                "Connection: keep-alive\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
        )
        connection.output.write(query)
        connection.output.flush()

        val status = readLine(connection.input, connection.socket, deadlineNanos)
            ?: return HttpDnsResponse(
                null,
                reusable = false,
                retryOnNewConnection = true
            )
        val statusCode = status.substringAfter(' ').takeWhile(Char::isDigit)
        if (statusCode != "200") return HttpDnsResponse(null, reusable = false)

        var contentLength = -1
        var chunked = false
        var connectionClose = false
        while (true) {
            val line = readLine(connection.input, connection.socket, deadlineNanos)
                ?: return HttpDnsResponse(
                    null,
                    reusable = false,
                    retryOnNewConnection = true
                )
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            when (line.substring(0, separator).trim().lowercase()) {
                "content-length" -> contentLength =
                    line.substring(separator + 1).trim().toIntOrNull()
                        ?: return HttpDnsResponse(null, reusable = false)
                "transfer-encoding" -> chunked =
                    line.substring(separator + 1).contains("chunked", ignoreCase = true)
                "connection" -> connectionClose =
                    line.substring(separator + 1).contains("close", ignoreCase = true)
            }
        }
        val body = when {
            contentLength >= 0 -> readExactly(
                connection.input,
                contentLength,
                minLength = 12,
                socket = connection.socket,
                deadlineNanos = deadlineNanos
            )
            chunked -> readChunked(connection.input, connection.socket, deadlineNanos)
            else -> readToEnd(connection.input, connection.socket, deadlineNanos)
        }
        return HttpDnsResponse(
            body,
            reusable = body != null && !connectionClose && (contentLength >= 0 || chunked)
        )
    }

    private fun remainingTimeoutMs(deadlineNanos: Long): Int =
        TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            .coerceAtLeast(0)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun updateReadTimeout(socket: Socket, deadlineNanos: Long) {
        val remainingMs = remainingTimeoutMs(deadlineNanos)
        if (remainingMs <= 0) throw SocketTimeoutException("DoH request timed out")
        socket.soTimeout = minOf(READ_TIMEOUT_MS, remainingMs)
    }

    private fun closeConnection(connection: DnsHttpsConnection?) {
        if (connection != null) runCatching { connection.socket.close() }
    }

    private fun readLine(
        input: BufferedInputStream,
        socket: Socket? = null,
        deadlineNanos: Long = Long.MAX_VALUE
    ): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= 8192) {
            if (socket != null) updateReadTimeout(socket, deadlineNanos)
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line
                .toByteArray().toString(StandardCharsets.US_ASCII).trimEnd('\r')
            if (byte == '\n'.code) {
                return line.toByteArray().toString(StandardCharsets.US_ASCII).trimEnd('\r')
            }
            line.write(byte)
        }
        throw IOException("HTTP header line too long")
    }

    private fun readExactly(
        input: BufferedInputStream,
        length: Int,
        minLength: Int = 0,
        socket: Socket? = null,
        deadlineNanos: Long = Long.MAX_VALUE
    ): ByteArray? {
        if (length !in minLength..MAX_DNS_MESSAGE_SIZE) return null
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            if (socket != null) updateReadTimeout(socket, deadlineNanos)
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("Truncated DoH response")
            offset += count
        }
        return bytes
    }

    private fun readChunked(
        input: BufferedInputStream,
        socket: Socket? = null,
        deadlineNanos: Long = Long.MAX_VALUE
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input, socket, deadlineNanos) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: return null
            if (size == 0) {
                while (readLine(input, socket, deadlineNanos)?.isNotEmpty() == true) Unit
                break
            }
            if (size < 0 || output.size() + size > MAX_DNS_MESSAGE_SIZE) return null
            val chunk = readExactly(input, size, socket = socket, deadlineNanos = deadlineNanos)
                ?: return null
            output.write(chunk)
            if (socket != null) updateReadTimeout(socket, deadlineNanos)
            if (input.read() != '\r'.code || input.read() != '\n'.code) return null
        }
        return output.toByteArray().takeIf { it.size >= 12 }
    }

    private fun readToEnd(
        input: BufferedInputStream,
        socket: Socket? = null,
        deadlineNanos: Long = Long.MAX_VALUE
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            if (socket != null) updateReadTimeout(socket, deadlineNanos)
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_DNS_MESSAGE_SIZE) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray().takeIf { it.size >= 12 }
    }

    private fun isDnsQuery(message: ByteArray): Boolean =
        message.size in 12..MAX_DNS_MESSAGE_SIZE &&
            (message[2].toInt() and 0x80) == 0 &&
            readU16(message, 4) in 1..16

    private fun isDnsResponse(response: ByteArray, query: ByteArray): Boolean =
        response.size in 12..MAX_DNS_MESSAGE_SIZE &&
            response[0] == query[0] && response[1] == query[1] &&
            (response[2].toInt() and 0x80) != 0 &&
            (response[3].toInt() and 0x0f) in setOf(0, 3)

    private fun cacheKey(query: ByteArray): String =
        query.copyOf().also {
            it[0] = 0
            it[1] = 0
        }.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun withQueryId(response: ByteArray, query: ByteArray): ByteArray =
        response.copyOf().also {
            it[0] = query[0]
            it[1] = query[1]
        }

    internal fun cacheTtlMillis(message: ByteArray): Long? {
        if (message.size < 12 || (message[2].toInt() and 0x82) != 0x80) return null
        val responseCode = message[3].toInt() and 0x0f
        val questionCount = readU16(message, 4)
        val counts = (6..10 step 2).map { readU16(message, it) }
        val negativeResponse = responseCode == 3 ||
            (responseCode == 0 && counts[0] == 0)
        if (responseCode != 0 && responseCode != 3) return null
        var offset = 12
        repeat(questionCount) {
            offset = skipName(message, offset) ?: return null
            if (offset + 4 > message.size) return null
            offset += 4
        }

        var minRecordTtl = Long.MAX_VALUE
        var minNegativeTtl = Long.MAX_VALUE
        for ((sectionIndex, recordCount) in counts.withIndex()) {
            repeat(recordCount) {
                offset = skipName(message, offset) ?: return null
                if (offset + 10 > message.size) return null
                val type = readU16(message, offset)
                val recordClass = readU16(message, offset + 2)
                val ttl = readU32(message, offset + 4)
                val dataLength = readU16(message, offset + 8)
                offset += 10
                val dataEnd = offset + dataLength
                if (dataEnd > message.size) return null
                if (type != 41) minRecordTtl = minOf(minRecordTtl, ttl)
                if (negativeResponse && sectionIndex == 1 && type == 6 &&
                    recordClass == 1
                ) {
                    val mailboxOffset = skipName(message, offset) ?: return null
                    val fixedOffset = skipName(message, mailboxOffset) ?: return null
                    if (fixedOffset + 20 > dataEnd) return null
                    val soaMinimum = readU32(message, fixedOffset + 16)
                    minNegativeTtl = minOf(minNegativeTtl, minOf(ttl, soaMinimum))
                }
                offset += dataLength
            }
        }
        val ttlSeconds = if (negativeResponse) {
            if (minNegativeTtl == Long.MAX_VALUE) return null
            minOf(minRecordTtl, minNegativeTtl)
        } else {
            minRecordTtl
        }
        return ttlSeconds.takeIf { it > 0 && it != Long.MAX_VALUE }
            ?.coerceAtMost(300)
            ?.times(1000)
    }

    private fun skipName(message: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < message.size) {
            val length = message[offset].toInt() and 0xff
            when {
                length == 0 -> return offset + 1
                (length and 0xc0) == 0xc0 -> {
                    if (offset + 1 >= message.size) return null
                    val target = ((length and 0x3f) shl 8) or
                        (message[offset + 1].toInt() and 0xff)
                    if (target < 12 || target >= offset || target >= message.size) return null
                    return offset + 2
                }
                (length and 0xc0) != 0 || offset + 1 + length > message.size -> return null
                else -> offset += length + 1
            }
        }
        return null
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
}
