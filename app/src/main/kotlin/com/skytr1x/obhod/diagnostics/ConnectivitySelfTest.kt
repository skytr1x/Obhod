package com.skytr1x.obhod.diagnostics

import android.net.Network
import com.skytr1x.obhod.network.DnsOverHttpsResolver
import com.skytr1x.obhod.proxy.NativeNetworkOperations
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

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
    val attempt: Int,
    val outcome: String,
    val bytesReceived: Int,
    val elapsedMs: Long,
    val dohResolveMs: Long?,
    val socksConnectMs: Long?,
    val tlsHandshakeMs: Long?,
    val transferMs: Long?,
    val httpStatus: Int?
) {
    fun asLogMessage(): String =
        "[SELFTEST] service=$service attempt=$attempt outcome=$outcome bytes=$bytesReceived " +
            "elapsed_ms=$elapsedMs socks_ms=${socksConnectMs ?: "-"} " +
            "doh_ms=${dohResolveMs ?: "-"} " +
            "tls_ms=${tlsHandshakeMs ?: "-"} transfer_ms=${transferMs ?: "-"} " +
            "http=${httpStatus ?: "-"}"
}

internal data class TransferServiceStats(
    val service: String,
    val httpResponsesReceived: Int,
    val attempts: Int,
    val medianElapsedMs: Long?,
    val medianBytesReceived: Int?,
    val medianTransferKiBPerSecond: Double?
)

internal data class TransferTestSummary(
    val config: TransferTestConfig,
    val repetitions: Int,
    val results: List<TransferTestResult>
) {
    fun serviceStats(): List<TransferServiceStats> =
        results.groupBy { it.service }.map { (service, attempts) ->
            val transferRates = attempts.mapNotNull { result ->
                val transferMs = result.transferMs?.takeIf { it > 0 } ?: return@mapNotNull null
                result.bytesReceived * 1000.0 / 1024.0 / transferMs
            }
            TransferServiceStats(
                service = service,
                httpResponsesReceived = attempts.count { it.httpStatus != null },
                attempts = attempts.size,
                medianElapsedMs = median(attempts.map { it.elapsedMs }),
                medianBytesReceived = median(attempts.map { it.bytesReceived.toLong() })?.toInt(),
                medianTransferKiBPerSecond = median(transferRates)
            )
        }

    private fun median(values: List<Long>): Long? {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun median(values: List<Double>): Double? {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

internal object ConnectivitySelfTest {
    const val REPETITIONS = 3

    private data class Target(
        val service: String,
        val host: String,
        val path: (Int) -> String
    )

    private val targets = listOf(
        Target("youtube", "i.ytimg.com") { "/vi/aqz-KE-bpKQ/maxresdefault.jpg" },
        Target("discord", "cdn.discordapp.com") { "/embed/avatars/0.png" },
        Target("4pda", "4pda.to") { "/favicon.ico" },
        Target("cloudflare", "speed.cloudflare.com") { bytes ->
            "/__down?bytes=$bytes"
        }
    )

    fun run(
        config: TransferTestConfig,
        network: Network?,
        onProgress: (service: String, attempt: Int) -> Unit,
        onResult: (TransferTestResult) -> Unit
    ): TransferTestSummary {
        val results = buildList {
            for (attempt in 1..REPETITIONS) {
                targets.forEach { target ->
                    ensureNotInterrupted()
                    onProgress(target.service, attempt)
                    val startedAtNanos = System.nanoTime()
                    val addresses = network?.let { resolveAddresses(target.host, it) }.orEmpty()
                    val dohResolveMs = elapsedMs(startedAtNanos)
                    val address = addresses.getOrNull((attempt - 1) % addresses.size.coerceAtLeast(1))
                    val remainingTimeoutMs =
                        config.hostTimeoutSeconds * 1000L - elapsedMs(startedAtNanos)
                    val result = if (address == null) {
                        TransferTestResult(
                            service = target.service,
                            attempt = attempt,
                            outcome = if (network == null) "network_unavailable" else "dns_failed",
                            bytesReceived = 0,
                            elapsedMs = elapsedMs(startedAtNanos),
                            dohResolveMs = dohResolveMs,
                            socksConnectMs = null,
                            tlsHandshakeMs = null,
                            transferMs = null,
                            httpStatus = null
                        )
                    } else if (remainingTimeoutMs <= 0) {
                        TransferTestResult(
                            service = target.service,
                            attempt = attempt,
                            outcome = "overall_timeout",
                            bytesReceived = 0,
                            elapsedMs = elapsedMs(startedAtNanos),
                            dohResolveMs = dohResolveMs,
                            socksConnectMs = null,
                            tlsHandshakeMs = null,
                            transferMs = null,
                            httpStatus = null
                        )
                    } else {
                        NativeNetworkOperations.runTransferTest(
                            service = target.service,
                            host = target.host,
                            resolvedIp = address.hostAddress.orEmpty().substringBefore('%'),
                            path = target.path(config.payloadKiB * 1024),
                            attempt = attempt,
                            payloadKiB = config.payloadKiB,
                            hostTimeoutMillis = remainingTimeoutMs.toInt(),
                            idleTimeoutSeconds = config.idleTimeoutSeconds
                        ).copy(
                            dohResolveMs = dohResolveMs,
                            elapsedMs = elapsedMs(startedAtNanos)
                        )
                    }
                    result.also {
                        add(it)
                        onResult(it)
                    }
                }
            }
        }
        return TransferTestSummary(config, REPETITIONS, results)
    }

    private fun resolveAddresses(host: String, network: Network): List<InetAddress> {
        val queries = listOfNotNull(
            DnsOverHttpsResolver.createAQuery(host),
            DnsOverHttpsResolver.createAaaaQuery(host)
        )
        return queries.flatMap { query ->
            ensureNotInterrupted()
            val response = DnsOverHttpsResolver.resolve(query, network) { true }
                ?: return@flatMap emptyList()
            ensureNotInterrupted()
            DnsOverHttpsResolver.ipAnswers(response).mapNotNull(::parseNumericAddress)
        }.distinct()
    }

    private fun parseNumericAddress(value: String): InetAddress? {
        if (value.contains(':')) {
            if (!value.matches(Regex("[0-9a-fA-F:]+"))) return null
            return try {
                InetAddress.getByName(value).takeIf { it is Inet6Address }
            } catch (_: IOException) {
                null
            }
        }
        val octets = value.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
            .takeIf { it is Inet4Address }
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000L

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Self-test cancelled")
        }
    }
}
