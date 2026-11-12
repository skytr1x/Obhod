package com.skytr1x.obhod.strategy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StrategyProfilesTest {
    @Test
    fun adaptiveMergeKeepsBaseTacticAfterChainAndInheritsDirectList() {
        val adaptive = """
            {
              "direct_first": true,
              "defaults": { "tcp": ["direct", "adaptive-p1"] },
              "strategies": [
                { "id": "adaptive-p1", "adaptive": true,
                  "tcp": { "actions": [{ "type": "split", "pos": "sni" }] } }
              ]
            }
        """.trimIndent()
        val base = """
            {
              "direct_ru": false,
              "include": ["blocked.example"],
              "defaults": { "tcp": ["base-split", "direct"] },
              "strategies": [
                { "id": "base-split",
                  "tcp": { "actions": [{ "type": "split", "pos": "sni" }] } }
              ]
            }
        """.trimIndent()

        val merged = JSONObject(StrategyProfiles.mergeAdaptiveProfile(base, adaptive))
        val tcp = merged.getJSONObject("defaults").getJSONArray("tcp")
        assertEquals(
            listOf("direct", "adaptive-p1", "base-split"),
            (0 until tcp.length()).map(tcp::getString)
        )
        val strategies = merged.getJSONArray("strategies")
        assertEquals("adaptive-p1", strategies.getJSONObject(0).getString("id"))
        assertEquals("base-split", strategies.getJSONObject(1).getString("id"))
        assertEquals("blocked.example", merged.getJSONArray("include").getString(0))
        assertFalse(merged.getBoolean("direct_ru"))
    }
}