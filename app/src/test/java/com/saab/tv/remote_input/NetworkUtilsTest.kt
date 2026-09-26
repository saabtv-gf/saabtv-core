package com.saab.tv.remote_input

import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {
    @Test
    fun `wifi and ethernet rank ahead of vpn interfaces`() {
        val wifi = NetworkUtils.interfacePriority("wlan0", isSiteLocal = true, isVirtual = false)
        val ethernet = NetworkUtils.interfacePriority("eth0", isSiteLocal = true, isVirtual = false)
        val vpn = NetworkUtils.interfacePriority("tun0", isSiteLocal = true, isVirtual = false)

        assertTrue(wifi > vpn)
        assertTrue(ethernet > vpn)
    }

    @Test
    fun `site local address ranks ahead of public address on same interface`() {
        val local = NetworkUtils.interfacePriority("wlan0", isSiteLocal = true, isVirtual = false)
        val public = NetworkUtils.interfacePriority("wlan0", isSiteLocal = false, isVirtual = false)

        assertTrue(local > public)
    }
}
