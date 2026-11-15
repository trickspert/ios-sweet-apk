package com.iossweet.sweet16

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Direct display: reads iOS's frames straight from the guest's RAM.
 *
 * QEMU runs with its RAM in a memfd (-object memory-backend-memfd); the app
 * opens it through /proc/<qemu pid>/fd and maps it read-only. The kernel
 * (SweetFramebuffer.cpp, boot-arg sweetfb_scanout=1) keeps a descriptor in
 * the reserved framebuffer region saying where the current frame is: the
 * panel, or iOS's own full-screen surface as a list of physical pieces.
 * No Android dependencies (tested on the JVM with a plain file).
 */
class GuestRam(file: File, private val ramBase: Long = RAM_BASE) : Closeable {
    /** Read-write when allowed: the app's "I am reading" counter goes to the descriptor. */
    private val raf = try { RandomAccessFile(file, "rw") } catch (e: Exception) { RandomAccessFile(file, "r") }
    val writable: Boolean = try { raf.channel.write(ByteBuffer.allocate(0), 0); true } catch (e: Exception) { false }
    private val channel = raf.channel
    private val size = channel.size()
    private val windows = arrayOfNulls<MappedByteBuffer>(((size + WINDOW - 1) / WINDOW).toInt())

    private fun window(i: Int): MappedByteBuffer =
        windows[i] ?: channel.map(FileChannel.MapMode.READ_ONLY, i * WINDOW, minOf(WINDOW, size - i * WINDOW))
            .also { it.order(ByteOrder.LITTLE_ENDIAN); windows[i] = it }

    /** Copy [len] bytes of guest physical memory at [pa] into dst (at its position). */
    fun read(pa: Long, dst: ByteBuffer, len: Int) {
        var off = pa - ramBase
        var left = len
        require(off >= 0 && off + len <= size) { "guest address 0x%x outside RAM".format(pa) }
        while (left > 0) {
            val w = (off / WINDOW).toInt()
            val inWin = (off % WINDOW).toInt()
            val n = minOf(left.toLong(), WINDOW - inWin).toInt()
            val src = window(w).duplicate()
            src.position(inWin).limit(inWin + n)
            dst.put(src)
            off += n
            left -= n
        }
    }

    fun u32(pa: Long): Int {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        read(pa, b, 4)
        return b.getInt(0)
    }

    /** Write a 32-bit value into guest RAM; false if not possible. */
    fun write32(pa: Long, v: Int): Boolean = try {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, v)
        channel.write(b, pa - ramBase) == 4
    } catch (e: Exception) {
        false
    }

    override fun close() {
        channel.close()
        raf.close()
    }

    companion object {
        const val RAM_BASE = 0x40000000L
        private const val WINDOW = 1L shl 30
    }
}

class DirectFrame(private val ram: GuestRam, private val descPa: Long = DESCRIPTOR_PA) {
    data class Frame(val seq: Int, val width: Int, val height: Int, val kind: Int)

    private val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
    private var runs = ByteBuffer.allocate(16 * 16).order(ByteOrder.LITTLE_ENDIAN)

    /** True once the kernel has written the descriptor. */
    fun ready(): Boolean = try { ram.u32(descPa) == MAGIC && ram.u32(descPa + 4) == 1 } catch (e: Exception) { false }

    /**
     * Tell the kernel the app reads frames from RAM (it then stops copying them
     * into the panel; it copies again ~1 s after the counter stops changing).
     */
    private var ack = 0
    fun acknowledge(): Boolean = ram.write32(descPa + 0x38, ++ack)

    /** The descriptor's sequence number (cheap; changes with every new frame). */
    fun seq(): Int = ram.u32(descPa + 8)

    /**
     * Copy the current frame as tightly packed 32-bit pixels (the guest's
     * BGRA byte order) into [dst] (cleared first; grown when too small, the
     * buffer in use is returned in the frame's place via [out]).
     */
    fun fetch(dst: ByteBuffer): Frame? {
        for (attempt in 0 until 3) {
            header.clear()
            ram.read(descPa, header, 64)
            if (header.getInt(0) != MAGIC || header.getInt(4) != 1) return null
            val seq = header.getInt(8)
            if (seq and 1 != 0) continue
            val w = header.getInt(12)
            val h = header.getInt(16)
            val bpr = header.getInt(20)
            val n = header.getInt(28)
            val offset = header.getLong(32)
            val total = header.getLong(40)
            val kind = header.getInt(48)
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || bpr < w * 4 || n <= 0 || n > MAX_RUNS ||
                offset < 0 || offset + bpr.toLong() * (h - 1) + w * 4L > total) return null
            if (runs.capacity() < n * 16) runs = ByteBuffer.allocate(n * 16).order(ByteOrder.LITTLE_ENDIAN)
            runs.clear()
            ram.read(descPa + 64, runs, n * 16)
            if (dst.capacity() < w * h * 4) return null
            dst.clear()
            // Rows of w*4 bytes at offset + y*bpr in the pieces' concatenation.
            var run = 0
            var runStart = 0L
            for (y in 0 until h) {
                var pos = offset + y.toLong() * bpr
                var left = w * 4
                while (left > 0) {
                    while (run < n && pos >= runStart + runs.getLong(run * 16 + 8)) {
                        runStart += runs.getLong(run * 16 + 8)
                        run++
                    }
                    if (run >= n) return null
                    val pa = runs.getLong(run * 16) + (pos - runStart)
                    val k = minOf(left.toLong(), runStart + runs.getLong(run * 16 + 8) - pos).toInt()
                    ram.read(pa, dst, k)
                    pos += k
                    left -= k
                }
            }
            if (ram.u32(descPa + 8) != seq) continue
            dst.flip()
            return Frame(seq, w, h, kind)
        }
        return null
    }

    companion object {
        /** VirtAdt.FB + 0x1600000 (SWEET_SCANOUT_OFFSET in SweetFramebuffer.cpp). */
        const val DESCRIPTOR_PA = 0x9c000000L + 0x1600000L
        const val MAGIC = 0x44363153      // 'S16D'
        const val MAX_RUNS = 4096

        /** QEMU's RAM memfd, found through /proc/<pid>/fd (same app, same user). */
        fun findQemuRam(pid: Int): File? {
            val fds = File("/proc/$pid/fd").listFiles() ?: return null
            return fds.firstOrNull { f ->
                try { f.canonicalPath.contains("memfd:memory-backend-memfd") || java.nio.file.Files.readSymbolicLink(f.toPath()).toString().contains("memory-backend-memfd") } catch (e: Exception) { false }
            }
        }
    }
}
