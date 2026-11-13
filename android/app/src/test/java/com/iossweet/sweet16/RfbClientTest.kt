package com.iossweet.sweet16

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ProtocolFamily
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/**
 * Talks to a live QEMU VNC server (unix socket in SWEET16_VNC_SOCKET), e.g. the
 * sweet-virt stub test of tools/qemu-sweet-virt.sh. Skipped without it.
 * SWEET16_EXPECT_PIXEL=x,y,0xAARRGGBB checks one pixel of the first frame.
 */
class RfbClientTest {
    @Test
    fun framebufferAndInput() {
        val path = System.getenv("SWEET16_VNC_SOCKET")
        assumeTrue("SWEET16_VNC_SOCKET not set", path != null)
        // Unix sockets of JDK 16+ (not in android.jar, so by reflection).
        val ch = SocketChannel::class.java.getMethod("open", ProtocolFamily::class.java)
            .invoke(null, StandardProtocolFamily.valueOf("UNIX")) as SocketChannel
        ch.connect(Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", String::class.java)
            .invoke(null, path) as SocketAddress)
        val c = RfbClient(Channels.newInputStream(ch), Channels.newOutputStream(ch))
        c.connect()
        println("VNC ${c.name}: ${c.width}x${c.height}")
        var updates = 0
        var area = 0L
        val t = Thread {
            c.run(object : RfbClient.Listener {
                override fun onResize(width: Int, height: Int) {}
                override fun onUpdate(x: Int, y: Int, w: Int, h: Int) { updates++; area += w.toLong() * h }
                override fun onClosed(error: Throwable?) { error?.printStackTrace() }
            })
        }
        t.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (area < c.width.toLong() * c.height && System.currentTimeMillis() < deadline) Thread.sleep(50)
        println("updates=$updates area=$area")
        assertEquals(c.width.toLong() * c.height, minOf(area, c.width.toLong() * c.height))
        System.getenv("SWEET16_EXPECT_PIXEL")?.let {
            val (x, y, v) = it.split(',')
            val got = c.pixels[y.toInt() * c.width + x.toInt()]
            assertEquals("pixel $x,$y", java.lang.Long.decode(v).toInt(), got)
        }
        c.pointer(540, 1200, true)
        c.pointer(540, 1300, false)
        c.key(RfbClient.SC_HOME, RfbClient.KS_HOME, true)
        c.key(RfbClient.SC_HOME, RfbClient.KS_HOME, false)
        Thread.sleep(300)
        c.close()
        t.join(2000)
    }
}
