package com.iossweet.sweet16

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** A fake guest RAM file with a descriptor and a frame split into scattered pieces. */
class DirectFrameTest {
    private val base = 0x40000000L
    private val desc = 0x41000000L

    private fun ram(): File {
        val f = Files.createTempFile("ram", ".bin").toFile()
        RandomAccessFile(f, "rw").use { it.setLength(32L shl 20) }
        return f
    }

    private fun put(f: File, pa: Long, b: ByteArray) = RandomAccessFile(f, "rw").use { it.seek(pa - base); it.write(b) }

    @Test
    fun readsAFrameFromScatteredPieces() {
        val f = ram()
        val w = 37
        val h = 23
        val bpr = 160          // padded rows
        val offset = 100L
        val total = offset + bpr.toLong() * h
        // The surface memory (total bytes) split into 3 pieces at unrelated addresses.
        val surface = ByteArray(total.toInt()) { (it * 31 + 7).toByte() }
        val pieces = listOf(0x40100000L to 1000L, 0x40900000L to 2000L, 0x40300000L to (total - 3000))
        var o = 0
        for ((pa, len) in pieces) {
            put(f, pa, surface.copyOfRange(o, o + len.toInt()))
            o += len.toInt()
        }
        val hdr = ByteBuffer.allocate(64 + 16 * pieces.size).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(DirectFrame.MAGIC).putInt(1).putInt(42).putInt(w).putInt(h).putInt(bpr)
            .putInt(0x42475241).putInt(pieces.size).putLong(offset).putLong(total).putInt(2)
        hdr.position(64)
        for ((pa, len) in pieces) hdr.putLong(pa).putLong(len)
        put(f, desc, hdr.array())

        GuestRam(f, base).use { r ->
            val d = DirectFrame(r, desc)
            assertEquals(true, d.ready())
            assertEquals(42, d.seq())
            val out = ByteBuffer.allocateDirect(w * h * 4)
            val fr = d.fetch(out)
            assertNotNull(fr)
            assertEquals(DirectFrame.Frame(42, w, h, 2), fr)
            assertEquals(w * h * 4, out.remaining())
            for (y in 0 until h) for (x in 0 until w * 4) {
                assertEquals(surface[(offset + y * bpr + x).toInt()], out.get(y * w * 4 + x))
            }
        }
        f.delete()
    }

    @Test
    fun rejectsAFrameBeingWritten() {
        val f = ram()
        val hdr = ByteBuffer.allocate(64 + 16).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(DirectFrame.MAGIC).putInt(1).putInt(7).putInt(4).putInt(4).putInt(16)
            .putInt(0x42475241).putInt(1).putLong(0).putLong(64).putInt(1)
        hdr.position(64)
        hdr.putLong(0x40200000L).putLong(64)
        put(f, desc, hdr.array())
        GuestRam(f, base).use { r -> assertNull(DirectFrame(r, desc).fetch(ByteBuffer.allocateDirect(64))) }
        f.delete()
    }
}
