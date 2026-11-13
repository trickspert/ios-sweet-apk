package com.iossweet.sweet16

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Boot bundle metadata (tools/mkvirtbundle.py, bundle.json). */
data class Bundle(
    val name: String,
    val version: String,
    val ramMb: Int,
    val physBase: Long,
    val stubPa: Long,
    val cmdlineOffset: Long,
    val cmdlineMax: Int,
    val cmdline: String,
    val dir: File,
) {
    val ramBin get() = File(dir, "ram.bin")
    val stubBin get() = File(dir, "stub.bin")

    companion object {
        fun load(dir: File): Bundle? = try {
            val j = JSONObject(File(dir, "bundle.json").readText())
            if (j.getInt("format") != 1) null else Bundle(
                name = j.optString("name", "iOS 16"),
                version = j.optString("version", "16"),
                ramMb = j.getInt("ram_mb"),
                physBase = j.getLong("phys_base"),
                stubPa = j.getLong("stub_pa"),
                cmdlineOffset = j.getLong("cmdline_offset"),
                cmdlineMax = j.getInt("cmdline_max"),
                cmdline = j.getString("cmdline"),
                dir = dir,
            ).takeIf { it.ramBin.isFile && it.stubBin.isFile }
        } catch (e: Exception) {
            null
        }
    }

    /** Write the boot-args line into ram.bin (QEMU loads the file as-is). */
    fun writeCmdline(line: String) {
        val bytes = line.toByteArray(Charsets.US_ASCII)
        require(bytes.size < cmdlineMax) { "boot-args longer than ${cmdlineMax - 1} bytes" }
        RandomAccessFile(ramBin, "rw").use { f ->
            f.seek(cmdlineOffset)
            f.write(bytes.copyOf(cmdlineMax))
        }
    }
}

/** Files and settings of the app. */
class Store(private val context: Context) {
    val files: File = context.filesDir
    val bundleDir = File(files, "bundle")
    val diskDir = File(files, "disk")
    val disk = File(diskDir, "ios16-root.img")
    val qemuData = File(files, "qemu")
    val consoleLog = File(files, "console.log")
    val qemuLog = File(files, "qemu.log")
    val vncSocket = File(context.cacheDir, "vnc.sock")
    val qmpSocket = File(context.cacheDir, "qmp.sock")

    val prefs: SharedPreferences = context.getSharedPreferences("sweet16", Context.MODE_PRIVATE)

    var cores: Int
        get() = prefs.getInt("cores", defaultCores())
        set(v) = prefs.edit().putInt("cores", v.coerceIn(1, 8)).apply()

    var showKernelLog: Boolean
        get() = prefs.getBoolean("kernel_log", false)
        set(v) = prefs.edit().putBoolean("kernel_log", v).apply()

    var forwardVolumeKeys: Boolean
        get() = prefs.getBoolean("volume_keys", true)
        set(v) = prefs.edit().putBoolean("volume_keys", v).apply()

    var extraBootArgs: String
        get() = prefs.getString("boot_args", "") ?: ""
        set(v) = prefs.edit().putString("boot_args", v.trim()).apply()

    var tbSizeMb: Int
        get() = prefs.getInt("tb_size", 256)
        set(v) = prefs.edit().putInt("tb_size", v.coerceIn(64, 1024)).apply()

    fun bundle(): Bundle? = Bundle.load(bundleDir)

    fun hasDisk(): Boolean = disk.isFile && disk.length() > 0

    /** Install the boot bundle shipped in the APK (assets/bundle), if newer. */
    fun installBundledBootFiles(): Boolean {
        val assets = context.assets
        val names = try { assets.list("bundle")?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
        if (!names.containsAll(listOf("bundle.json", "ram.bin", "stub.bin"))) return false
        val shipped = assets.open("bundle/bundle.json").use { it.readBytes() }
        val marker = File(bundleDir, ".from-apk")
        val stamp = sha256(shipped) + ":" + context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime
        val current = File(bundleDir, "bundle.json")
        if (current.isFile && marker.isFile && marker.readText() == stamp) return true
        // A bundle the user imported takes precedence over the shipped one.
        if (current.isFile && !marker.isFile && bundle() != null) return true
        val tmp = File(files, "bundle.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        for (n in listOf("ram.bin", "stub.bin", "bundle.json")) {
            assets.open("bundle/$n").use { input -> File(tmp, n).outputStream().use { input.copyTo(it, 1 shl 20) } }
        }
        File(tmp, ".from-apk").writeText(stamp)
        bundleDir.deleteRecursively()
        return tmp.renameTo(bundleDir)
    }

    /** QEMU's data directory (-L): VNC needs its keymap. */
    fun installQemuData() {
        val km = File(qemuData, "keymaps")
        km.mkdirs()
        val names = context.assets.list("qemu/keymaps") ?: return
        for (n in names) {
            val out = File(km, n)
            if (out.isFile) continue
            context.assets.open("qemu/keymaps/$n").use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }

    fun nativeLibDir(): String = context.applicationInfo.nativeLibraryDir

    fun qemuBinary(): File = File(nativeLibDir(), "libqemu.so")

    companion object {
        fun defaultCores(): Int = Runtime.getRuntime().availableProcessors().let { (it / 2).coerceIn(1, 4) }

        fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
