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
    val quicSuppressionCount: Int
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
        val operatorRegion = data.operatorRegion
            .filterNot(Char::isISOControl)
            .trim()
            .take(80)
            .ifBlank { "не указан" }
        appendLine("Оператор / регион: $operatorRegion")
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
        appendLine("Он не содержит домены, IP-адреса, SSID или содержимое трафика и не отправляется автоматически.")
        appendLine("Разные DNS-ответы и неудачный TLS-пробник сами по себе не доказывают подмену DNS или тип блокировки.")
        appendLine("Эта статистика относится только к проверкам стратегий с полным ClientHello, а не к завершённым соединениям приложения.")
        appendLine("Время до ServerHello не является подтверждением завершённого TLS-handshake.")
        appendLine("Пропускная способность измеряется только самотестом для четырёх фиксированных HTTPS-сервисов.")
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

    private fun Boolean?.asText(): String = when (this) {
        true -> "да"
        false -> "нет"
        null -> "неизвестно"
    }
}
