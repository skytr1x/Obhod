package com.skytr1x.obhod.network

import android.content.Context
import android.net.Network
import android.net.VpnService
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

    data class Result(
        val signal: Signal,
        val fromCache: Boolean,
        val dnsComparison: DnsComparison
    )

    enum class DnsComparison(val storageValue: String) {
        MATCH("match"),
        ANSWER_MISMATCH("answer_mismatch"),
        SYSTEM_FAILED("system_failed"),
        DOH_FAILED("doh_failed"),
        UNKNOWN("unknown");

        companion object {
            fun fromStorage(value: String?): DnsComparison =
                values().firstOrNull { it.storageValue == value } ?: UNKNOWN
        }
    }

    data class Calibration(val signal: Signal, val dnsComparison: DnsComparison)

    private enum class ProbeResult {
        TLS_OK,
        TLS_INTERRUPTED,
        TCP_UNAVAILABLE,
        UNKNOWN
    }

    private const val PREFS_NAME = "network_calibration"
    private const val CACHE_TTL_MS = 30L * 60L * 1000L
    private const val TOTAL_BUDGET_MS = 7000L
    private const val CONNECT_TIMEOUT_MS = 1700
    private const val HANDSHAKE_TIMEOUT_MS = 1700
    private val probeHosts = listOf(
        "www.google.com",
        "www.microsoft.com",
        "www.cloudflare.com",
        "gateway.discord.gg"
    )
    private const val DNS_CONTROL_HOST = "gateway.discord.gg"

    fun resolve(context: Context, networkKey: String, network: Network?): Result {
        if (network == null || networkKey.isBlank() || networkKey == "default") {
            return Result(
                Signal.UNKNOWN,
                fromCache = false,
                dnsComparison = DnsComparison.UNKNOWN
            )
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheKey = sha256(networkKey)
        val timestampKey = "${cacheKey}_at"
        val signalKey = "${cacheKey}_signal"
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(timestampKey, 0L)
        if (cachedAt > 0L && now >= cachedAt && now - cachedAt < CACHE_TTL_MS) {
            val comparison = DnsComparison.fromStorage(
                prefs.getString("${cacheKey}_dns", null)
            )
            return Result(
                Signal.fromStorage(prefs.getString(signalKey, null)),
                fromCache = true,
                dnsComparison = comparison
            )
        }

        val calibration = probe(context, network)
        prefs.edit()
            .putString(signalKey, calibration.signal.storageValue)
            .putString("${cacheKey}_dns", calibration.dnsComparison.storageValue)
            .putLong(timestampKey, System.currentTimeMillis())
            .apply()
        return Result(
            calibration.signal,
            fromCache = false,
            dnsComparison = calibration.dnsComparison
        )
    }

    private fun probe(context: Context, network: Network): Calibration {
        val executor = Executors.newFixedThreadPool(probeHosts.size + 1) { runnable ->
            Thread(runnable, "network-calibration").apply { isDaemon = true }
        }
        val futures = try {
            executor.invokeAll(
                probeHosts.map { host ->
                    Callable<Any> { probeHost(network, host) }
                } + Callable<Any> { compareDns(context, network) },
                TOTAL_BUDGET_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Calibration(Signal.UNKNOWN, DnsComparison.UNKNOWN)
        } finally {
            executor.shutdownNow()
        }

        val hostResults = futures.take(probeHosts.size).mapIndexed { index, future ->
            val result = if (!future.isDone || future.isCancelled) null
            else runCatching { future.get() as? ProbeResult }.getOrNull()
            probeHosts[index] to result
        }
        val results = hostResults.mapNotNull { it.second }
        val dnsComparison = futures.getOrNull(probeHosts.size)?.let { future ->
            if (!future.isDone || future.isCancelled) DnsComparison.UNKNOWN
            else runCatching { future.get() as? DnsComparison }.getOrNull()
                ?: DnsComparison.UNKNOWN
        } ?: DnsComparison.UNKNOWN
        val tlsOk = results.count { it == ProbeResult.TLS_OK }
        val tlsInterrupted = results.count { it == ProbeResult.TLS_INTERRUPTED }

        val discordProbe = hostResults.firstOrNull { it.first == DNS_CONTROL_HOST }?.second
        val signal = when {
            discordProbe == ProbeResult.TLS_INTERRUPTED -> Signal.TLS_INTERFERENCE
            tlsOk >= 2 -> Signal.TLS_REACHABLE
            tlsInterrupted >= 2 && tlsOk == 0 -> Signal.TLS_INTERFERENCE
            else -> Signal.UNKNOWN
        }
        return Calibration(signal, dnsComparison)
    }

    private fun compareDns(context: Context, network: Network): DnsComparison {
        val query = DnsOverHttpsResolver.createAQuery(DNS_CONTROL_HOST)
            ?: return DnsComparison.UNKNOWN
        val systemAddresses = try {
            network.getAllByName(DNS_CONTROL_HOST)
                .mapNotNull { it.hostAddress }
                .toSet()
        } catch (_: IOException) {
            emptySet()
        }
        val vpnService = context as? VpnService
        val dohResponse = DnsOverHttpsResolver.resolve(query, network) { socket ->
            vpnService?.protect(socket) ?: true
        } ?: return if (systemAddresses.isEmpty()) {
            DnsComparison.SYSTEM_FAILED
        } else {
            DnsComparison.DOH_FAILED
        }
        val dohAddresses = DnsOverHttpsResolver.ipv4Answers(dohResponse)
        if (dohAddresses.isEmpty()) return DnsComparison.UNKNOWN
        return when {
            systemAddresses.isEmpty() -> DnsComparison.SYSTEM_FAILED
            systemAddresses.intersect(dohAddresses).isEmpty() ->
                DnsComparison.ANSWER_MISMATCH
            else -> DnsComparison.MATCH
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
