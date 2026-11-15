package com.iossweet.sweet16

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The app's direct-display reader on a running QEMU (tools/qemu-sweet-virt.sh
 * with DIRECT=1 and boot-arg sweetfb_scanout=1): SWEET16_QEMU_PID, writes
 * the frame (packed BGRA) and "W H KIND SEQ" to SWEET16_FRAME_OUT(.txt).
 * Skipped without SWEET16_QEMU_PID.
 */
class LiveDirectFrameTest {
    @Test
    fun readsTheLiveFrame() {
        val pid = System.getenv("SWEET16_QEMU_PID")?.toIntOrNull()
        assumeTrue("SWEET16_QEMU_PID not set", pid != null)
        val f = DirectFrame.findQemuRam(pid!!)
        assertNotNull("no memfd in /proc/$pid/fd", f)
        GuestRam(f!!).use { ram ->
            val d = DirectFrame(ram)
            val t0 = System.currentTimeMillis()
            while (!d.ready() && System.currentTimeMillis() - t0 < 60_000) Thread.sleep(200)
            val buf = ByteBuffer.allocateDirect(1152 * 2560 * 4)
            val t1 = System.nanoTime()
            val frame = d.fetch(buf)
            val ms = (System.nanoTime() - t1) / 1e6
            assertNotNull("no frame", frame)
            val out = File(System.getenv("SWEET16_FRAME_OUT") ?: "build/frame.bgra")
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            out.writeBytes(bytes)
            File(out.path + ".txt").writeText("${frame!!.width} ${frame.height} ${frame.kind} ${frame.seq} %.2f\n".format(ms))
            println("frame $frame read in %.2f ms".format(ms))
        }
    }
}
