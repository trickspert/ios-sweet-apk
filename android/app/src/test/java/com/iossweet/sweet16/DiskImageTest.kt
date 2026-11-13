package com.iossweet.sweet16

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class DiskImageTest {
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    /** Builds a sparse image: raw, fill, don't-care, crc, raw chunks; checks the expansion. */
    @Test
    fun sparse() {
        val blk = 4096
        val raw1 = Random(1).nextBytes(3 * blk)
        val raw2 = ByteArray(2 * blk).also { it[5] = 7 }
        val out = ByteArrayOutputStream()
        val chunks = listOf(
            Triple(0xCAC1, 3, raw1), Triple(0xCAC2, 2, byteArrayOf(1, 2, 3, 4)),
            Triple(0xCAC3, 4, ByteArray(0)), Triple(0xCAC4, 0, ByteArray(4)), Triple(0xCAC1, 2, raw2))
        val total = 3 + 2 + 4 + 2
        out.write(le(28).putInt(Importer.SPARSE_MAGIC).putShort(1).putShort(0).putShort(28).putShort(12)
            .putInt(blk).putInt(total).putInt(chunks.size).putInt(0).array())
        for ((type, n, data) in chunks) {
            out.write(le(12).putShort(type.toShort()).putShort(0).putInt(n).putInt(12 + data.size).array())
            out.write(data)
        }
        val f = File.createTempFile("sparse", ".img")
        RandomAccessFile(f, "rw").use { DiskImage.writeSparse(ByteArrayInputStream(out.toByteArray()).also { it.skip(0) }, it, AtomicBoolean()) }
        val img = f.readBytes()
        f.delete()
        assertEquals(total * blk, img.size)
        assertArrayEquals(raw1, img.copyOfRange(0, 3 * blk))
        for (i in 3 * blk until 5 * blk) assertEquals(((i % 4) + 1).toByte(), img[i])
        for (i in 5 * blk until 9 * blk) assertEquals(0.toByte(), img[i])
        assertArrayEquals(raw2, img.copyOfRange(9 * blk, 11 * blk))
    }

    @Test
    fun rawKeepsLength() {
        val data = ByteArray((3 shl 20) + 123).also { it[(2 shl 20) + 5] = 9; it[it.size - 1] = 1 }
        val f = File.createTempFile("raw", ".img")
        RandomAccessFile(f, "rw").use { DiskImage.writeRaw(ByteArrayInputStream(data), it, AtomicBoolean()) }
        assertArrayEquals(data, f.readBytes())
        f.delete()
    }
}
