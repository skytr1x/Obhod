package com.skytr1x.obhod.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionFailureClassifierTest {
    @Test
    fun tcpTimeoutDoesNotClaimIpBlocking() {
        val finding = ConnectionFailureClassifier.classify(
            type = "tcp_connect_failed",
            reason = "timeout"
        )

        assertEquals("tcp_connect_failed", finding?.code)
        assertFalse(finding?.summary.orEmpty().contains("IP-блок"))
        assertTrueContains(finding?.summary.orEmpty(), "нельзя отличить")
    }

    @Test
    fun tlsAlertIsNotTreatedAsProofOfInterference() {
        val finding = ConnectionFailureClassifier.classify(
            type = "tls_unverified",
            reason = "tls_alert"
        )

        assertEquals("tls_alert", finding?.code)
        assertTrueContains(finding?.summary.orEmpty(), "не доказывает")
    }

    @Test
    fun unknownEventsAreNotExportedAsClassifications() {
        assertNull(ConnectionFailureClassifier.classify("future_unknown_event"))
    }

    private fun assertTrueContains(value: String, expected: String) {
        org.junit.Assert.assertTrue(value.contains(expected))
    }
}
