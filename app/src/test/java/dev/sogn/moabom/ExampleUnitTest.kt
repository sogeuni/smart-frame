package dev.sogn.moabom

import org.junit.Test

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun privateIpv4AddressesAreAcceptedForHttp() {
        assertTrue(SmartFramePreferences.isPrivateIpv4Address("10.0.0.1"))
        assertTrue(SmartFramePreferences.isPrivateIpv4Address("172.16.0.1"))
        assertTrue(SmartFramePreferences.isPrivateIpv4Address("172.31.255.255"))
        assertTrue(SmartFramePreferences.isPrivateIpv4Address("192.168.1.10"))
    }

    @Test
    fun publicAndMalformedIpv4AddressesAreRejectedForHttp() {
        assertFalse(SmartFramePreferences.isPrivateIpv4Address("172.32.0.1"))
        assertFalse(SmartFramePreferences.isPrivateIpv4Address("8.8.8.8"))
        assertFalse(SmartFramePreferences.isPrivateIpv4Address("192.168.1.256"))
        assertFalse(SmartFramePreferences.isPrivateIpv4Address("smart-frame.local"))
    }
}
