package com.iossweet.sweet16

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import java.io.RandomAccessFile

/** Live tail of the kernel console (the PL011 copy, -serial file) and QEMU's own output. */
class ConsoleActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.rgb(210, 230, 210))
            setTextIsSelectable(true)
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p * 3, p, p)
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(HorizontalScrollView(this@ConsoleActivity).apply { addView(text) })
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val atEnd = !scroll.canScrollVertically(1)
        val sb = StringBuilder()
        sb.append("── kernel console ──\n").append(tail(store.consoleLog, 256 * 1024))
        sb.append("\n── QEMU ──\n").append(tail(store.qemuLog, 16 * 1024))
        text.text = sb
        if (atEnd) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun tail(f: java.io.File, max: Int): String = try {
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
