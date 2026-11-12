package com.skytr1x.obhod.diagnostics

internal data class FailureFinding(
    val code: String,
    val summary: String
)

internal object ConnectionFailureClassifier {
    fun fromLogMessage(message: String): FailureFinding? {
        if (message.startsWith("[UDP-QUIC-BLOCK]")) {
            return FailureFinding(
                "quic_suppressed_by_profile",
                "QUIC начальный пакет остановлен настройкой профиля; это не доказывает блокировку QUIC сетью."
            )
        }
        if (!message.startsWith("[PROBLEM]")) return null
        val fields = message.substringAfter("[PROBLEM]").trim()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) null
                else token.substring(0, separator) to token.substring(separator + 1)
            }
            .toMap()
        return classify(
            type = fields["type"].orEmpty(),
            stage = fields["stage"].orEmpty(),
            reason = fields["reason"].orEmpty()
        )
    }

    fun classify(type: String, stage: String = "", reason: String = ""): FailureFinding? =
        when (type) {
            "dns_doh_fallback", "dns_worker_queue_full" -> FailureFinding(
                "dns_fallback",
                "DoH не обработал запрос; использован системный DNS. Возможна DNS-подмена, " +
                    "но результат резолвинга неизвестен и это не доказано."
            )
            "dns_resolution_failed" -> FailureFinding(
                "dns_resolution_failed",
                "Не получен DNS-ответ ни через DoH, ни через системный DNS. " +
                    "Возможна DNS-подмена или блокировка DNS, но это не доказано."
            )
            "tcp_connect_failed" -> FailureFinding(
                "tcp_connect_failed",
                when (reason) {
                    "timeout" ->
                        "Истёк таймаут TCP-подключения. Возможна IP-фильтрация, но это не доказано; " +
                            "также возможны проблемы маршрута или недоступность сервера, и по таймауту " +
                            "их нельзя отличить."
                    "connection_reset" ->
                        "TCP-подключение сброшено (RST). Возможна IP-фильтрация или вмешательство " +
                            "провайдера, но RST также приходит от перегруженного или закрывшего порт " +
                            "сервера, поэтому это не доказано."
                    "dns" ->
                        "DNS-запрос не дал адресов. Возможна DNS-подмена или блокировка DNS, " +
                            "но причину по одному сбою установить нельзя."
                    else -> "TCP-подключение не установлено; причина сбоя не определена."
                }
            )
            "tls_unverified" -> when {
                stage == "reconnect" -> FailureFinding(
                    "tcp_reconnect_failed",
                    "Не удалось повторно установить TCP-соединение; возможна IP-фильтрация " +
                        "или проблема маршрута, но причина не доказана."
                )
                stage == "strategy_send" || reason == "send_failed" -> FailureFinding(
                    "tls_client_hello_send_failed",
                    "Не удалось отправить TLS ClientHello; причина сбоя не определена."
                )
                reason == "tls_alert" -> FailureFinding(
                    "tls_alert",
                    "Получен TLS alert. Он сам по себе не доказывает вмешательство сети."
                )
                reason == "timeout" -> FailureFinding(
                    "tls_response_timeout",
                    "TCP установлен, но TLS ServerHello не подтверждён до таймаута; возможная причина не определена."
                )
                reason == "connection_reset" -> FailureFinding(
                    "tls_connection_reset",
                    "Соединение сброшено (RST) до подтверждённого TLS-ответа. Возможен RST-сброс " +
                        "DPI по SNI, но RST также приходит от сервера или посредника; это не доказано."
                )
                reason == "dns" -> FailureFinding(
                    "tls_dns_failed",
                    "Повторное подключение не получило DNS-адресов. Возможна DNS-подмена или " +
                        "блокировка DNS, но это не доказано."
                )
                reason == "server_closed" -> FailureFinding(
                    "tls_peer_closed",
                    "Удалённая сторона закрыла TCP до подтверждённого TLS-ответа."
                )
                reason == "truncated_response" -> FailureFinding(
                    "tls_response_truncated",
                    "TLS-ответ оборвался до полного заголовка; причина сбоя не определена."
                )
                reason == "unexpected_response" || reason == "not_tls" ->
                    FailureFinding(
                        "non_tls_response",
                        "Получен ответ, не подтверждающий TLS; это может быть неверный endpoint или посредник, но причина неизвестна."
                    )
                else -> FailureFinding(
                    "tls_unverified",
                    "TLS-ответ не подтверждён; имеющихся данных недостаточно, чтобы определить причину."
                )
            }
            "tcp_direct_replay_failed" -> FailureFinding(
                "tcp_direct_replay_failed",
                "Прямая повторная попытка не удалась; причина исходного сбоя остаётся неизвестной."
            )
            else -> null
        }
}
