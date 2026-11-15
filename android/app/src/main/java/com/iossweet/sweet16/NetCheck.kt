package com.iossweet.sweet16

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * "Internet check": how far iOS got with its network, read from the kernel's
 * "SWEET NET:" console lines (bsd/dev/sweet_virtio.c, SweetNet.cpp, and the
 * route/address logging in rtsock.c / in.c), plus a DNS test from this phone
 * to the nameserver QEMU forwards iOS's DNS to. No Android dependencies.
 */
object NetCheck {
    /** ok: true = done, false = failed / not happening, null = not (yet) seen. */
    data class Step(val ok: Boolean?, val text: String, val detail: String = "")

    fun steps(console: String, networkOn: Boolean): List<Step> {
        val lines = console.lineSequence().filter { it.contains("SWEET NET:") }.map { it.substringAfter("SWEET NET:").trim() }.toList()
        fun find(vararg keys: String) = lines.firstOrNull { l -> keys.all { l.contains(it) } }
        val out = mutableListOf<Step>()
        if (!networkOn) {
            out += Step(false, "Internet for iOS is off in Settings")
            return out
        }
        val attached = find("en0 attached")
        out += Step(attached != null || find("virtio-net on transport") != null && find("setup failed") == null,
            "Virtual network card (kernel en0)", attached ?: find("virtio-net") ?: "no virtio-net line: kernel too old or QEMU without -netdev")
        val stack = find("opened IONetworkStack")
        out += Step(stack != null, "iOS network service found the card", stack ?: "configd has not opened IONetworkStack yet")
        val up = find("turned en0 on")
        out += Step(up != null, "iOS switched the card on", up ?: "")
        val dhcpOut = find("first DHCP request")
        out += Step(dhcpOut != null, "iOS asked for an address (DHCP)", dhcpOut ?: "")
        val dhcpIn = find("first DHCP answer")
        out += Step(dhcpIn != null, "QEMU answered (DHCP)", dhcpIn ?: "")
        val addr = lines.lastOrNull { it.contains("en0 IPv4 address") && !it.contains("address 0.0.0.0") }
        val fallback = find("kernel fallback")
        out += Step(addr != null, "iOS has an address", addr ?: "")
        if (fallback != null) out += Step(false, "iOS did not set up the network itself: the kernel gave it an address (no DNS)", fallback)
        val route = lines.firstOrNull { it.contains(" route add 0.0.0.0") || it.contains("default route via 10.0.2.2 added") }
        out += Step(route != null, "Route to the internet", route ?: "")
        val dnsOut = find("first DNS query")
        out += Step(dnsOut != null, "iOS looked up a name (DNS)", dnsOut ?: "")
        val dnsIn = find("first DNS answer")
        out += Step(dnsIn != null, "DNS answered", dnsIn ?: "")
        val tcpOut = find("opened its first TCP")
        out += Step(tcpOut != null, "iOS opened a connection", tcpOut ?: "")
        val tcpIn = find("first TCP connection accepted")
        out += Step(tcpIn != null, "A server answered", tcpIn ?: "")
        return out
    }

    /** One plain-language line about the first missing step. */
    fun verdict(steps: List<Step>): String {
        val firstMissing = steps.firstOrNull { it.ok != true } ?: return "Everything works up to a server answering."
        return when {
            firstMissing.text.startsWith("Internet for iOS is off") -> "Turn on Settings > Internet for iOS and restart iOS."
            firstMissing.text.startsWith("Virtual network card") -> "The kernel has no network card: update the app (new kernel)."
            firstMissing.text.startsWith("iOS network service") -> "iOS is still starting, or configd does not use the card (wait a minute and check again)."
            firstMissing.text.startsWith("iOS asked for an address") -> "iOS found the card but did not ask for an address yet."
            firstMissing.text.startsWith("QEMU answered") -> "QEMU did not answer DHCP: restart iOS."
            firstMissing.text.startsWith("iOS looked up a name") -> "iOS has not tried to look up a name yet: open Safari."
            firstMissing.text.startsWith("DNS answered") -> "Name lookups get no answer: see the DNS test below."
            else -> "Stopped at: ${firstMissing.text}."
        }
    }

    fun report(console: String, networkOn: Boolean, dnsServers: List<String>, dnsTest: String): String = buildString {
        val st = steps(console, networkOn)
        for (s in st) {
            append(if (s.ok == true) "✅ " else if (s.ok == false && s.text.contains("kernel gave")) "⚠️ " else "⬜ ")
            append(s.text).append('\n')
            if (s.detail.isNotEmpty() && s.ok != true) append("     ").append(s.detail.take(120)).append('\n')
        }
        append('\n').append(verdict(st)).append("\n\n")
        append("DNS for iOS goes to: ").append(if (dnsServers.isEmpty()) "(not written yet)" else dnsServers.joinToString(", ")).append('\n')
        append("DNS test from this phone: ").append(dnsTest).append('\n')
    }

    /** Ask [server] for apple.com (A) over UDP; "ok (N ms)" or the error. */
    fun probeDns(server: String, timeoutMs: Int = 3000): String = try {
        val q = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            5, 'a'.code.toByte(), 'p'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 1, 0, 1)
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val t0 = System.currentTimeMillis()
            s.send(DatagramPacket(q, q.size, InetSocketAddress(InetAddress.getByName(server), 53)))
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val answers = ((buf[6].toInt() and 0xff) shl 8) or (buf[7].toInt() and 0xff)
            "ok, $answers answer(s) in ${System.currentTimeMillis() - t0} ms ($server)"
        }
    } catch (e: Exception) {
        "FAILED ($server): ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
    }
}
