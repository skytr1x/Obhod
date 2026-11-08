package com.skytr1x.obhod.network

import android.content.Context
import android.net.Network
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

internal object NetworkCalibrator {
    enum class Signal(val storageValue: String) {
        TLS_INTERFERENCE("tls_interference"),
        TLS_REACHABLE("tls_reachable"),
        UNKNOWN("unknown");

        companion object {
            fun fromStorage(value: String?): Signal =
                values().firstOrNull { it.storageValue == value } ?: UNKNOWN
        }
    }

    data class Result(val signal: Signal, val fromCache: Boolean)

    private enum class ProbeResult {
        TLS_OK,
        TLS_INTERRUPTED,
        TCP_UNAVAILABLE,
        UNKNOWN
    }

    private const val PREFS_NAME = "network_calibration"
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private const val TOTAL_BUDGET_MS = 6000L
    private const val CONNECT_TIMEOUT_MS = 1700
    private const val HANDSHAKE_TIMEOUT_MS = 1700
    private val probeHosts = listOf(
        "www.google.com",
        "www.microsoft.com",
        "www.cloudflare.com"
    )

    fun resolve(context: Context, networkKey: String, network: Network?): Result {
        if (network == null || networkKey.isBlank() || networkKey == "default") {
            return Result(Signal.UNKNOWN, fromCache = false)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheKey = sha256(networkKey)
        val timestampKey = "${cacheKey}_at"
        val signalKey = "${cacheKey}_signal"
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(timestampKey, 0L)
        if (cachedAt > 0L && now >= cachedAt && now - cachedAt < CACHE_TTL_MS) {
            return Result(Signal.fromStorage(prefs.getString(signalKey, null)), fromCache = true)
        }

        val signal = probe(network)
        prefs.edit()
            .putString(signalKey, signal.storageValue)
            .putLong(timestampKey, System.currentTimeMillis())
            .apply()
        return Result(signal, fromCache = false)
    }

    private fun probe(network: Network): Signal {
        val executor = Executors.newFixedThreadPool(probeHosts.size) { runnable ->
            Thread(runnable, "network-calibration").apply { isDaemon = true }
        }
        val futures = try {
            executor.invokeAll(
                probeHosts.map { host -> Callable { probeHost(network, host) } },
                TOTAL_BUDGET_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Signal.UNKNOWN
        } finally {
            executor.shutdownNow()
        }

        val results = futures.mapNotNull { future ->
            if (!future.isDone || future.isCancelled) null
            else runCatching { future.get() }.getOrNull()
        }
        val tlsOk = results.count { it == ProbeResult.TLS_OK }
        val tlsInterrupted = results.count { it == ProbeResult.TLS_INTERRUPTED }

        return when {
            tlsOk >= 2 -> Signal.TLS_REACHABLE
            tlsInterrupted >= 2 && tlsOk == 0 -> Signal.TLS_INTERFERENCE
            else -> Signal.UNKNOWN
        }
    }

    private fun probeHost(network: Network, host: String): ProbeResult {
        var connectedSocket: Socket? = null
        var connected = false
        try {
            val addresses = network.getAllByName(host)
            for (address in addresses.take(2)) {
                val socket = Socket()
                try {
                    network.bindSocket(socket)
                    socket.connect(InetSocketAddress(address, 443), CONNECT_TIMEOUT_MS)
                    connectedSocket = socket
                    connected = true
                    break
                } catch (_: IOException) {
                    runCatching { socket.close() }
                }
            }
            val rawSocket = connectedSocket ?: return ProbeResult.TCP_UNAVAILABLE
            rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val tlsSocket = SSLContext.getDefault().socketFactory
                .createSocket(rawSocket, host, 443, true) as SSLSocket
            tlsSocket.use {
                it.soTimeout = HANDSHAKE_TIMEOUT_MS
                it.startHandshake()
            }
            return ProbeResult.TLS_OK
        } catch (error: Exception) {
            return if (connected && isTransportInterruption(error)) {
                ProbeResult.TLS_INTERRUPTED
            } else {
                ProbeResult.UNKNOWN
            }
        } finally {
            runCatching { connectedSocket?.close() }
        }
    }

    private fun isTransportInterruption(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SocketTimeoutException || cause is EOFException) return true
            if (cause is SocketException) {
                val message = cause.message.orEmpty().lowercase()
                if (listOf("reset", "broken pipe", "abort", "unexpected end").any(message::contains)) {
                    return true
                }
            }
            if (cause is SSLException) {
                val message = cause.message.orEmpty().lowercase()
                if (listOf("connection reset", "broken pipe", "unexpected eof").any(message::contains)) {
                    return true
                }
            }
            cause = cause.cause
        }
        return false
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
