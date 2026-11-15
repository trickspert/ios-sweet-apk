package com.iossweet.sweet16

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetCheckTest {
    private val base = """
        SWEET NET: virtio-net on transport 31: MAC 52:54:00:12:34:56, queues 64/32
        SWEET NET: en0 published in IOKit (IOEthernetInterface)
        SWEET NET: en0 attached (sweetnet=1)
    """.trimIndent()

    @Test
    fun stopsAtConfigd() {
        val st = NetCheck.steps(base, true)
        assertEquals(true, st[0].ok)
        assertEquals(false, st[1].ok)
        assertTrue(NetCheck.verdict(st), NetCheck.verdict(st).contains("configd"))
    }

    @Test
    fun fullSuccess() {
        val log = base + "\n" + """
            SWEET NET: configd opened IONetworkStack (configd's InterfaceNamer)
            SWEET NET: configd turned en0 on
            SWEET NET: iOS sent its first DHCP request (0.0.0.0:68 -> 255.255.255.255:67)
            SWEET NET: first DHCP answer from QEMU (10.0.2.2:67 -> 255.255.255.255:68)
            SWEET NET: configd en0 add address 10.0.2.15
            SWEET NET: configd route add 0.0.0.0 gw 10.0.2.2 ifindex 5 flags 0x803
            SWEET NET: en0 IPv4 address 10.0.2.15 (rx 3, tx 4 frames)
            SWEET NET: iOS sent its first DNS query (10.0.2.15:5353 -> 10.0.2.3:53)
            SWEET NET: first DNS answer (10.0.2.3:53 -> 10.0.2.15:5353)
            SWEET NET: iOS opened its first TCP connection (10.0.2.15:49152 -> 17.253.144.10:443)
            SWEET NET: first TCP connection accepted (17.253.144.10:443 -> 10.0.2.15:49152)
        """.trimIndent()
        val st = NetCheck.steps(log, true)
        assertTrue(st.toString(), st.all { it.ok == true })
        assertTrue(NetCheck.report(log, true, listOf("1.1.1.1"), "ok").contains("Everything works"))
    }

    @Test
    fun fallbackIsReported() {
        val log = base + "\nSWEET NET: iOS did not configure en0 within 300 s (DHCP request never sent): kernel fallback, no DNS server\n" +
            "SWEET NET: en0 10.0.2.15/24 up (err 0)\nSWEET NET: default route via 10.0.2.2 added (err 0)\n" +
            "SWEET NET: en0 IPv4 address 10.0.2.15 (rx 0, tx 0 frames)"
        val r = NetCheck.report(log, true, emptyList(), "-")
        assertTrue(r, r.contains("kernel gave it an address"))
    }
}
