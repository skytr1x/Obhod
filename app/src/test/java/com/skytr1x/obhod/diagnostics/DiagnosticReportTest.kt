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
                "[SELFTEST_RUN] repetitions=3 payload_kib=100 host_timeout_s=15 idle_timeout_s=4",
                "[SELFTEST] service=youtube attempt=1 outcome=complete bytes=102400 elapsed_ms=1200 socks_ms=50 doh_ms=20 tls_ms=300 transfer_ms=800 http=206",
                "[SELFTEST_RUN] repetitions=3 payload_kib=200 host_timeout_s=15 idle_timeout_s=8",
                "[SELFTEST] service=discord attempt=1 outcome=reached_http_non_2xx bytes=1200 elapsed_ms=4000 socks_ms=60 doh_ms=30 tls_ms=400 transfer_ms=3000 http=403",
                "[SELFTEST] service=discord attempt=2 outcome=idle_timeout bytes=32768 elapsed_ms=5000 socks_ms=70 doh_ms=20 tls_ms=450 transfer_ms=3500 http=-",
                "[SELFTEST] service=discord attempt=3 outcome=reached_http_non_2xx bytes=2400 elapsed_ms=6000 socks_ms=65 doh_ms=25 tls_ms=420 transfer_ms=4000 http=429"
            )
        )

        assertEquals(200, summary?.config?.payloadKiB)
        assertEquals(15, summary?.config?.hostTimeoutSeconds)
        assertEquals(8, summary?.config?.idleTimeoutSeconds)
        assertEquals(3, summary?.repetitions)
        assertEquals(3, summary?.results?.size)
        assertEquals("discord", summary?.results?.get(1)?.service)
        assertEquals("idle_timeout", summary?.results?.get(1)?.outcome)
        assertEquals(32768, summary?.results?.get(1)?.bytesReceived)
        val discord = summary?.serviceStats()?.single()
        assertEquals(2, discord?.httpResponsesReceived)
        assertEquals(3, discord?.attempts)
        assertEquals(5000L, discord?.medianElapsedMs)
        assertEquals(2400, discord?.medianBytesReceived)
    }

    @Test
    fun reportIncludesTransferMeasurementWithoutRawAddresses() {
        val summary = DiagnosticReport.latestSelfTest(
            listOf(
                "[SELFTEST_RUN] repetitions=3 payload_kib=150 host_timeout_s=15 idle_timeout_s=4",
                "[SELFTEST] service=cloudflare attempt=1 outcome=complete bytes=153600 elapsed_ms=1100 socks_ms=40 doh_ms=20 tls_ms=250 transfer_ms=750 http=200",
                "[SELFTEST] service=cloudflare attempt=2 outcome=complete bytes=153600 elapsed_ms=1200 socks_ms=42 doh_ms=18 tls_ms=260 transfer_ms=760 http=200",
                "[SELFTEST] service=cloudflare attempt=3 outcome=complete bytes=153600 elapsed_ms=1300 socks_ms=44 doh_ms=17 tls_ms=270 transfer_ms=770 http=200"
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

        assertTrue(report.contains("Cloudflare control: HTTP-ответ 3/3 (100%)"))
        assertTrue(report.contains("Cloudflare control run 1: complete, 153600 bytes"))
        assertTrue(report.contains("HTTP=200"))
        assertFalse(report.contains("speed.cloudflare.com"))
        assertFalse(report.contains("https://"))
    }

    @Test
    fun ignoresSelfTestLogWithNonstandardHostTimeout() {
        assertNull(
            DiagnosticReport.latestSelfTest(
                listOf("[SELFTEST_RUN] repetitions=3 payload_kib=100 host_timeout_s=5 idle_timeout_s=4")
            )
        )
    }
}
