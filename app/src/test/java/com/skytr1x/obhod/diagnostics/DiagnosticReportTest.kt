package com.skytr1x.obhod.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                operatorRegion = "",
                latestTlsSuccess = null,
                tlsSamples = TlsSampleSummary(0, 0),
                selfTest = null,
                latestFailure = null,
                quicSuppressionCount = 0
            )
        )

        assertTrue(report.contains("не отправляется автоматически"))
        assertTrue(report.contains("не является подтверждением завершённого TLS-handshake"))
    }

    @Test
    fun includesOptionalOperatorRegionWithoutControlCharacters() {
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
                operatorRegion = "Оператор\nМосква",
                latestTlsSuccess = null,
                tlsSamples = TlsSampleSummary(0, 0),
                selfTest = null,
                latestFailure = null,
                quicSuppressionCount = 0
            )
        )

        assertTrue(report.contains("Оператор / регион: ОператорМосква"))
    }

    @Test
    fun parsesResultsFromTheLatestTransferTestRun() {
        val summary = DiagnosticReport.latestSelfTest(
            listOf(
                "[SELFTEST_RUN] payload_kib=100 host_timeout_s=15 idle_timeout_s=4",
                "[SELFTEST] service=youtube outcome=success bytes=102400 elapsed_ms=1200 socks_ms=50 tls_ms=300 transfer_ms=800 http=206",
                "[SELFTEST_RUN] payload_kib=200 host_timeout_s=15 idle_timeout_s=8",
                "[SELFTEST] service=discord outcome=idle_timeout bytes=32768 elapsed_ms=4500 socks_ms=60 tls_ms=400 transfer_ms=3000 http=200"
            )
        )

        assertEquals(200, summary?.config?.payloadKiB)
        assertEquals(15, summary?.config?.hostTimeoutSeconds)
        assertEquals(8, summary?.config?.idleTimeoutSeconds)
        assertEquals(1, summary?.results?.size)
        assertEquals("discord", summary?.results?.single()?.service)
        assertEquals("idle_timeout", summary?.results?.single()?.outcome)
        assertEquals(32768, summary?.results?.single()?.bytesReceived)
    }

    @Test
    fun reportIncludesTransferMeasurementWithoutRawAddresses() {
        val summary = DiagnosticReport.latestSelfTest(
            listOf(
                "[SELFTEST_RUN] payload_kib=150 host_timeout_s=15 idle_timeout_s=4",
                "[SELFTEST] service=cloudflare outcome=success bytes=153600 elapsed_ms=1100 socks_ms=40 tls_ms=250 transfer_ms=750 http=200"
            )
        )
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
                operatorRegion = "",
                latestTlsSuccess = null,
                tlsSamples = TlsSampleSummary(0, 0),
                selfTest = summary,
                latestFailure = null,
                quicSuppressionCount = 0
            )
        )

        assertTrue(report.contains("Cloudflare control: success, 153600 bytes"))
        assertTrue(report.contains("HTTP=200"))
        assertFalse(report.contains("speed.cloudflare.com"))
        assertFalse(report.contains("https://"))
    }

    @Test
    fun ignoresSelfTestLogWithNonstandardHostTimeout() {
        assertNull(
            DiagnosticReport.latestSelfTest(
                listOf("[SELFTEST_RUN] payload_kib=100 host_timeout_s=5 idle_timeout_s=4")
            )
        )
    }
}
