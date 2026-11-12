package com.skytr1x.obhod.diagnostics

internal data class TlsSuccessMetric(
    val strategy: String,
    val connectRttMs: Int,
    val tlsResponseMs: Long
) {
    val fakeTtl: Int?
        get() = strategy.substringAfterLast('@', "").toIntOrNull()
}

internal data class TlsSampleSummary(
    val serverHelloObserved: Int,
    val serverHelloNotObserved: Int
)

internal data class DiagnosticReportData(
    val generatedAt: String,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val networkClass: String,
    val networkValidated: Boolean?,
    val networkMetered: Boolean?,
    val privateDnsActive: Boolean?,
    val ipv6Only: Boolean?,
    val calibrationSignal: String,
    val dnsComparison: String,
    val selectedProfile: String,
    val operatorRegion: String,
    val latestTlsSuccess: TlsSuccessMetric?,
    val tlsSamples: TlsSampleSummary,
    val selfTest: TransferTestSummary?,
    val latestFailure: FailureFinding?,
    val quicSuppressionCount: Int,
    val adaptiveProfileSelected: Boolean = false,
    val adaptiveAttempts: List<String> = emptyList(),
    val logs: List<String> = emptyList()
)

internal data class AdaptiveHostSummary(
    val host: String,
    val steps: List<String>,
    val succeeded: Boolean,
    val finalReason: String?
)

internal data class AdaptiveSummary(
    val hosts: List<AdaptiveHostSummary>,
    val winCounts: List<Pair<String, Int>>,
    val attemptedHosts: Int,
    val succeededHosts: Int,
    val p50Ms: Long?,
    val p95Ms: Long?,
    val possibleIpFilteringHosts: Int
)

internal object DiagnosticReport {
    private val metricPattern = Regex(
        """^\[METRIC] kind=tls_success strategy=([^\s]+) connect_rtt_ms=(\d+) tls_response_ms=(\d+)$"""
    )
    private val selfTestStartPattern = Regex(
        """^\[SELFTEST_RUN] repetitions=(\d+) payload_kib=(\d+) host_timeout_s=(\d+) idle_timeout_s=(\d+)$"""
    )
    private val selfTestResultPattern = Regex(
        """^\[SELFTEST] service=(youtube|discord|4pda|cloudflare) attempt=(\d+) outcome=([a-z0-9_]+) bytes=(\d+) elapsed_ms=(\d+) socks_ms=(\d+|-) doh_ms=(\d+|-) tls_ms=(\d+|-) transfer_ms=(\d+|-) http=(\d+|-)$"""
    )

    fun latestTlsSuccess(messages: List<String>): TlsSuccessMetric? =
        messages.asReversed().firstNotNullOfOrNull(::parseTlsSuccess)

    fun tlsSampleSummary(messages: List<String>): TlsSampleSummary =
        TlsSampleSummary(
            serverHelloObserved = messages.count { parseTlsSuccess(it) != null },
            serverHelloNotObserved = messages.count {
                it.startsWith("[METRIC] kind=tls_failure ")
            }
        )

    fun latestFailure(messages: List<String>): FailureFinding? =
        messages.asReversed().firstNotNullOfOrNull(ConnectionFailureClassifier::fromLogMessage)

    fun quicSuppressionCount(messages: List<String>): Int =
        messages.count { it.startsWith("[UDP-QUIC-BLOCK]") }

    fun adaptiveSummary(messages: List<String>): AdaptiveSummary {
        class MutableHost {
            val steps = mutableListOf<String>()
            var succeeded = false
            var finalReason: String? = null
            var connectIssue = false
        }

        val hosts = LinkedHashMap<String, MutableHost>()
        val winCounts = mutableMapOf<String, Int>()
        val successTimes = mutableListOf<Long>()

        messages.forEach { message ->
            val fields = adaptiveFields(message) ?: return@forEach
            val host = fields["host"]?.takeIf { it.isNotBlank() } ?: return@forEach
            val item = hosts.getOrPut(host) { MutableHost() }
            val profile = fields["profile"]
            val reason = fields["reason"]
            when (fields["outcome"]) {
                "attempt" -> if (profile != null) item.steps += profile
                "success" -> {
                    if (profile != null) {
                        item.steps += "$profile (ok)"
                        winCounts[profile] = (winCounts[profile] ?: 0) + 1
                    }
                    item.succeeded = true
                    item.finalReason = null
                    fields["elapsed_ms"]?.toLongOrNull()?.let(successTimes::add)
                }
                "failed" -> {
                    item.steps += "${profile ?: "?"} (${reason ?: "unknown"})"
                    item.finalReason = reason
                    if (fields["stage"] == "connect" &&
                        (reason == "timeout" || reason == "connection_reset")
                    ) {
                        item.connectIssue = true
                    }
                }
                "chain_exhausted" -> item.finalReason = reason ?: item.finalReason
                "handoff" -> if (profile != null) item.steps += "$profile (handoff)"
                "rate_limited" -> if (profile != null) item.steps += "$profile (rate_limited)"
            }
        }

        val summaries = hosts.map { (host, item) ->
            AdaptiveHostSummary(host, item.steps, item.succeeded, item.finalReason)
        }
        return AdaptiveSummary(
            hosts = summaries,
            winCounts = winCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value },
            attemptedHosts = hosts.values.count { it.succeeded || it.finalReason != null },
            succeededHosts = hosts.values.count { it.succeeded },
            p50Ms = percentile(successTimes, 50),
            p95Ms = percentile(successTimes, 95),
            possibleIpFilteringHosts = hosts.values.count {
                !it.succeeded && it.connectIssue
            }
        )
    }

    private fun adaptiveFields(message: String): Map<String, String>? {
        if (!message.startsWith("[ADAPTIVE] ")) return null
        return message.removePrefix("[ADAPTIVE] ").trim()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) null
                else token.substring(0, separator) to token.substring(separator + 1)
            }
            .toMap()
    }

    private fun percentile(values: List<Long>, percent: Int): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = ((percent / 100.0) * sorted.size).toInt().coerceAtLeast(1)
        return sorted[rank.coerceAtMost(sorted.size) - 1]
    }

    fun latestSelfTest(messages: List<String>): TransferTestSummary? {
        val startIndex = messages.indexOfLast { selfTestStartPattern.matches(it) }
        if (startIndex < 0) return null
        val configMatch = selfTestStartPattern.matchEntire(messages[startIndex]) ?: return null
        val repetitions = configMatch.groupValues[1].toIntOrNull() ?: return null
        if (repetitions != ConnectivitySelfTest.REPETITIONS) return null
        val config = TransferTestConfig(
            payloadKiB = configMatch.groupValues[2].toIntOrNull() ?: return null,
            idleTimeoutSeconds = configMatch.groupValues[4].toIntOrNull() ?: return null
        )
        if (configMatch.groupValues[3].toIntOrNull() != config.hostTimeoutSeconds) return null
        val results = messages.drop(startIndex + 1).mapNotNull(::parseSelfTestResult)
        return TransferTestSummary(config, repetitions, results)
    }

    fun format(data: DiagnosticReportData): String = buildString {
        appendLine("Обход - диагностический отчёт")
        appendLine("Создан: ${data.generatedAt.reportSafe()}")
        appendLine("Версия приложения: ${data.appVersion.reportSafe()}")
        appendLine("Android: ${data.androidVersion.reportSafe()}")
        appendLine("Устройство: ${data.device.reportSafe()}")
        appendLine("Сеть: ${data.networkClass.reportSafe()}")
        appendLine("Сеть подтверждена Android: ${data.networkValidated.asText()}")
        appendLine("Сеть тарифицируется: ${data.networkMetered.asText()}")
        appendLine("Private DNS активен: ${data.privateDnsActive.asText()}")
        appendLine("IPv6-only маршрут: ${data.ipv6Only.asText()}")
        appendLine("Калибровка TLS: ${data.calibrationSignal.reportSafe()}")
        appendLine("Сравнение DNS: ${data.dnsComparison.reportSafe()}")
        appendLine("Интерпретация DNS: ${dnsInterpretation(data.dnsComparison)}")
        appendLine("Выбранный профиль: ${data.selectedProfile.reportSafe()}")
        appendLine(
            "Adaptive Desync Chain: " +
                if (data.adaptiveProfileSelected) "профиль выбран" else "профиль не выбран"
        )
        if (data.adaptiveProfileSelected || data.adaptiveAttempts.isNotEmpty()) {
            appendLine(
                "Причины в журнале тактики — это симптомы, а не диагноз: таймаут или сброс " +
                    "не подтверждают DPI, IP-блокировку или DNS-подмену. IP-блокировки эта тактика не решает."
            )
        }
        if (data.adaptiveAttempts.isNotEmpty()) {
            val adaptive = adaptiveSummary(data.adaptiveAttempts)
            appendLine("Adaptive Desync Chain по доменам:")
            if (adaptive.hosts.isEmpty()) {
                appendLine("  нет данных")
            } else {
                adaptive.hosts.forEach { host ->
                    val outcome = when {
                        host.succeeded -> "успех"
                        host.finalReason != null -> "исчерпано: ${host.finalReason}"
                        else -> "нет итога"
                    }
                    val steps = host.steps.joinToString(" -> ").ifBlank { "нет шагов" }
                    appendLine("  ${host.host.reportSafe()}: ${steps.reportSafe()} => $outcome")
                }
                appendLine(
                    "Победы профилей: " +
                        adaptive.winCounts.joinToString(", ") { "${it.first} ${it.second}" }
                            .ifBlank { "нет" }
                )
                val attempted = adaptive.attemptedHosts
                if (attempted > 0) {
                    val percent = adaptive.succeededHosts * 100 / attempted
                    appendLine(
                        "Success rate по доменам: " +
                            "${adaptive.succeededHosts}/$attempted ($percent%)"
                    )
                } else {
                    appendLine("Success rate по доменам: нет завершённых переборов")
                }
                appendLine(
                    "Время до успеха: p50 ${adaptive.p50Ms?.let { "$it ms" } ?: "нет данных"}, " +
                        "p95 ${adaptive.p95Ms?.let { "$it ms" } ?: "нет данных"}"
                )
                appendLine(
                    "Возможная IP-фильтрация (таймаут или RST на connect): " +
                        "${adaptive.possibleIpFilteringHosts} из ${adaptive.hosts.size} доменов; " +
                        "это не доказательство."
                )
            }
        } else {
            appendLine("Попытки Adaptive Desync Chain: нет данных")
        }
        val operatorRegion = data.operatorRegion
            .filterNot(Char::isISOControl)
            .trim()
            .take(80)
            .ifBlank { "не указан" }
        appendLine("Оператор / регион: $operatorRegion")
        appendLine(
            "Предупреждение: оператор и регион указаны вручную и могут быть неточными. " +
                "Вместе с журналом эти данные помогают идентифицировать вашу сеть, поэтому " +
                "просмотрите файл перед отправкой."
        )
        appendLine(
            "В проверках стратегий ServerHello наблюдался: " +
                "${data.tlsSamples.serverHelloObserved}; не наблюдался: " +
                "${data.tlsSamples.serverHelloNotObserved}"
        )
        val metric = data.latestTlsSuccess
        if (metric == null) {
            appendLine("Последняя TLS-стратегия: нет данных")
        } else {
            appendLine("Последняя TLS-стратегия: ${metric.strategy.reportSafe()}")
            appendLine("Connect RTT: ${metric.connectRttMs} ms")
            appendLine("Время до наблюдения TLS ServerHello: ${metric.tlsResponseMs} ms")
            appendLine("Fake TTL: ${metric.fakeTtl?.toString() ?: "не применялся/неизвестен"}")
        }
        val failure = data.latestFailure
        appendLine("Последняя классификация: ${failure?.code ?: "нет данных"}")
        if (failure != null) appendLine("Интерпретация: ${failure.summary}")
        appendLine("QUIC-пакетов, остановленных профилем: ${data.quicSuppressionCount}")
        val selfTest = data.selfTest
        if (selfTest == null) {
            appendLine("Проверка передачи данных: не запускалась")
        } else {
            appendLine(
                "Самотест: ${selfTest.repetitions} прогона; до ${selfTest.config.payloadKiB} KiB " +
                    "на ответ, до ${selfTest.config.hostTimeoutSeconds} s на попытку, " +
                    "idle ${selfTest.config.idleTimeoutSeconds} s"
            )
            if (selfTest.results.isEmpty()) {
                appendLine("Результаты самотеста: нет")
            } else {
                selfTest.serviceStats().forEach { stats ->
                    val service = serviceLabel(stats.service)
                    val httpResponsePercent = if (stats.attempts > 0) {
                        stats.httpResponsesReceived * 100 / stats.attempts
                    } else {
                        0
                    }
                    val medianRate = stats.medianTransferKiBPerSecond?.let {
                        String.format(java.util.Locale.ROOT, "%.1f KiB/s", it)
                    } ?: "нет данных"
                    appendLine(
                        "$service: HTTP-ответ ${stats.httpResponsesReceived}/${stats.attempts} " +
                            "($httpResponsePercent%), медиана времени ${stats.medianElapsedMs ?: "?"} ms, " +
                            "медиана тела ${stats.medianBytesReceived ?: "?"} bytes, " +
                            "медианная скорость $medianRate"
                    )
                }
                selfTest.results.forEach { result ->
                    appendLine(
                        "${serviceLabel(result.service)} run ${result.attempt}: ${result.outcome}, " +
                            "${result.bytesReceived} bytes, total=${result.elapsedMs} ms, " +
                            "DoH=${result.dohResolveMs ?: "?"} ms, " +
                            "transfer=${result.transferMs ?: "?"} ms, " +
                            "TLS=${result.tlsHandshakeMs ?: "?"} ms, " +
                            "HTTP=${result.httpStatus ?: "?"}"
                    )
                }
            }
        }
        appendLine()
        appendLine("Отчёт создаётся только по запросу и сохраняется в выбранное вами место.")
        appendLine(
            if (data.adaptiveAttempts.isNotEmpty() || data.logs.isNotEmpty()) {
                "При включённой адаптивной тактике или приложенном журнале отчёт может содержать " +
                    "доменные имена, IP-адреса и параметры соединений; содержимое трафика не " +
                    "записывается и отчёт не отправляется автоматически."
            } else {
                "Отчёт не содержит домены, IP-адреса, SSID или содержимое трафика и не отправляется автоматически."
            }
        )
        appendLine("Разные DNS-ответы и неудачный TLS-пробник сами по себе не доказывают подмену DNS или тип блокировки.")
        appendLine("Эта статистика относится только к проверкам стратегий с полным ClientHello, а не к завершённым соединениям приложения.")
        appendLine("Время до ServerHello не является подтверждением завершённого TLS-handshake.")
        appendLine("Пропускная способность измеряется только самотестом для четырёх фиксированных HTTPS-сервисов.")
        if (data.logs.isNotEmpty()) {
            appendLine()
            appendLine("Журнал приложения (${data.logs.size} записей):")
            data.logs.forEach { appendLine(it.logSafe()) }
        }
    }

    private fun parseTlsSuccess(message: String): TlsSuccessMetric? {
        val match = metricPattern.matchEntire(message) ?: return null
        return TlsSuccessMetric(
            strategy = match.groupValues[1],
            connectRttMs = match.groupValues[2].toIntOrNull() ?: return null,
            tlsResponseMs = match.groupValues[3].toLongOrNull() ?: return null
        )
    }

    private fun parseSelfTestResult(message: String): TransferTestResult? {
        val match = selfTestResultPattern.matchEntire(message) ?: return null
        fun optionalLong(group: Int): Long? =
            match.groupValues[group].takeIf { it != "-" }?.toLongOrNull()
        fun optionalInt(group: Int): Int? =
            match.groupValues[group].takeIf { it != "-" }?.toIntOrNull()
        return TransferTestResult(
            service = match.groupValues[1],
            attempt = match.groupValues[2].toIntOrNull() ?: return null,
            outcome = match.groupValues[3],
            bytesReceived = match.groupValues[4].toIntOrNull() ?: return null,
            elapsedMs = match.groupValues[5].toLongOrNull() ?: return null,
            socksConnectMs = optionalLong(6),
            dohResolveMs = optionalLong(7),
            tlsHandshakeMs = optionalLong(8),
            transferMs = optionalLong(9),
            httpStatus = optionalInt(10)
        )
    }

    private fun serviceLabel(service: String): String = when (service) {
        "youtube" -> "YouTube"
        "discord" -> "Discord"
        "4pda" -> "4pda"
        else -> "Cloudflare control"
    }

    private fun dnsInterpretation(comparison: String): String = when (comparison) {
        "match" -> "системный DNS и DoH вернули хотя бы один общий адрес."
        "answers_differ_tls_ok" ->
            "ответы различаются, но TLS на системном IP проходит; возможна штатная CDN-вариация."
        "answers_differ_tls_failed" ->
            "ответы различаются и TLS-проба не прошла; причина неизвестна, подмена не доказана."
        "system_failed" -> "системный DNS не вернул адрес; причина неизвестна."
        "doh_failed" -> "DoH не вернул ответ; системный DNS может быть доступен."
        else -> "недостаточно данных."
    }

    private fun String.reportSafe(): String =
        filterNot(Char::isISOControl).take(160)

    // Log lines keep their detail (up to the AppLog message cap) but lose newlines
    // so the exported file stays one entry per line.
    private fun String.logSafe(): String =
        filterNot(Char::isISOControl).take(2000)

    private fun Boolean?.asText(): String = when (this) {
        true -> "да"
        false -> "нет"
        null -> "неизвестно"
    }
}
