package com.skytr1x.obhod.strategy

import org.json.JSONArray
import org.json.JSONObject

data class StrategyProfileOption(
    val id: String,
    val title: String,
    val description: String
)

object StrategyProfiles {
    const val DEFAULT_ID = "list"
    const val ADAPTIVE_ID = "adaptive-desync-chain"
    const val ADAPTIVE_PREF = "adaptive_chain"

    val options = listOf(
        StrategyProfileOption(
            "list",
            "List",
            "Точечно сплитит запросы. Требует периодического обновления списка домейнов."
        ),
        StrategyProfileOption(
            "flowseal-general-mobile",
            "General",
            "Несколько разрезов TCP-запроса и запасные split/fake/disorder приёмы. QUIC на 443 уводится в TCP, UDP-фейки - для остальных портов."
        ),
        StrategyProfileOption(
            "flowseal-alt-mobile",
            "ALT",
            "fake ClientHello с перебором TTL, затем split/disorder. QUIC на 443 уводится в TCP, UDP-фейки - для остальных портов."
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

    internal fun mergeAdaptiveProfile(baseJson: String, adaptiveJson: String): String {
        val adaptive = JSONObject(adaptiveJson)
        val base = JSONObject(baseJson)

        val defaults = adaptive.getJSONObject("defaults")
        val tcp = mutableListOf<String>()
        fun addTcp(array: JSONArray?) {
            if (array == null) return
            for (index in 0 until array.length()) {
                val id = array.optString(index)
                if (id.isNotBlank() && id !in tcp) tcp += id
            }
        }
        addTcp(defaults.optJSONArray("tcp"))
        addTcp(base.optJSONObject("defaults")?.optJSONArray("tcp"))
        defaults.put("tcp", JSONArray(tcp))

        val strategies = adaptive.getJSONArray("strategies")
        val ids = buildSet {
            for (index in 0 until strategies.length()) {
                strategies.optJSONObject(index)?.optString("id")
                    ?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        base.optJSONArray("strategies")?.let { baseStrategies ->
            for (index in 0 until baseStrategies.length()) {
                val strategy = baseStrategies.optJSONObject(index) ?: continue
                val id = strategy.optString("id")
                if (id.isNotBlank() && id !in ids) strategies.put(strategy)
            }
        }

        base.optJSONArray("include")?.let { adaptive.put("include", it) }
        base.optJSONArray("exclude")?.let { adaptive.put("exclude", it) }
        base.optJSONArray("rules")?.let { adaptive.put("rules", it) }
        if (base.has("direct_ru")) adaptive.put("direct_ru", base.getBoolean("direct_ru"))
        return adaptive.toString()
    }
}
