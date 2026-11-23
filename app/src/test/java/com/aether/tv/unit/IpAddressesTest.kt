package com.aether.tv.unit

import com.aether.tv.platform.IpAddresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class IpAddressesTest {
    private fun ips(vararg a: String) = a.map { InetAddress.getByName(it) }

    @Test fun theLanIpv4AddressWins() =
        assertEquals("192.168.1.23", IpAddresses.pick(ips("fe80::1c2:3ff:fe4:5", "2001:db8::23", "192.168.1.23")))

    @Test fun loopbackAndLinkLocalAreNeverShown() {
        assertNull(IpAddresses.pick(ips("127.0.0.1", "::1", "fe80::1", "169.254.10.20")))
        assertNull(IpAddresses.pick(emptyList()))
    }

    @Test fun ipv6OnlyNetworksShowTheGlobalAddress() =
        assertEquals("2001:db8:0:0:0:0:0:23", IpAddresses.pick(ips("fe80::1", "2001:db8::23")))

    @Test fun adbTargets() {
        assertEquals("192.168.1.23:5555", IpAddresses.adbTarget("192.168.1.23"))
        assertEquals("[2001:db8::23]:5555", IpAddresses.adbTarget("2001:db8::23"))
    }
}
