package com.iossweet.sweet16

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Files and settings of the app.
 *
 * Boot inputs (all optional except a kernel):
 *  - kernel: the sweet xnu kernel shipped in the APK (assets/boot/kernel), or
 *    one the user picked (files/user/kernel): any decrypted, decompressed
 *    arm64 Mach-O kernel or kernelcache.
 *  - device tree: generated for QEMU virt; the user's Apple DeviceTree
 *    (files/user/devicetree) adds its /product, or is used as-is.
 *  - RAM disk: none, the built-in self-test shell (assets/boot/sweet-sh), or
 *    the user's image (files/user/ramdisk).
 *  - root disk: the imported iOS 16 HFS+ image (files/disk/ios16-root.img).
 */
class Store(private val context: Context) {
    val files: File = context.filesDir
    val userDir = File(files, "user")
    val userKernel = File(userDir, "kernel")
    val userDeviceTree = File(userDir, "devicetree")
    val userRamdisk = File(userDir, "ramdisk")
    val diskDir = File(files, "disk")
    val disk = File(diskDir, "ios16-root.img")
    val qemuData = File(files, "qemu")
    val consoleLog = File(files, "console.log")
    val qemuLog = File(files, "qemu.log")
    val qemuDebugLog = File(files, "qemu-debug.log")
    val lastLaunch = File(files, "last-launch.txt")
    val launchLog = File(files, "launch.log")
    val bootDir = File(context.cacheDir, "boot")
    val selftestRamdisk = File(files, "sweet-sh")
    val vncSocket = File(context.cacheDir, "vnc.sock")
    val qmpSocket = File(context.cacheDir, "qmp.sock")
    val serialSocket = File(context.cacheDir, "serial.sock")

    val prefs: SharedPreferences = context.getSharedPreferences("sweet16", Context.MODE_PRIVATE)

    private fun int(k: String, d: Int) = prefs.getInt(k, d)
    private fun bool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun str(k: String, d: String) = prefs.getString(k, d) ?: d

    var cores: Int
        get() = int("cores", defaultCores())
        set(v) = prefs.edit().putInt("cores", v.coerceIn(1, 8)).apply()
    var showKernelLog: Boolean
        get() = bool("kernel_log", true)
        set(v) = prefs.edit().putBoolean("kernel_log", v).apply()
    var forwardVolumeKeys: Boolean
        get() = bool("volume_keys", true)
        set(v) = prefs.edit().putBoolean("volume_keys", v).apply()
    var extraBootArgs: String
        get() = str("boot_args", "")
        set(v) = prefs.edit().putString("boot_args", v.trim()).apply()
    var tbSizeMb: Int
        get() = int("tb_size", 256)
        set(v) = prefs.edit().putInt("tb_size", v.coerceIn(64, 1024)).apply()
    var ramMb: Int
        get() = int("ram_mb", 4096)
        set(v) = prefs.edit().putInt("ram_mb", v.coerceIn(2048, 8192)).apply()

    /** Use the picked kernel instead of the built-in one (both profiles). */
    var useUserKernel: Boolean
        get() = bool("user_kernel", false) && userKernel.isFile
        set(v) = prefs.edit().putBoolean("user_kernel", v).apply()
    /** 0 generated, 1 generated + /product of the picked tree, 2 the picked Apple tree as-is. */
    var devAdtMode: Int
        get() = int("dev_adt", 0).let { if (it > 0 && !userDeviceTree.isFile) 0 else it }
        set(v) = prefs.edit().putInt("dev_adt", v).apply()
    /** 0 none, 1 self-test shell, 2 the picked file. */
    var devRamdisk: Int
        get() = int("dev_ramdisk", 1).let { if (it == 2 && !userRamdisk.isFile) 1 else it }
        set(v) = prefs.edit().putInt("dev_ramdisk", v).apply()
    var devAttachDisk: Boolean
        get() = bool("dev_disk", false)
        set(v) = prefs.edit().putBoolean("dev_disk", v).apply()
    var devBootArgs: String
        get() = str("dev_args", DEV_BOOT_ARGS)
        set(v) = prefs.edit().putString("dev_args", v.trim()).apply()
    var devLogExceptions: Boolean
        get() = bool("dev_dint", false)
        set(v) = prefs.edit().putBoolean("dev_dint", v).apply()

    fun hasDisk(): Boolean = disk.isFile && disk.length() > 0

    fun hasBuiltinKernel(): Boolean = assetExists("boot/kernel")

    fun hasKernel(): Boolean = useUserKernel || hasBuiltinKernel()

    fun kernelBytes(): ByteArray =
        if (useUserKernel) userKernel.readBytes()
        else context.assets.open("boot/kernel").use { it.readBytes() }

    fun kernelLabel(): String = when {
        useUserKernel -> "your kernel (${userKernel.length() shr 20} MB)"
        hasBuiltinKernel() -> "built-in sweet xnu kernel"
        else -> "none"
    }

    fun stubTemplate(): ByteArray = context.assets.open("boot/stub.bin").use { it.readBytes() }

    /** Copy the self-test shell out of the APK (QEMU and the builder read files). */
    fun selftest(): File? {
        if (!assetExists("boot/sweet-sh")) return null
        val bytes = context.assets.open("boot/sweet-sh").use { it.readBytes() }
        if (!selftestRamdisk.isFile || selftestRamdisk.length() != bytes.size.toLong()) selftestRamdisk.writeBytes(bytes)
        return selftestRamdisk
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

    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).close(); true
    } catch (e: Exception) {
        false
    }

    fun nativeLibDir(): String = context.applicationInfo.nativeLibraryDir

    fun qemuBinary(): File = File(nativeLibDir(), "libqemu.so")

    companion object {
        fun defaultCores(): Int = Runtime.getRuntime().availableProcessors().let { (it / 2).coerceIn(1, 4) }

        /** Developer boot default: verbose kernel, console on the PL011, RAM disk root. */
        const val DEV_BOOT_ARGS = "-v debug=0xc serial=3 keepsyms=1 -enable_kprintf_spam -noprogress rd=md0"

        /** iOS 16 from the imported disk (the phone's line, minus phone-only options). */
        const val IOS_BOOT_ARGS = "debug=0xc serial=3 keepsyms=1 -enable_kprintf_spam -noprogress rd=ufsroot0 " +
            "unentitled_ios_sim_launch=1 ignition_level=1 wdt_test=no_user_checkin ufsrw=1 rootrw=1 " +
            "launchd_unsecure_cache=1 wdt=-1 sweetfb_hz=30"
    }
}
