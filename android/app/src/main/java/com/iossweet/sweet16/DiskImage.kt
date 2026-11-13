package com.iossweet.sweet16

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Disk image decoders (raw and Android sparse), written sparse to [RandomAccessFile]. */
object DiskImage {
    fun writeRaw(input: InputStream, out: RandomAccessFile, cancelled: AtomicBoolean) {
        val buf = ByteArray(1 shl 20)
        var pos = 0L
        while (true) {
            checkCancel(cancelled)
            val n = readFully(input, buf)
            if (n <= 0) break
            if (!isZero(buf, n)) {
                out.seek(pos)
                out.write(buf, 0, n)
            }
            pos += n
            if (n < buf.size) break
        }
        out.setLength(pos)
    }

    /** Android sparse image (system/core/libsparse/sparse_format.h). */
    fun writeSparse(input: InputStream, out: RandomAccessFile, cancelled: AtomicBoolean) {
        val d = DataInputStream(input)
        val hdr = le(d, 28)
        hdr.int // magic
        val major = hdr.short.toInt()
        hdr.short // minor
        val fileHdrSz = hdr.short.toInt() and 0xffff
        val chunkHdrSz = hdr.short.toInt() and 0xffff
        val blkSz = hdr.int.toLong()
        val totalBlks = hdr.int.toLong() and 0xffffffffL
        val totalChunks = hdr.int.toLong() and 0xffffffffL
        if (major != 1 || fileHdrSz < 28 || chunkHdrSz < 12 || blkSz <= 0 || blkSz % 4 != 0L) {
            throw IOException("unsupported sparse image")
        }
        d.skipBytes(fileHdrSz - 28)
        out.setLength(totalBlks * blkSz)
        val buf = ByteArray(1 shl 20)
        var blk = 0L
        for (c in 0 until totalChunks) {
            checkCancel(cancelled)
            val ch = le(d, 12)
            val type = ch.short.toInt() and 0xffff
            ch.short
            val chunkBlks = ch.int.toLong() and 0xffffffffL
            val totalSz = ch.int.toLong() and 0xffffffffL
            d.skipBytes(chunkHdrSz - 12)
            val bytes = chunkBlks * blkSz
            when (type) {
                0xCAC1 -> { // raw
                    var left = bytes
                    var pos = blk * blkSz
                    while (left > 0) {
                        checkCancel(cancelled)
                        val n = minOf(left, buf.size.toLong()).toInt()
                        d.readFully(buf, 0, n)
                        if (!isZero(buf, n)) {
                            out.seek(pos)
                            out.write(buf, 0, n)
                        }
                        pos += n
                        left -= n
                    }
                }
                0xCAC2 -> { // fill
                    val pattern = ByteArray(4)
                    d.readFully(pattern)
                    if (!isZero(pattern, 4)) {
                        for (i in buf.indices) buf[i] = pattern[i and 3]
                        var left = bytes
                        var pos = blk * blkSz
                        while (left > 0) {
                            val n = minOf(left, buf.size.toLong()).toInt()
                            out.seek(pos)
                            out.write(buf, 0, n)
                            pos += n
                            left -= n
                        }
                    }
                }
                0xCAC3 -> {} // don't care
                0xCAC4 -> d.skipBytes(4) // crc32
                else -> throw IOException("bad sparse chunk type 0x%x".format(type))
            }
            if (type != 0xCAC1 && type != 0xCAC2 && type != 0xCAC4 && totalSz != chunkHdrSz.toLong()) {
                throw IOException("bad sparse chunk size")
            }
            blk += chunkBlks
        }
    }

    private fun checkCancel(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw InterruptedException("cancelled")
    }

    private fun readFully(input: InputStream, buf: ByteArray) = Importer.readFully(input, buf)
    private fun isZero(b: ByteArray, n: Int) = Importer.isZero(b, n)
    private fun le(d: DataInputStream, n: Int) = Importer.le(d, n)
}
