package com.skytr1x.obhod

data class StrategyProfileOption(
    val id: String,
    val title: String,
    val description: String
)

object StrategyProfiles {
    const val DEFAULT_ID = "flowseal-general-mobile"

    val options = listOf(
        StrategyProfileOption(
            "flowseal-general-mobile",
            "General",
            "Несколько разрезов TCP-запроса и запасные split/fake/disorder приёмы. UDP 443: QUIC fake ×6."
        ),
        StrategyProfileOption(
            "flowseal-alt-mobile",
            "ALT",
            "fake ClientHello с перебором TTL, затем split/disorder. UDP 443: QUIC fake ×6."
        ),
        StrategyProfileOption(
            "simple-fake-mobile",
            "Simple Fake",
            "Минимальный вариант. fake ClientHello с двумя шаблонами, после них прямая попытка."
        ),
        StrategyProfileOption(
            "split-sni-mobile",
            "Split SNI",
            "Сначала разрез перед SNI, затем разрез после первого байта. Без fake-пакетов TCP."
        )
    )

    fun label(id: String): String = options.firstOrNull { it.id == id }?.title ?: options.first().title
}
