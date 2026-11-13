package com.iossweet.sweet16

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Imports the iOS 16 root disk (raw HFS+ image from tools/mkiosroot.sh,
 * optionally gzip-compressed or as an Android sparse image from img2simg)
 * and boot bundles (zip with bundle.json, ram.bin, stub.bin).
 *
 * The disk is written sparse: runs of zero blocks become holes, so a 16 GB
 * image only takes the space iOS actually uses.
 */
class Importer(private val context: Context, private val store: Store) {

    fun interface Progress {
        /** done/total in bytes of the source (total may be -1). */
        fun update(done: Long, total: Long)
    }

    val cancelled = AtomicBoolean(false)

    fun sourceSize(uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    fun sourceName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else ""
        } ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Copy a disk image into the app. Throws on error or cancel. */
    fun importDisk(uri: Uri, progress: Progress) {
        val total = sourceSize(uri)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
        importDisk(input, total, progress)
    }

    fun importDisk(source: InputStream, total: Long, progress: Progress) {
        store.diskDir.mkdirs()
        val tmp = File(store.diskDir, "import.tmp")
        tmp.delete()
        val counting = CountingStream(source, total, progress)
        try {
            var stream: InputStream = BufferedInputStream(counting, 1 shl 20)
            stream.mark(16)
            val head = ByteArray(4)
            val n = readFully(stream, head)
            stream.reset()
            if (n >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) {
                stream = BufferedInputStream(GZIPInputStream(stream, 1 shl 16), 1 shl 20)
                stream.mark(16)
                readFully(stream, head)
                stream.reset()
            }
            val magic = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int
            RandomAccessFile(tmp, "rw").use { out ->
                if (magic == SPARSE_MAGIC) DiskImage.writeSparse(stream, out, cancelled) else DiskImage.writeRaw(stream, out, cancelled)
            }
            checkHfs(tmp)
            store.disk.delete()
            if (!tmp.renameTo(store.disk)) throw IOException("cannot move the image into place")
        } finally {
            tmp.delete()
            source.close()
        }
    }

    /** The image must be an HFS+ (H+) or HFSX (HX) volume: signature at byte 1024. */
    private fun checkHfs(f: File) {
        RandomAccessFile(f, "r").use {
            if (it.length() < 4096) throw IOException("the image is too small")
            it.seek(1024)
            val sig = ByteArray(2)
            it.readFully(sig)
            val s = String(sig, Charsets.US_ASCII)
            if (s != "H+" && s != "HX") {
                throw IOException("not an HFS+ volume (build it with tools/mkiosroot.sh)")
            }
        }
    }

    /** Boot bundle zip: bundle.json, ram.bin, stub.bin (any folder level). */
    fun importBundle(uri: Uri, progress: Progress) {
        val total = sourceSize(uri)
        val tmp = File(store.files, "bundle.import")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
            ZipInputStream(BufferedInputStream(CountingStream(input, total, progress), 1 shl 16)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (!e.isDirectory && name in setOf("bundle.json", "ram.bin", "stub.bin")) {
                        File(tmp, name).outputStream().use { zip.copyTo(it, 1 shl 20) }
                    }
                    checkCancel()
                }
            }
            Bundle.load(tmp) ?: throw IOException("not a boot bundle (needs bundle.json, ram.bin, stub.bin)")
            store.bundleDir.deleteRecursively()
            if (!tmp.renameTo(store.bundleDir)) throw IOException("cannot move the bundle into place")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun checkCancel() {
        if (cancelled.get()) throw InterruptedException("cancelled")
    }

    private class CountingStream(
        private val inner: InputStream, private val total: Long, private val progress: Progress,
    ) : InputStream() {
        private var done = 0L
        private var reported = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it.toLong()) }
        override fun close() = inner.close()
        private fun count(n: Long) {
            done += n
            if (done - reported >= (4 shl 20)) {
                reported = done
                progress.update(done, total)
            }
        }
    }

    companion object {
        const val SPARSE_MAGIC = 0xED26FF3A.toInt()

        fun readFully(input: InputStream, buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        fun le(d: DataInputStream, n: Int): ByteBuffer {
            val b = ByteArray(n)
            try {
                d.readFully(b)
            } catch (e: EOFException) {
                throw IOException("truncated sparse image")
            }
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        }

        /** True if b[0, n) is all zero (8 bytes at a time: a 16 GB image is mostly zeros). */
        fun isZero(b: ByteArray, n: Int): Boolean {
            val longs = ByteBuffer.wrap(b, 0, n - n % 8).asLongBuffer()
            while (longs.hasRemaining()) if (longs.get() != 0L) return false
            for (i in n - n % 8 until n) if (b[i].toInt() != 0) return false
            return true
        }
    }
}
