package com.iossweet.sweet16

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/**
 * The phone screen of the virtual machine: full screen, touch goes to the
 * guest. Back = the iPhone's Home; a small handle at the top opens the
 * controls (Home, lock, volume, kernel log, stop).
 */
class VmActivity : Activity() {
    private lateinit var store: Store
    private lateinit var view: VmView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var client: RfbClient? = null
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var closing = false
    private var connector: Thread? = null
    private val stateListener = { onState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        view = VmView(this)
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(status, FrameLayout.LayoutParams(MATCH, MATCH))

        val handle = View(this).apply {
            setBackgroundResource(R.drawable.bg_handle)
            alpha = 0.55f
            contentDescription = "Controls"
            setOnClickListener { toggleControls() }
        }
        root.addView(handle, FrameLayout.LayoutParams(dp(64), dp(8)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })
        // A taller invisible touch target around the handle.
        root.addView(View(this).apply { setOnClickListener { toggleControls() } },
            FrameLayout.LayoutParams(dp(120), dp(28)).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        fun row(vararg items: Pair<String, () -> Unit>) {
            val r = LinearLayout(this)
            for ((label, action) in items) {
                r.addView(Button(this).apply {
                    text = label
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_pill)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            controls.addView(r)
        }
        row("Home" to { tap(RfbClient.SC_HOME, RfbClient.KS_HOME) },
            "Lock" to { tap(RfbClient.SC_POWER, RfbClient.KS_POWER) })
        row("Volume −" to { tap(RfbClient.SC_VOLUME_DOWN, RfbClient.KS_VOLUME_DOWN) },
            "Volume +" to { tap(RfbClient.SC_VOLUME_UP, RfbClient.KS_VOLUME_UP) })
        row("Kernel log" to { startActivity(Intent(this, ConsoleActivity::class.java)) },
            "Stop iOS" to { VmService.stop(this); finish() })
        root.addView(controls, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            setMargins(dp(12), dp(28), dp(12), 0)
        })
        setContentView(root)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        VmState.observe(stateListener)
        onState()
        connect()
    }

    override fun onPause() {
        VmState.remove(stateListener)
        disconnect()
        super.onPause()
    }

    private fun onState() {
        when (VmState.phase) {
            VmState.Phase.STARTING -> showStatus("Starting iOS 16…")
            VmState.Phase.RUNNING -> if (client == null) showStatus("Connecting to the screen…")
            VmState.Phase.FAILED -> showStatus("iOS 16 stopped with an error:\n\n" + VmState.message)
            VmState.Phase.STOPPED -> { showStatus("iOS 16 is not running."); disconnect() }
        }
    }

    private fun showStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun connect() {
        if (connector?.isAlive == true || client != null) return
        closing = false
        connector = Thread({
            while (!closing && VmState.active) {
                if (store.vncSocket.exists()) {
                    try {
                        val s = LocalSocket()
                        s.connect(LocalSocketAddress(store.vncSocket.path, LocalSocketAddress.Namespace.FILESYSTEM))
                        socket = s
                        val c = RfbClient(s.inputStream, s.outputStream)
                        c.connect()
                        client = c
                        view.client = c
                        main.post { showStatus(null) }
                        c.run(object : RfbClient.Listener {
                            override fun onResize(width: Int, height: Int) = view.resize(width, height)
                            override fun onUpdate(x: Int, y: Int, w: Int, h: Int) = view.damage(x, y, w, h)
                            override fun onClosed(error: Throwable?) {}
                        }, minFrameMs = 33)
                    } catch (e: Exception) {
                        // QEMU not ready yet, or it went away
                    }
                    view.client = null
                    client = null
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                    if (!closing) main.post { onState() }
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }, "vnc").apply { start() }
    }

    private fun disconnect() {
        closing = true
        client?.close()
        try { socket?.close() } catch (_: Exception) {}
        connector?.interrupt()
        connector = null
    }

    private fun tap(scancode: Int, keysym: Int) {
        val c = client ?: return
        view.input.execute {
            try {
                c.key(scancode, keysym, true)
                Thread.sleep(80)
                c.key(scancode, keysym, false)
            } catch (_: Exception) {}
        }
    }

    private fun key(scancode: Int, keysym: Int, down: Boolean) {
        val c = client ?: return
        view.input.execute { try { c.key(scancode, keysym, down) } catch (_: Exception) {} }
    }

    private fun toggleControls() {
        controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun onBack() {
        if (controls.visibility == View.VISIBLE) {
            controls.visibility = View.GONE
        } else if (client != null) {
            tap(RfbClient.SC_HOME, RfbClient.KS_HOME)
        } else {
            finish()
        }
    }

    @Deprecated("Back for API < 33")
    override fun onBackPressed() = onBack()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (store.forwardVolumeKeys && client != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { if (event.repeatCount == 0) key(RfbClient.SC_VOLUME_UP, RfbClient.KS_VOLUME_UP, true); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { if (event.repeatCount == 0) key(RfbClient.SC_VOLUME_DOWN, RfbClient.KS_VOLUME_DOWN, true); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (store.forwardVolumeKeys && client != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { key(RfbClient.SC_VOLUME_UP, RfbClient.KS_VOLUME_UP, false); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { key(RfbClient.SC_VOLUME_DOWN, RfbClient.KS_VOLUME_DOWN, false); return true }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        @Suppress("unused") private val MONO = Typeface.MONOSPACE
    }
}
