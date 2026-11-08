package com.skytr1x.obhod.network

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsOverHttpsResolverTest {
    @Test
    fun positiveTtlIsCappedInsteadOfDiscarded() {
        assertEquals(300_000L, DnsOverHttpsResolver.cacheTtlMillis(positiveResponse(3600)))
    }

    @Test
    fun zeroTtlIsNotCached() {
        assertNull(DnsOverHttpsResolver.cacheTtlMillis(positiveResponse(0)))
    }

    @Test
    fun negativeAnswerUsesSoaTtlAndMinimum() {
        assertEquals(120_000L, DnsOverHttpsResolver.cacheTtlMillis(nxdomainResponse(600, 120)))
    }

    @Test
    fun negativeAnswerWithoutSoaIsNotCached() {
        val response = dnsHeader(flags = 0x8183, answers = 0, authority = 0)
            .apply { writeQuestion() }
            .toByteArray()
        assertNull(DnsOverHttpsResolver.cacheTtlMillis(response))
    }

    @Test
    fun truncatedDnsResponseIsNotCached() {
        assertNull(DnsOverHttpsResolver.cacheTtlMillis(positiveResponse(60).copyOf(18)))
    }

    @Test
    fun createsIpv6QueryWithExpectedRecordType() {
        assertEquals(
            listOf(0, 28, 0, 1),
            DnsOverHttpsResolver.createAaaaQuery("example.com")
                ?.takeLast(4)
                ?.map { it.toInt() and 0xff }
        )
    }

    @Test
    fun preferredSniModeIsTriedBeforeFallback() {
        assertEquals(listOf(true, false), DnsOverHttpsResolver.sniModeOrder(true))
        assertEquals(listOf(false, true), DnsOverHttpsResolver.sniModeOrder(false))
        assertEquals(listOf(false, true), DnsOverHttpsResolver.sniModeOrder(null))
    }

    @Test
    fun ipAnswersIncludesIpv4AndIpv6Records() {
        val ipv6 = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1
        )
        val response = dnsHeader(flags = 0x8180, answers = 2, authority = 0)
            .apply {
                writeQuestion()
                writePointerToQuestion()
                writeU16(1)
                writeU16(1)
                writeU32(60)
                writeU16(4)
                write(byteArrayOf(192.toByte(), 0, 2, 1))
                writePointerToQuestion()
                writeU16(28)
                writeU16(1)
                writeU32(60)
                writeU16(ipv6.size)
                write(ipv6)
            }
            .toByteArray()
        val ipv6Text = InetAddress.getByAddress(ipv6).hostAddress.orEmpty().lowercase()

        assertEquals(
            setOf("192.0.2.1", ipv6Text),
            DnsOverHttpsResolver.ipAnswers(response)
        )
    }

    private fun positiveResponse(ttl: Long): ByteArray =
        dnsHeader(flags = 0x8180, answers = 1, authority = 0)
            .apply {
                writeQuestion()
                writePointerToQuestion()
                writeU16(1)
                writeU16(1)
                writeU32(ttl)
                writeU16(4)
                write(byteArrayOf(192.toByte(), 0, 2, 1))
            }
            .toByteArray()

    private fun nxdomainResponse(soaTtl: Long, soaMinimum: Long): ByteArray =
        dnsHeader(flags = 0x8183, answers = 0, authority = 1)
            .apply {
                writeQuestion()
                writePointerToQuestion()
                writeU16(6)
                writeU16(1)
                writeU32(soaTtl)
                writeU16(24)
                writePointerToQuestion()
                writePointerToQuestion()
                writeU32(1)
                writeU32(2)
                writeU32(3)
                writeU32(4)
                writeU32(soaMinimum)
            }
            .toByteArray()

    private fun dnsHeader(flags: Int, answers: Int, authority: Int) =
        ByteArrayOutputStream().apply {
            writeU16(0x1234)
            writeU16(flags)
            writeU16(1)
            writeU16(answers)
            writeU16(authority)
            writeU16(0)
        }

    private fun ByteArrayOutputStream.writeQuestion() {
        write(7)
        write("example".toByteArray(Charsets.US_ASCII))
        write(3)
        write("com".toByteArray(Charsets.US_ASCII))
        write(0)
        writeU16(1)
        writeU16(1)
    }

    private fun ByteArrayOutputStream.writePointerToQuestion() {
        write(0xc0)
        write(0x0c)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write(((value ushr 24) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write((value and 0xff).toInt())
    }
}
