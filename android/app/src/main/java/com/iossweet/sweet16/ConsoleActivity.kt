package com.iossweet.sweet16

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * The kernel's serial console (QEMU PL011 -> chardev socket + console.log),
 * as a terminal: everything shown is read from QEMU's output, and the input
 * line goes to the guest's /dev/console. Also: CPU registers through QMP,
 * QEMU's own logs, and the exact launch command.
 */
class ConsoleActivity : Activity() {
    private lateinit var store: Store
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var serial: LocalSocket? = null
    @Volatile private var serialOut: OutputStream? = null
    @Volatile private var open = false
    private var view = View.CONSOLE

    private enum class View { CONSOLE, QEMU }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val d = resources.displayMetrics.density
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.rgb(210, 230, 210))
            setTextIsSelectable(true)
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(HorizontalScrollView(this@ConsoleActivity).apply { addView(text) })
        }
        input = EditText(this).apply {
            hint = "type a line for the guest console (e.g. help, uname, hw)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false
            }
        }
        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f; setOnClickListener { action() }
        }
        val bar = LinearLayout(this).apply {
            addView(btn("Console") { view = View.CONSOLE; refresh() })
            addView(btn("QEMU log") { view = View.QEMU; refresh() })
            addView(btn("CPU regs") { cpuRegisters() })
            addView(btn("Boot details") { bootDetails() })
        }
        val inputRow = LinearLayout(this).apply {
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("Send") { send() })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(0, (24 * d).toInt(), 0, 0)
            addView(HorizontalScrollView(this@ConsoleActivity).apply { addView(bar) })
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(inputRow)
        })
    }

    override fun onResume() {
        super.onResume()
        open = true
        main.post(tick)
        io.execute { connectSerial() }
    }

    override fun onPause() {
        open = false
        main.removeCallbacks(tick)
        io.execute { closeSerial() }
        super.onPause()
    }

    /** Keep a connection to QEMU's serial socket (it accepts one client) and drain it. */
    private fun connectSerial() {
        if (serial != null || !open || !store.serialSocket.exists()) return
        try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(store.serialSocket.path, LocalSocketAddress.Namespace.FILESYSTEM))
            serial = s
            serialOut = s.outputStream
            Thread({
                val buf = ByteArray(4096)
                try {
                    // Output is also in console.log (shown from there); read so QEMU never blocks.
                    while (s.inputStream.read(buf) >= 0) { }
                } catch (_: Exception) {}
                io.execute { if (serial === s) closeSerial() }
            }, "serial-drain").start()
        } catch (_: Exception) {
            serial = null
        }
    }

    private fun closeSerial() {
        try { serial?.close() } catch (_: Exception) {}
        serial = null
        serialOut = null
    }

    private fun send() {
        val line = input.text.toString()
        input.setText("")
        io.execute {
            connectSerial()
            try {
                serialOut?.apply { write((line + "\r").toByteArray()); flush() }
            } catch (_: Exception) {
                closeSerial()
            }
        }
    }

    private fun refresh() {
        io.execute { if (serial == null) connectSerial() }
        val atEnd = !scroll.canScrollVertically(1)
        text.text = when (view) {
            View.CONSOLE -> "── kernel console (PL011, ${store.consoleLog.name}) ──\n" + tail(store.consoleLog, 384 * 1024)
            View.QEMU -> "── QEMU output (${store.qemuLog.name}) ──\n" + tail(store.qemuLog, 32 * 1024) +
                "\n── QEMU -d log (${store.qemuDebugLog.name}, Developer boot option) ──\n" + tail(store.qemuDebugLog, 64 * 1024)
        }
        if (atEnd) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** QMP `info registers` from the live QEMU: where the guest CPUs actually are. */
    private fun cpuRegisters() {
        io.execute {
            val reply = VmService.qmp(store, """{"execute":"human-monitor-command","arguments":{"command-line":"info registers -a"}}""")
            val regs = reply?.let {
                try { org.json.JSONObject(it).optString("return", it) } catch (e: Exception) { it }
            } ?: "QEMU is not running (no QMP socket)."
            main.post { show("CPU registers (QMP)", regs) }
        }
    }

    private fun bootDetails() {
        val t = if (store.lastLaunch.isFile) store.lastLaunch.readText() else "Nothing launched yet."
        show("Boot details (last launch)", t)
    }

    private fun show(title: String, body: String) {
        val tv = TextView(this).apply {
            text = body; typeface = Typeface.MONOSPACE; textSize = 10f; setTextIsSelectable(true)
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(HorizontalScrollView(this@ConsoleActivity).apply { addView(tv) }) })
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, body))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun tail(f: File, max: Int): String = try {
        RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val start = maxOf(0L, len - max)
            r.seek(start)
            val b = ByteArray((len - start).toInt())
            r.readFully(b)
            String(b, Charsets.UTF_8).replace("\r", "")
        }
    } catch (e: Exception) {
        "(nothing yet)\n"
    }
}
