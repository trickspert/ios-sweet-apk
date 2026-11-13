package com.iossweet.sweet16

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Settings: cores, kernel log, keys, boot-args, storage, battery, about. */
class SettingsActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(list) })
        build()
    }

    private fun build() {
        list.removeAllViews()
        title("Settings")

        section("Virtual machine")
        val cpus = Runtime.getRuntime().availableProcessors()
        val coresLabel = text("")
        fun showCores() { coresLabel.text = "CPU cores for iOS: ${store.cores} (this phone has $cpus)" }
        showCores()
        list.addView(SeekBar(this).apply {
            max = 7
            progress = store.cores - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { store.cores = p + 1; showCores() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        note("More cores boot faster but use more battery. Takes effect on the next start.")

        val tbLabel = text("")
        fun showTb() { tbLabel.text = "Translation cache: ${store.tbSizeMb} MB" }
        showTb()
        list.addView(SeekBar(this).apply {
            max = 15
            progress = store.tbSizeMb / 64 - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { store.tbSizeMb = (p + 1) * 64; showTb() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        switch("Show the kernel log on screen while booting", store.showKernelLog) { store.showKernelLog = it }
        switch("Phone volume keys control iOS", store.forwardVolumeKeys) { store.forwardVolumeKeys = it }

        text("Extra kernel boot-args (advanced)")
        list.addView(EditText(this).apply {
            setText(store.extraBootArgs)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(Color.WHITE)
            hint = "e.g. sweethidtest=10"
            setOnFocusChangeListener { _, has -> if (!has) store.extraBootArgs = text.toString() }
        })

        section("Storage")
        text("Kernel: ${store.kernelLabel()} · guest RAM ${store.ramMb} MB")
        if (store.userDeviceTree.isFile) text("Apple DeviceTree: picked (${store.userDeviceTree.length() shr 10} KB, /product used)")
        text(if (store.hasDisk()) "iOS disk: ${store.disk.length() shr 30} GB image, " +
            "${usedBytes(store.disk) shr 20} MB used on this phone" else "iOS disk: not imported")
        button("Delete the iOS disk", danger = true) {
            AlertDialog.Builder(this)
                .setTitle("Delete the iOS 16 disk?")
                .setMessage("Everything inside iOS is lost. Import the image again to use iOS.")
                .setPositiveButton("Delete") { _, _ -> if (!VmState.active) store.disk.delete(); build() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        section("Keep running in the background")
        note("Samsung and other phones stop background apps to save battery. Allow Sweet16 to run unrestricted " +
            "so iOS keeps running when you leave the app.")
        button("Battery: allow background use") { requestBatteryExemption() }

        section("About")
        note("Sweet16 runs the ios-sweet XNU kernel and the iOS 16 userland in QEMU's virt machine on this phone's " +
            "CPU (software emulation; no GPU). It is not affiliated with Apple. iOS is Apple's software and is not " +
            "included: you build the disk image from an IPSW you download yourself (tools/mkiosroot.sh).\n\n" +
            "QEMU (GPL-2.0) and its libraries are Termux builds; versions and sources are listed below. " +
            "The kernel is built from Apple's open-source xnu (APSL) with the ios-sweet patches.")
        val sources = try { assets.open("qemu/SOURCES.txt").bufferedReader().readText() } catch (e: Exception) { "" }
        if (sources.isNotEmpty()) note(sources)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun usedBytes(f: java.io.File): Long = try {
        android.system.Os.stat(f.path).st_blocks * 512
    } catch (e: Exception) {
        f.length()
    }

    private fun title(s: String) = list.addView(TextView(this).apply {
        text = s; textSize = 28f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8))
    })

    private fun section(s: String) = list.addView(TextView(this).apply {
        text = s; textSize = 14f; setTextColor(getColor(R.color.accent_text))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    })

    private fun text(s: String): TextView = TextView(this).apply {
        text = s; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(4))
    }.also { list.addView(it) }

    private fun note(s: String) = list.addView(TextView(this).apply {
        text = s; textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(2), 0, dp(6))
    })

    private fun switch(label: String, on: Boolean, change: (Boolean) -> Unit) = list.addView(Switch(this).apply {
        text = label; textSize = 16f; setTextColor(Color.WHITE); isChecked = on
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, v -> change(v) }
    })

    private fun button(label: String, danger: Boolean = false, action: () -> Unit) = list.addView(Button(this).apply {
        text = label; isAllCaps = false; setTextColor(if (danger) getColor(R.color.danger) else Color.WHITE)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    })

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
