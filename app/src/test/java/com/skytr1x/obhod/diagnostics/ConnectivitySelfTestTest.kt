package com.skytr1x.obhod.diagnostics

import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ConnectivitySelfTestTest {
    @Test
    fun encodesIpv4AddressInSocksConnectRequest() {
        val address = InetAddress.getByAddress(
            byteArrayOf(192.toByte(), 0, 2, 1)
        )

        assertArrayEquals(
            byteArrayOf(5, 1, 0, 1, 192.toByte(), 0, 2, 1, 1, 0xbb.toByte()),
            ConnectivitySelfTest.socksConnectRequest(address, 443)
        )
    }

    @Test
    fun encodesIpv6AddressInSocksConnectRequest() {
        val address = InetAddress.getByAddress(
            byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 1
            )
        )

        assertArrayEquals(
            byteArrayOf(5, 1, 0, 4) + address.address +
                byteArrayOf(1, 0xbb.toByte()),
            ConnectivitySelfTest.socksConnectRequest(address, 443)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSocksPort() {
        ConnectivitySelfTest.socksConnectRequest(
            InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)),
            0
        )
    }
}
