package com.iossweet.sweet16

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView

/** Import progress survives the activity (one import at a time). */
object ImportJob {
    @Volatile var running = false
    @Volatile var what = ""
    @Volatile var done = 0L
    @Volatile var total = -1L
    @Volatile var error: String? = null
    @Volatile var importer: Importer? = null
}

/**
 * Launcher screen: the app header, a status banner (what to do next) and
 * one big card, iOS 16, with its state chip and a menu.
 */
class MainActivity : Activity() {
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bannerIcon: ImageView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerText: TextView
    private lateinit var bannerProgress: ProgressBar
    private lateinit var bannerButton: Button
    private lateinit var chipText: TextView
    private lateinit var specs: TextView
    private lateinit var art: Ios16ArtView
    @Volatile private var preparing = true
    private val stateListener = { render() }
    private val poll = object : Runnable {
        override fun run() {
            render()
            if (ImportJob.running || preparing) main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        val title = SpannableString(getString(R.string.title_first) + getString(R.string.title_second))
        title.setSpan(ForegroundColorSpan(getColor(R.color.accent_text)), getString(R.string.title_first).length,
            title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.app_title).text = title
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        bannerIcon = findViewById(R.id.banner_icon)
        bannerTitle = findViewById(R.id.banner_title)
        bannerText = findViewById(R.id.banner_text)
        bannerProgress = findViewById(R.id.banner_progress)
        bannerButton = findViewById(R.id.banner_button)
        chipText = findViewById(R.id.os_chip_text)
        specs = findViewById(R.id.os_specs)
        art = findViewById(R.id.os_art)
        findViewById<View>(R.id.os_card).setOnClickListener { onCard() }
        findViewById<ImageButton>(R.id.os_more).setOnClickListener { showMenu(it) }

        // QEMU's keymap out of the APK (once).
        Thread({
            try { store.installQemuData() } catch (_: Exception) {}
            preparing = false
            main.post { render() }
        }, "prepare").start()
    }

    override fun onResume() {
        super.onResume()
        VmState.observe(stateListener)
        main.post(poll)
        render()
    }

    override fun onPause() {
        VmState.remove(stateListener)
        main.removeCallbacks(poll)
        super.onPause()
    }

    private enum class Ui { PREPARING, IMPORTING, NO_BUNDLE, NO_DISK, READY, STARTING, RUNNING, FAILED }

    private fun ui(): Ui = when {
        ImportJob.running -> Ui.IMPORTING
        VmState.phase == VmState.Phase.STARTING -> Ui.STARTING
        VmState.phase == VmState.Phase.RUNNING -> Ui.RUNNING
        preparing -> Ui.PREPARING
        !store.hasKernel() -> Ui.NO_BUNDLE
        !store.hasDisk() -> Ui.NO_DISK
        VmState.phase == VmState.Phase.FAILED -> Ui.FAILED
        else -> Ui.READY
    }

    private fun render() {
        val state = ui()
        bannerProgress.visibility = View.GONE
        bannerButton.visibility = View.VISIBLE
        art.dimmed = state in setOf(Ui.NO_BUNDLE, Ui.NO_DISK, Ui.PREPARING, Ui.IMPORTING)
        when (state) {
            Ui.PREPARING -> banner(R.drawable.ic_download, "Getting ready", "Unpacking QEMU's files…", null)
            Ui.IMPORTING -> {
                val pct = if (ImportJob.total > 0) (ImportJob.done * 1000 / ImportJob.total).toInt() else -1
                banner(R.drawable.ic_download, "Importing ${ImportJob.what}",
                    "${ImportJob.done shr 20} MB" + (if (ImportJob.total > 0) " of ${ImportJob.total shr 20} MB" else "") +
                        ". Keep the app open.", "Cancel")
                bannerProgress.visibility = View.VISIBLE
                bannerProgress.isIndeterminate = pct < 0
                if (pct >= 0) bannerProgress.progress = pct
            }
            Ui.NO_BUNDLE -> banner(R.drawable.ic_warning, "Kernel missing",
                "This build has no kernel inside. Pick your kernel in Developer boot.", "Open")
            Ui.NO_DISK -> banner(R.drawable.ic_download, "Add your iOS 16 disk",
                "Import the root image you built from the iPhone X IPSW (.img, .img.gz or .simg).", "Import")
            Ui.READY -> banner(R.drawable.ic_check, "iOS 16 is ready",
                (ImportJob.error?.let { "Last import failed: $it" }) ?: "Boots in a few minutes. Tap Start or the card.",
                "Start")
            Ui.STARTING -> banner(R.drawable.ic_play, "iOS 16 is starting", "Tap the card to watch it boot.", "Open")
            Ui.RUNNING -> banner(R.drawable.ic_play, "iOS 16 is running", "Tap the card to open its screen.", "Stop")
            Ui.FAILED -> banner(R.drawable.ic_warning, "iOS 16 stopped", VmState.message, "Start")
        }
        chipText.text = when (state) {
            Ui.PREPARING, Ui.IMPORTING -> "Installing"
            Ui.NO_BUNDLE, Ui.NO_DISK -> "Not installed"
            Ui.READY, Ui.FAILED -> "Optimised"
            Ui.STARTING -> "Booting"
            Ui.RUNNING -> "Running"
        }
        val disk = if (store.hasDisk()) " · ${(store.disk.length() + (1L shl 29)) shr 30} GB disk" else ""
        specs.text = "${store.cores} cores · ${store.ramMb / 1024} GB RAM$disk"
    }

    private fun banner(icon: Int, title: String, text: String, button: String?) {
        bannerIcon.setImageResource(icon)
        bannerTitle.text = title
        bannerText.text = text
        if (button == null) {
            bannerButton.visibility = View.GONE
        } else {
            bannerButton.text = button
            bannerButton.setOnClickListener { onBannerButton() }
        }
    }

    private fun onBannerButton() {
        when (ui()) {
            Ui.IMPORTING -> ImportJob.importer?.cancelled?.set(true)
            Ui.NO_BUNDLE -> startActivity(Intent(this, DevBootActivity::class.java))
            Ui.NO_DISK -> pick(REQ_DISK)
            Ui.READY, Ui.FAILED -> startVm()
            Ui.STARTING -> openVm()
            Ui.RUNNING -> confirmStop()
            Ui.PREPARING -> {}
        }
    }

    private fun onCard() {
        when (ui()) {
            Ui.READY, Ui.FAILED -> startVm()
            Ui.STARTING, Ui.RUNNING -> openVm()
            Ui.NO_DISK -> pick(REQ_DISK)
            Ui.NO_BUNDLE -> startActivity(Intent(this, DevBootActivity::class.java))
            else -> {}
        }
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val state = ui()
        if (state == Ui.RUNNING || state == Ui.STARTING) {
            m.menu.add(0, 1, 0, "Open")
            m.menu.add(0, 2, 1, "Stop")
        } else if (state == Ui.READY || state == Ui.FAILED) {
            m.menu.add(0, 3, 0, "Start")
        }
        m.menu.add(0, 4, 2, "Serial console & CPU state")
        m.menu.add(0, 8, 3, "Boot details (QEMU command)")
        if (!VmState.active && !ImportJob.running) {
            m.menu.add(0, 6, 4, "Developer boot (verbose, your files)…")
            m.menu.add(0, 5, 5, "Import iOS disk…")
        }
        m.menu.add(0, 7, 6, "Settings")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openVm()
                2 -> confirmStop()
                3 -> startVm()
                4 -> startActivity(Intent(this, ConsoleActivity::class.java))
                5 -> pick(REQ_DISK)
                6 -> startActivity(Intent(this, DevBootActivity::class.java))
                7 -> startActivity(Intent(this, SettingsActivity::class.java))
                8 -> bootDetails()
            }
            true
        }
        m.show()
    }

    private fun startVm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return // continues in onRequestPermissionsResult
        }
        ImportJob.error = null
        VmService.start(this, Launch.IOS)
        openVm()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_NOTIFY) {
            // The VM runs either way; the notification only makes it visible.
            VmService.start(this)
            openVm()
        }
    }

    private fun openVm() = startActivity(Intent(this, VmActivity::class.java))

    /** The exact launch: from the running VM if there is one, else built now from the current settings. */
    private fun bootDetails() {
        Thread({
            val text = if (VmState.active && store.lastLaunch.isFile) store.lastLaunch.readText()
            else try { Launch.prepare(store, Launch.IOS).describe(store) } catch (e: Exception) { "Cannot build the iOS 16 boot: ${e.message}" }
            main.post {
                val tv = TextView(this).apply {
                    this.text = text; typeface = android.graphics.Typeface.MONOSPACE; textSize = 10f
                    setTextIsSelectable(true); setPadding(32, 16, 32, 16)
                }
                AlertDialog.Builder(this).setTitle("Boot details")
                    .setView(android.widget.ScrollView(this).apply { addView(android.widget.HorizontalScrollView(this@MainActivity).apply { addView(tv) }) })
                    .setPositiveButton("Close", null).show()
            }
        }, "details").start()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Stop iOS 16?")
            .setMessage("Like pulling the battery: apps lose unsaved work.")
            .setPositiveButton("Stop") { _, _ -> VmService.stop(this) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pick(req: Int) {
        if (VmState.active || ImportJob.running) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, req)
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK || ImportJob.running) return
        val importer = Importer(applicationContext, store)
        ImportJob.importer = importer
        if (requestCode != REQ_DISK) return
        ImportJob.what = "iOS 16 disk"
        ImportJob.done = 0
        ImportJob.total = importer.sourceSize(uri)
        ImportJob.error = null
        ImportJob.running = true
        Thread({
            try {
                val progress = Importer.Progress { d, t -> ImportJob.done = d; ImportJob.total = t }
                importer.importDisk(uri, progress)
            } catch (e: InterruptedException) {
                ImportJob.error = "cancelled"
            } catch (e: Throwable) {
                ImportJob.error = e.message ?: e.toString()
            } finally {
                ImportJob.running = false
                ImportJob.importer = null
                main.post {
                    render()
                    ImportJob.error?.let { err ->
                        if (!isFinishing) AlertDialog.Builder(this).setTitle("Import failed").setMessage(err)
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }, "import").start()
        main.post(poll)
        render()
    }

    companion object {
        private const val REQ_DISK = 1
        private const val REQ_NOTIFY = 3
    }
}
