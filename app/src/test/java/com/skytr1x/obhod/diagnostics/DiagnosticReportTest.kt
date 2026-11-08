package com.skytr1x.obhod.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun parsesOnlySanitizedTlsMetricAndFakeTtl() {
        val metric = DiagnosticReport.latestTlsSuccess(
            listOf(
                "[METRIC] kind=tls_success strategy=fake-split@4 connect_rtt_ms=83 tls_response_ms=412"
            )
        )

        assertEquals("fake-split@4", metric?.strategy)
        assertEquals(83, metric?.connectRttMs)
        assertEquals(412L, metric?.tlsResponseMs)
        assertEquals(4, metric?.fakeTtl)
    }

    @Test
    fun excludesHostAndAddressFromFailureClassification() {
        val finding = DiagnosticReport.latestFailure(
            listOf(
                "[PROBLEM] type=tls_unverified host=private.example port=443 remote_ip=192.0.2.10 reason=timeout"
            )
        )

        assertEquals("tls_response_timeout", finding?.code)
        assertFalse(finding?.summary.orEmpty().contains("private.example"))
        assertFalse(finding?.summary.orEmpty().contains("192.0.2.10"))
    }

    @Test
    fun summarizesOnlyStructuredTlsProbeResults() {
        val summary = DiagnosticReport.tlsSampleSummary(
            listOf(
                "[METRIC] kind=tls_success strategy=split-sni connect_rtt_ms=83 tls_response_ms=412",
                "[METRIC] kind=tls_failure reason=timeout",
                "[OK] private.example:443 via fake-tls@4"
            )
        )

        assertEquals(1, summary.serverHelloObserved)
        assertEquals(1, summary.serverHelloNotObserved)
    }

    @Test
    fun reportStatesItsMeasurementLimitations() {
        val report = DiagnosticReport.format(
            DiagnosticReportData(
                generatedAt = "2026-11-08T19:00:00Z",
                appVersion = "1",
                androidVersion = "Android (API 35)",
                device = "Test device",
                networkClass = "Wi-Fi",
                networkValidated = true,
                networkMetered = false,
                privateDnsActive = false,
                ipv6Only = false,
                calibrationSignal = "unknown",
                dnsComparison = "unknown",
                selectedProfile = "list",
                latestTlsSuccess = null,
                tlsSamples = TlsSampleSummary(0, 0),
                latestFailure = null,
                quicSuppressionCount = 0
            )
        )

        assertTrue(report.contains("не отправляется автоматически"))
        assertTrue(report.contains("не является подтверждением завершённого TLS-handshake"))
    }
}
