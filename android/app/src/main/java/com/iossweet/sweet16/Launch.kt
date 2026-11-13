package com.iossweet.sweet16

import com.iossweet.sweet16.boot.BootBuilder
import java.io.File

/**
 * One boot: which files, which boot-args, and the exact QEMU command line.
 * The same object is used to start QEMU and to show "Boot details", so what
 * the screen shows is what runs.
 */
class Launch private constructor(
    val profile: String,
    val image: BootBuilder.BootImage,
    val args: List<String>,
    val diskAttached: Boolean,
) {
    val bootArgs: String get() = image.cmdline

    /** The command as a shell line (copyable). */
    fun commandLine(store: Store): String =
        (listOf(store.qemuBinary().path) + args).joinToString(" \\\n  ") { quote(it) }

    fun describe(store: Store): String = buildString {
        append("Profile: ").append(if (profile == IOS) "iOS 16 (root disk)" else "Developer boot").append("\n\n")
        append("Kernel: ").append(store.kernelLabel()).append('\n')
        append(image.summary).append('\n')
        append("Kernel boot-args (boot_args.CommandLine):\n").append(bootArgs).append("\n\n")
        append("QEMU (Termux qemu-system-aarch64, run from the app's native library dir):\n")
        append(commandLine(store)).append('\n')
    }

    companion object {
        const val IOS = "ios"
        const val DEV = "dev"

        /** Builds the boot image into [Store.bootDir] and the QEMU arguments. */
        fun prepare(store: Store, profile: String): Launch {
            require(store.hasKernel()) { "no kernel: this APK has none built in; pick one in Developer boot" }
            val cores = store.cores
            val words = mutableListOf<String>()
            var adtMode = BootBuilder.AdtMode.GENERATED
            var apple: ByteArray? = null
            var ramdisk: File? = null
            val attachDisk: Boolean
            if (profile == IOS) {
                require(store.hasDisk()) { "the iOS 16 disk is missing: import it first" }
                attachDisk = true
                words += Store.IOS_BOOT_ARGS.split(' ')
                if (!store.showKernelLog) words += "sweetfb_noconsole=1"
                words += store.extraBootArgs.split(' ')
                if (store.userDeviceTree.isFile) apple = store.userDeviceTree.readBytes() // its /product
            } else {
                attachDisk = store.devAttachDisk && store.hasDisk()
                words += store.devBootArgs.split(' ')
                when (store.devAdtMode) {
                    1 -> apple = store.userDeviceTree.readBytes()
                    2 -> { apple = store.userDeviceTree.readBytes(); adtMode = BootBuilder.AdtMode.APPLE_AS_IS }
                }
                ramdisk = when (store.devRamdisk) {
                    1 -> store.selftest() ?: throw IllegalStateException("this APK has no self-test RAM disk")
                    2 -> store.userRamdisk
                    else -> null
                }
            }
            // Machine-dependent words the app owns: cores and the half-size framebuffer.
            val line = words.filter { it.isNotBlank() && !it.startsWith("cpus=") && !it.startsWith("sweetfb_scale=") } +
                listOf("cpus=$cores", "sweetfb_scale=2")
            val image = BootBuilder.build(
                BootBuilder.Plan(store.kernelBytes(), store.ramMb, line.joinToString(" "), adtMode, apple, ramdisk),
                store.stubTemplate(), store.bootDir)
            return Launch(profile, image, qemuArgs(store, image, attachDisk, profile == DEV && store.devLogExceptions), attachDisk)
        }

        private fun qemuArgs(store: Store, img: BootBuilder.BootImage, disk: Boolean, trace: Boolean): List<String> {
            val a = mutableListOf(
                "-nodefaults", "-no-user-config",
                "-L", store.qemuData.path,
                "-M", "virt,gic-version=3,highmem=on",
                "-cpu", img.cpuModel,
                "-smp", "${store.cores}",
                "-m", "${store.ramMb}",
                "-accel", "tcg,thread=multi,tb-size=${store.tbSizeMb}",
                "-display", "none",
                "-vnc", "unix:${store.vncSocket.path}",
                "-qmp", "unix:${store.qmpSocket.path},server=on,wait=off",
                // Kernel console (PL011): to the app's terminal socket, and always to console.log.
                "-chardev", "socket,id=con,path=${store.serialSocket.path},server=on,wait=off," +
                    "logfile=${store.consoleLog.path},logappend=off",
                "-serial", "chardev:con",
                "-device", "ramfb",
                "-global", "virtio-mmio.force-legacy=false",
                "-device", "virtio-tablet-device",
                "-device", "virtio-keyboard-device",
            )
            if (disk) {
                a += listOf("-drive", "if=none,id=root,file=${store.disk.path},format=raw,cache=writeback,aio=threads",
                    "-device", "virtio-blk-device,drive=root")
            }
            if (trace) a += listOf("-d", "int,guest_errors,unimp", "-D", store.qemuDebugLog.path)
            a += listOf(
                "-device", "loader,file=${img.ramBin.path},addr=0x%x,force-raw=on".format(img.physBase),
                "-device", "loader,file=${img.stubBin.path},addr=0x%x,force-raw=on".format(img.stubPa),
                "-device", "loader,addr=0x%x,cpu-num=0".format(img.stubPa),
            )
            return a
        }

        private fun quote(s: String) = if (s.all { it.isLetterOrDigit() || it in "-_=,./:+" }) s else "'" + s.replace("'", "'\\''") + "'"
    }
}
