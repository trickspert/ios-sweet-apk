package com.iossweet.sweet16

import com.iossweet.sweet16.boot.BootBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The app's boot builder on a real kernel (e.g. the CI artifact), exactly as
 * Developer boot uses it: SWEET16_KERNEL, SWEET16_RAMDISK (optional),
 * SWEET16_OUT (output dir: ram.bin, stub.bin). Skipped without SWEET16_KERNEL.
 */
class RealKernelTest {
    @Test
    fun buildsBootImage() {
        val k = System.getenv("SWEET16_KERNEL")
        assumeTrue("SWEET16_KERNEL not set", k != null)
        val img = BootBuilder.build(
            BootBuilder.Plan(File(k!!).readBytes(), 4096, Store.DEV_BOOT_ARGS + " cpus=1 sweetfb_scale=2",
                ramdisk = System.getenv("SWEET16_RAMDISK")?.let { File(it) }),
            File("../../tools/virt-stub/stub.bin").readBytes(), File(System.getenv("SWEET16_OUT") ?: "build/realkernel"))
        println(img.summary)
        println("phys 0x%x stub 0x%x".format(img.physBase, img.stubPa))
    }
}
