package com.iossweet.sweet16

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RFB 3.8 client for QEMU's built-in VNC server (no Android
 * dependencies, so it can be unit-tested on the JVM).
 *
 * Raw encoding only (the server is on the same phone, over a unix socket),
 * plus DesktopSize and QEMU's extended key events, which carry the key's
 * scancode so QEMU needs no keysym translation for power/volume/home.
 *
 * The framebuffer is kept as ARGB ints in [pixels] (guarded by itself);
 * [Listener.onUpdate] reports each changed rectangle.
 */
class RfbClient(input: InputStream, private val output: OutputStream) {
    interface Listener {
        fun onResize(width: Int, height: Int)
        fun onUpdate(x: Int, y: Int, w: Int, h: Int)
        fun onClosed(error: Throwable?)
    }

    private val inp = DataInputStream(BufferedInputStream(input, 1 shl 16))
    var width = 0
        private set
    var height = 0
        private set
    @Volatile var pixels = IntArray(0)
        private set
    var name = ""
        private set
    @Volatile private var running = true
    private var row = ByteArray(0)
    private var extKeys = false

    /** Handshake; returns after ServerInit. */
    fun connect() {
        val version = ByteArray(12)
        inp.readFully(version)
        val v = String(version, Charsets.US_ASCII)
        if (!v.startsWith("RFB ")) throw IOException("not a VNC server: $v")
        send("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        val n = inp.readUnsignedByte()
        if (n == 0) throw IOException("VNC refused: " + readString())
        val types = ByteArray(n).also { inp.readFully(it) }
        if (1.toByte() !in types) throw IOException("VNC server wants a password (security types ${types.toList()})")
        send(byteArrayOf(1))
        if (inp.readInt() != 0) throw IOException("VNC security failed: " + readString())
        send(byteArrayOf(1)) // ClientInit: shared
        width = inp.readUnsignedShort()
        height = inp.readUnsignedShort()
        inp.readFully(ByteArray(16)) // server pixel format
        name = readString()
        allocate(width, height)

        // 32 bpp, depth 24, little endian, true colour, R<<16 G<<8 B: an int per pixel.
        val pf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        pf.put(0).put(0).put(0).put(0)
        pf.put(32).put(24).put(0).put(1)
        pf.putShort(255).putShort(255).putShort(255)
        pf.put(16).put(8).put(0)
        pf.put(0).put(0).put(0)
        send(pf.array())
        val enc = intArrayOf(ENC_RAW, ENC_DESKTOP_SIZE, ENC_QEMU_EXT_KEY)
        val se = ByteBuffer.allocate(4 + 4 * enc.size).order(ByteOrder.BIG_ENDIAN)
        se.put(2).put(0).putShort(enc.size.toShort())
        enc.forEach { se.putInt(it) }
        send(se.array())
        requestUpdate(false)
    }

    private fun allocate(w: Int, h: Int) {
        pixels = IntArray(w * h)
        row = ByteArray(w * 4)
    }

    /** Message loop; blocks until the connection ends. */
    fun run(listener: Listener, minFrameMs: Long = 16) {
        listener.onResize(width, height)
        var error: Throwable? = null
        try {
            var last = 0L
            while (running) {
                when (val type = inp.readUnsignedByte()) {
                    0 -> {
                        readUpdate(listener)
                        val wait = minFrameMs - (System.currentTimeMillis() - last)
                        if (wait > 0) Thread.sleep(wait)
                        last = System.currentTimeMillis()
                        requestUpdate(true)
                    }
                    1 -> { // colour map
                        inp.readByte(); inp.readUnsignedShort()
                        val n = inp.readUnsignedShort()
                        inp.readFully(ByteArray(n * 6))
                    }
                    2 -> {} // bell
                    3 -> { // cut text
                        inp.readFully(ByteArray(3))
                        inp.readFully(ByteArray(inp.readInt()))
                    }
                    else -> throw IOException("unknown VNC message $type")
                }
            }
        } catch (t: Throwable) {
            if (running) error = t
        } finally {
            listener.onClosed(error)
        }
    }

    private fun readUpdate(listener: Listener) {
        inp.readByte()
        val n = inp.readUnsignedShort()
        for (i in 0 until n) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            when (val enc = inp.readInt()) {
                ENC_RAW -> {
                    if (x + w > width || y + h > height) throw IOException("rectangle outside the screen")
                    val px = pixels
                    val bytes = w * 4
                    for (r in 0 until h) {
                        inp.readFully(row, 0, bytes)
                        var o = (y + r) * width + x
                        var b = 0
                        synchronized(px) {
                            while (b < bytes) {
                                px[o++] = (0xFF shl 24) or
                                    ((row[b + 2].toInt() and 0xFF) shl 16) or
                                    ((row[b + 1].toInt() and 0xFF) shl 8) or
                                    (row[b].toInt() and 0xFF)
                                b += 4
                            }
                        }
                    }
                    listener.onUpdate(x, y, w, h)
                }
                ENC_DESKTOP_SIZE -> {
                    width = w
                    height = h
                    allocate(w, h)
                    listener.onResize(w, h)
                }
                ENC_QEMU_EXT_KEY -> extKeys = true
                else -> throw IOException("unexpected VNC encoding $enc")
            }
        }
    }

    fun requestUpdate(incremental: Boolean) {
        val b = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        b.put(3).put(if (incremental) 1 else 0)
        b.putShort(0).putShort(0).putShort(width.toShort()).putShort(height.toShort())
        send(b.array())
    }

    /** Absolute pointer (QEMU routes it to the virtio tablet = the touch screen). */
    fun pointer(x: Int, y: Int, down: Boolean) {
        val b = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        b.put(5).put(if (down) 1 else 0)
        b.putShort(x.coerceIn(0, maxOf(0, width - 1)).toShort())
        b.putShort(y.coerceIn(0, maxOf(0, height - 1)).toShort())
        send(b.array())
    }

    /**
     * Key with its PC scancode (QEMU numbering: extended keys = 0x80 | code),
     * e.g. [SC_POWER]. Falls back to the keysym when the server did not
     * acknowledge extended key events.
     */
    fun key(scancode: Int, keysym: Int, down: Boolean) {
        val b = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        b.put(255.toByte()).put(0).putShort(if (down) 1 else 0).putInt(keysym).putInt(scancode)
        if (extKeys) {
            send(b.array())
        } else {
            val k = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            k.put(4).put(if (down) 1 else 0).putShort(0).putInt(keysym)
            send(k.array())
        }
    }

    fun close() {
        running = false
        try { output.close() } catch (_: IOException) {}
        try { inp.close() } catch (_: IOException) {}
    }

    private fun readString(): String {
        val n = inp.readInt()
        val b = ByteArray(n.coerceIn(0, 1 shl 16))
        inp.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun send(b: ByteArray) {
        synchronized(output) {
            output.write(b)
            output.flush()
        }
    }

    companion object {
        const val ENC_RAW = 0
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_QEMU_EXT_KEY = -258

        // QEMU key numbers (ui/input-keymap, "number" = XT set 1, 0x80 | code for E0 keys)
        // -> virtio-keyboard -> Linux KEY_* in the guest (bsd/dev/sweet_virtio.c).
        const val SC_POWER = 0xde        // E0 5E  KEY_POWER
        const val SC_VOLUME_UP = 0xb0    // E0 30  KEY_VOLUMEUP
        const val SC_VOLUME_DOWN = 0xae  // E0 2E  KEY_VOLUMEDOWN
        const val SC_HOME = 0xc7         // E0 47  KEY_HOME
        const val KS_POWER = 0x1008FF2A  // XF86PowerOff
        const val KS_VOLUME_UP = 0x1008FF13
        const val KS_VOLUME_DOWN = 0x1008FF11
        const val KS_HOME = 0xFF50       // Home
    }
}
