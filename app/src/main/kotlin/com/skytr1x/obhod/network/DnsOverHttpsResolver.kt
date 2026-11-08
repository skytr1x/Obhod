package com.skytr1x.obhod.network

import android.net.Network
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName

internal object DnsOverHttpsResolver {
    private data class Provider(val host: String, val ips: List<String>)
    private data class CachedAnswer(val bytes: ByteArray, val expiresAtNanos: Long)

    private val providers = listOf(
        Provider("cloudflare-dns.com", listOf("1.1.1.1")),
        Provider("dns.google", listOf("8.8.8.8"))
    )
    private val cache = ConcurrentHashMap<String, CachedAnswer>()
    private const val TOTAL_TIMEOUT_MS = 2500L
    private const val CONNECT_TIMEOUT_MS = 700
    private const val READ_TIMEOUT_MS = 1000
    private const val MAX_DNS_MESSAGE_SIZE = 65535

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

        val deadline = now + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS)
        for (provider in providers) {
            for (ip in provider.ips) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(
                    deadline - System.nanoTime()
                ).toInt()
                if (remainingMs <= 0) return null
                val response = try {
                    queryProvider(
                        provider.host,
                        ip,
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
                        if (cache.size > 256) cache.clear()
                        cache[key] = CachedAnswer(
                            response.copyOf(),
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttl)
                        )
                    }
                    return response
                }
            }
        }
        return null
    }

    fun createAQuery(host: String): ByteArray? {
        val asciiHost = try {
            IDN.toASCII(host.trimEnd('.'))
        } catch (_: IllegalArgumentException) {
            return null
        }
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

    fun ipv4Answers(response: ByteArray): Set<String> {
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
            if (type == 1 && recordClass == 1 && dataLength == 4) {
                addresses += InetAddress.getByAddress(response.copyOfRange(offset, offset + 4))
                    .hostAddress.orEmpty()
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
        val rawSocket = Socket()
        try {
            network?.bindSocket(rawSocket)
            if (!protectSocket(rawSocket)) throw IOException("VPN socket protection failed")
            rawSocket.connect(
                InetSocketAddress(ip, 443),
                minOf(CONNECT_TIMEOUT_MS, remainingMs)
            )
            rawSocket.soTimeout = minOf(READ_TIMEOUT_MS, remainingMs)

            val sslSocket = SSLContext.getDefault().socketFactory
                .createSocket(rawSocket, host, 443, true) as SSLSocket
            sslSocket.use { ssl ->
                val parameters = ssl.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                parameters.serverNames = listOf(SNIHostName(host))
                ssl.sslParameters = parameters
                ssl.soTimeout = minOf(READ_TIMEOUT_MS, remainingMs)
                ssl.startHandshake()

                val output = ssl.outputStream
                output.write(
                    ("POST /dns-query HTTP/1.1\r\n" +
                        "Host: $host\r\n" +
                        "Accept: application/dns-message\r\n" +
                        "Content-Type: application/dns-message\r\n" +
                        "Content-Length: ${query.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                )
                output.write(query)
                output.flush()

                val input = BufferedInputStream(ssl.inputStream)
                val status = readLine(input) ?: return null
                val statusCode = status.substringAfter(' ').takeWhile(Char::isDigit)
                if (statusCode != "200") return null

                var contentLength = -1
                var chunked = false
                while (true) {
                    val line = readLine(input) ?: return null
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator <= 0) continue
                    when (line.substring(0, separator).trim().lowercase()) {
                        "content-length" -> contentLength =
                            line.substring(separator + 1).trim().toIntOrNull() ?: return null
                        "transfer-encoding" -> chunked =
                            line.substring(separator + 1).contains("chunked", ignoreCase = true)
                    }
                }
                return when {
                    contentLength >= 0 -> readExactly(input, contentLength, minLength = 12)
                    chunked -> readChunked(input)
                    else -> readToEnd(input)
                }
            }
        } finally {
            runCatching { rawSocket.close() }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= 8192) {
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
        minLength: Int = 0
    ): ByteArray? {
        if (length !in minLength..MAX_DNS_MESSAGE_SIZE) return null
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("Truncated DoH response")
            offset += count
        }
        return bytes
    }

    private fun readChunked(input: BufferedInputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: return null
            if (size == 0) {
                while (readLine(input)?.isNotEmpty() == true) Unit
                break
            }
            if (size < 0 || output.size() + size > MAX_DNS_MESSAGE_SIZE) return null
            val chunk = readExactly(input, size) ?: return null
            output.write(chunk)
            if (input.read() != '\r'.code || input.read() != '\n'.code) return null
        }
        return output.toByteArray().takeIf { it.size >= 12 }
    }

    private fun readToEnd(input: BufferedInputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
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

    private fun cacheTtlMillis(message: ByteArray): Long? {
        if (message.size < 12 || (message[2].toInt() and 0x80) == 0 ||
            (message[3].toInt() and 0x0f) != 0
        ) return null
        val questionCount = readU16(message, 4)
        val counts = (6..10 step 2).map { readU16(message, it) }
        var offset = 12
        repeat(questionCount) {
            offset = skipName(message, offset) ?: return null
            if (offset + 4 > message.size) return null
            offset += 4
        }

        var minTtl = Long.MAX_VALUE
        for (recordCount in counts) {
            repeat(recordCount) {
                offset = skipName(message, offset) ?: return null
                if (offset + 10 > message.size) return null
                val type = readU16(message, offset)
                val ttl = readU32(message, offset + 4)
                val dataLength = readU16(message, offset + 8)
                offset += 10
                if (offset + dataLength > message.size) return null
                if (type != 41) minTtl = minOf(minTtl, ttl)
                offset += dataLength
            }
        }
        return minTtl.takeIf { it in 1..300 }?.times(1000)
    }

    private fun skipName(message: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < message.size) {
            val length = message[offset].toInt() and 0xff
            when {
                length == 0 -> return offset + 1
                (length and 0xc0) == 0xc0 -> {
                    if (offset + 1 >= message.size) return null
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
