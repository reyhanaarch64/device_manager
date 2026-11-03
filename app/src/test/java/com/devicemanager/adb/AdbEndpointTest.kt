package com.devicemanager.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbEndpointTest {
    @Test fun parsesIpv4Endpoint() {
        assertEquals(AdbEndpoint("192.168.1.8", 37123), AdbEndpoint.parse("192.168.1.8:37123"))
    }

    @Test fun parsesBracketedIpv6Endpoint() {
        assertEquals(AdbEndpoint("fe80::1234", 37123), AdbEndpoint.parse("[fe80::1234]:37123"))
    }

    @Test fun rejectsInvalidPortsAndUnbracketedIpv6() {
        assertThrows(IllegalArgumentException::class.java) { AdbEndpoint.parse("192.168.1.8:0") }
        assertThrows(IllegalArgumentException::class.java) { AdbEndpoint.parse("192.168.1.8:65536") }
        assertThrows(IllegalArgumentException::class.java) { AdbEndpoint.parse("fe80::1234:5555") }
    }
}
