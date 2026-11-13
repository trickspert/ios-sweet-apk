package com.iossweet.sweet16

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * Developer boot: verify that XNU really runs before trying SpringBoard.
 * Pick your own files (decrypted, decompressed kernel / kernelcache, Apple
 * DeviceTree, RAM disk) or use the ones built into the APK, edit the
 * boot-args, look at the exact QEMU command, boot, and watch the serial
 * console.
 */
class DevBootActivity : Activity() {
    private lateinit var store: Store
    private lateinit var list: LinearLayout
    private lateinit var argsEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(list) })
        build()
    }

    private fun build() {
        list.removeAllViews()
        title("Developer boot")
        note("Early boot with a verbose kernel and the serial console, to check real XNU execution " +
            "(kernel log, panics, the self-test shell) before booting iOS 16 to SpringBoard. " +
            "Everything shown comes from QEMU: the screen is the guest framebuffer, the console is the guest's PL011.")

        section("Kernel")
        radio(listOf(
            "Built-in sweet xnu kernel" + if (store.hasBuiltinKernel()) "" else " (not in this APK)",
            "Your file" + if (store.userKernel.isFile) " (${store.userKernel.length() shr 20} MB)" else " (none picked)",
        ), if (store.useUserKernel) 1 else 0) { store.useUserKernel = it == 1 }
        button("Pick kernel / kernelcache…") { pick(REQ_KERNEL) }
        note("A decrypted, decompressed arm64 Mach-O: an xnu build (MH_EXECUTE) or a kernelcache (MH_FILESET). " +
            "Apple's stock kernelcaches expect Apple SoC hardware (AIC, Apple UART, PMGR) that QEMU's virt machine " +
            "does not emulate: they start executing, then usually fault early with nothing on the console " +
            "(see QEMU log with exception logging, and CPU regs). The sweet kernel has drivers for this machine.")

        section("Device tree")
        radio(listOf(
            "Generated for QEMU virt (sweet-virt)",
            "Generated + /product from your Apple DeviceTree",
            "Your Apple DeviceTree as-is (stock kernelcache; experimental)",
        ), store.devAdtMode) { store.devAdtMode = it }
        button("Pick DeviceTree…" + if (store.userDeviceTree.isFile) " (have ${store.userDeviceTree.length() shr 10} KB)" else "") { pick(REQ_ADT) }
        note("Raw tree or unencrypted IM4P (e.g. from `ipsw img4 im4p extract DeviceTree.d22ap.im4p`).")

        section("RAM disk (rd=md0)")
        radio(listOf(
            "None",
            "Self-test shell (sweet-sh: interactive PID 1, prints kern.version from the kernel)",
            "Your file" + if (store.userRamdisk.isFile) " (${store.userRamdisk.length() shr 20} MB)" else " (none picked)",
        ), store.devRamdisk) { store.devRamdisk = it }
        button("Pick RAM disk image…") { pick(REQ_RAMDISK) }
        note("Mounted as md0: an HFS+ image, or a Mach-O executable that mockfs runs as /sbin/launchd.")

        section("Root disk")
        switch("Attach the iOS 16 disk (virtio-blk, ufsroot0)" + if (store.hasDisk()) "" else " — not imported",
            store.devAttachDisk) { store.devAttachDisk = it }

        section("Kernel boot-args")
        argsEdit = EditText(this).apply {
            setText(store.devBootArgs)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        list.addView(argsEdit)
        note("The app appends cpus=N and sweetfb_scale=2. -v = verbose, serial=3 = console output and input " +
            "on the serial port, rd=md0 = RAM disk root, rd=ufsroot0 = the iOS disk.")
        LinearLayout(this).apply {
            addView(smallButton("RAM disk preset") { argsEdit.setText(Store.DEV_BOOT_ARGS) })
            addView(smallButton("iOS disk preset") { argsEdit.setText("-v " + Store.IOS_BOOT_ARGS) })
        }.also { list.addView(it) }
        switch("Log CPU exceptions (QEMU -d int,guest_errors,unimp; slow)", store.devLogExceptions) { store.devLogExceptions = it }

        section("Run")
        button("Show exact QEMU command") { saveArgs(); showCommand() }
        button("Boot") { saveArgs(); boot() }
    }

    private fun saveArgs() { store.devBootArgs = argsEdit.text.toString() }

    private fun showCommand() {
        Thread({
            val text = try {
                Launch.prepare(store, Launch.DEV).describe(store)
            } catch (e: Exception) {
                "Cannot build this boot: ${e.message}"
            }
            runOnUiThread { show("QEMU command and boot-args", text) }
        }, "describe").start()
    }

    private fun boot() {
        if (VmState.active) {
            AlertDialog.Builder(this).setMessage("A virtual machine is already running. Stop it first.")
                .setPositiveButton("OK", null).show()
            return
        }
        VmService.start(this, Launch.DEV)
        startActivity(Intent(this, ConsoleActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun pick(req: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), req)
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val dst = when (requestCode) {
            REQ_KERNEL -> store.userKernel
            REQ_ADT -> store.userDeviceTree
            else -> store.userRamdisk
        }
        val wait = AlertDialog.Builder(this).setMessage("Copying…").setCancelable(false).show()
        Thread({
            val err = try {
                copy(uri, dst)
                when (requestCode) {
                    REQ_KERNEL -> { com.iossweet.sweet16.boot.MachO.parse(dst.readBytes()); store.useUserKernel = true }
                    REQ_ADT -> { com.iossweet.sweet16.boot.Adt.load(dst.readBytes()); if (store.devAdtMode == 0) store.devAdtMode = 1 }
                    else -> store.devRamdisk = 2
                }
                null
            } catch (e: Throwable) {
                dst.delete()
                e.message ?: e.toString()
            }
            runOnUiThread {
                wait.dismiss()
                if (err != null) show("Not usable", err)
                build()
            }
        }, "pick").start()
    }

    private fun copy(uri: Uri, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".tmp")
        contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
        if (!tmp.renameTo(dst)) throw IllegalStateException("cannot store the file")
    }

    private fun show(title: String, body: String) {
        val tv = TextView(this).apply {
            text = body; typeface = Typeface.MONOSPACE; textSize = 10f; setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(HorizontalScrollView(this@DevBootActivity).apply { addView(tv) }) })
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, body))
            }
            .setNegativeButton("Close", null).show()
    }

    private fun radio(options: List<String>, selected: Int, change: (Int) -> Unit) {
        val g = RadioGroup(this)
        options.forEachIndexed { i, label ->
            g.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = label; setTextColor(Color.WHITE); isChecked = i == selected
                setOnCheckedChangeListener { _, on -> if (on) change(i) }
            })
        }
        list.addView(g)
    }

    private fun title(s: String) = list.addView(TextView(this).apply {
        text = s; textSize = 28f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
    })

    private fun section(s: String) = list.addView(TextView(this).apply {
        text = s; textSize = 14f; setTextColor(getColor(R.color.accent_text)); setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(4))
    })

    private fun note(s: String) = list.addView(TextView(this).apply {
        text = s; textSize = 12.5f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(2), 0, dp(4))
    })

    private fun switch(label: String, on: Boolean, change: (Boolean) -> Unit) = list.addView(Switch(this).apply {
        text = label; setTextColor(Color.WHITE); isChecked = on; setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> change(v) }
    })

    private fun button(label: String, action: () -> Unit) = list.addView(Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    })

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setOnClickListener { action() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_KERNEL = 1
        private const val REQ_ADT = 2
        private const val REQ_RAMDISK = 3
    }
}
