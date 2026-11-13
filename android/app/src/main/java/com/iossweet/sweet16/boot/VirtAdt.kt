package com.iossweet.sweet16.boot

import com.iossweet.sweet16.boot.Adt.cstr
import com.iossweet.sweet16.boot.Adt.node
import com.iossweet.sweet16.boot.Adt.u32
import com.iossweet.sweet16.boot.Adt.u64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Apple Device Tree of the sweet-virt machine: a port of
 * `tools/mkadt.py --virt` (byte-identical for the same seed and /product;
 * VirtAdtTest checks it against the Python tool).
 *
 * QEMU virt: GICv3 at 0x08000000/0x080a0000, 62.5 MHz timer (virtual timer
 * INTID 27), PL011 at 0x09000000 (/arm-io/uart0, the kernel console),
 * virtio-mmio at 0x0a000000 (/sweet-virtio), RAM from 0x40000000; the kernel
 * owns PHYS_BASE..end, the framebuffer (560x1218) sits below it.
 */
object VirtAdt {
    const val PHYS_BASE = 0xb0000000L
    const val PANIC_LOG_SIZE = 0x100000L
    const val SOC_BASE = 0x01000000L
    const val FB = 0x9c000000L
    const val FB_SIZE = 0x1700000L
    const val FB_W = 560
    const val FB_H = 1218
    const val RAM_BASE = 0x40000000L
    const val CPUS = 8
    private const val GICD = 0x08000000L
    private const val GICD_SIZE = 0x10000L
    private const val GICR = 0x080a0000L
    private const val GICR_SIZE = 0x100000L
    private const val TIMEBASE = 62500000L
    private const val VTIMER_INTID = 27L
    private const val PL011 = 0x09000000L
    private const val VIRTIO = 0x0a000000L
    private const val SWEET_ECID = 0x5357454554000001L

    /** /product properties of a real device tree (placeholders and bookkeeping dropped). */
    fun productOf(apple: AdtNode): List<Pair<String, ByteArray>> {
        val p = apple.children.firstOrNull { it.name == "product" } ?: return emptyList()
        return p.props.filter { it.flags == 0 && it.name != "name" && it.name != "AAPL,phandle" }.map { it.name to it.value }
    }

    fun build(ramMb: Int, product: List<Pair<String, ByteArray>> = emptyList(), seed: ByteArray? = null): AdtNode {
        val ramEnd = RAM_BASE + (ramMb.toLong() shl 20)
        require(ramEnd > PHYS_BASE + (512L shl 20)) { "$ramMb MB is too small: the kernel needs RAM above 0xb0000000" }
        var memSize = ramEnd - PHYS_BASE
        val panicBase = PHYS_BASE + memSize - PANIC_LOG_SIZE
        memSize -= PANIC_LOG_SIZE
        val rnd = seed ?: ByteArray(64).also { SecureRandom().nextBytes(it) }
        var ph = 0L
        fun ph() = "AAPL,phandle" to u32(++ph)

        val chosen = node("chosen", listOf(
            ph(),
            "dram-base" to u64(PHYS_BASE),
            "dram-size" to u64(memSize),
            "random-seed" to rnd,
            "debug-enabled" to u32(1),
            "embedded-panic-log-size" to u32(PANIC_LOG_SIZE),
            "firmware-version" to cstr("sweet-shim"),
            "system-firmware-version" to cstr("sweet-shim"),
            "boot-manifest-hash" to MessageDigest.getInstance("SHA-384").digest("sweet boot manifest".toByteArray()),
            "crypto-hash-method" to cstr("sha2-384"),
            "display-scale" to u32(3),
            "unique-chip-id" to u64(SWEET_ECID),
            "chip-id" to u32(0x8015),
            "certificate-production-status" to u32(0),
            "effective-production-status-ap" to u32(0),
            "certificate-security-mode" to u32(1),
            "effective-security-mode-sep" to u32(1),
        ), listOf(
            node("memory-map", listOf(ph())),
            node("asmb", listOf(ph(), "lp-sip0" to u64(0x6f), "lp-sip1" to u32(1))),
        ))

        val cpuNodes = (0 until CPUS).map { core ->
            node("cpu$core", listOf(
                ph(),
                "device_type" to cstr("cpu"),
                "compatible" to cstr("qcom,kryo470", "ARM,v8"),
                "reg" to u32(core.toLong()),
                "cpu-id" to u32(core.toLong()),
                "cluster-id" to u32(0),
                "cluster-type" to u32(0),
                "die-cluster-id" to u32(0),
                "cluster-core-id" to u32(core.toLong()),
                "l2-cache-id" to u32(core.toLong()),
                "l3-cache-id" to u32(0),
                "state" to cstr(if (core == 0) "running" else "stopped"),
                "timebase-frequency" to u32(TIMEBASE),
                "clock-frequency" to u32(1804800000),
                "bus-frequency" to u32(100000000),
                "memory-frequency" to u32(1866000000),
                "peripheral-frequency" to u32(TIMEBASE),
                "fixed-frequency" to u32(TIMEBASE),
            ))
        }
        val cpus = node("cpus", listOf(ph(), "#address-cells" to u32(1), "#size-cells" to u32(0)), cpuNodes)

        val gic = node("gic", listOf(
            ph(),
            "compatible" to cstr("arm,gic-v3"),
            "interrupt-controller" to ByteArray(0),
            "virtual-timer-intid" to u32(VTIMER_INTID),
            "reg" to u64(GICD - SOC_BASE, GICD_SIZE, GICR - SOC_BASE, GICR_SIZE),
        ))
        val armIo = node("arm-io", listOf(
            ph(),
            "device_type" to cstr("sweet-io"),
            "compatible" to cstr("sweet-io", "arm-io"),
            "#address-cells" to u32(2),
            "#size-cells" to u32(2),
            "ranges" to u64(0, SOC_BASE, 0x100000000L - SOC_BASE),
        ), listOf(
            gic,
            node("wdt", listOf(ph(), "no-pmu" to u32(1))),
            node("disp0", listOf(
                ph(),
                "device_type" to cstr("display-subsystem"),
                "compatible" to cstr("sweet,display"),
                "canvas-width" to u32(1125),
                "canvas-height" to u32(2436),
            )),
            node("uart0", listOf(
                ph(),
                "device_type" to cstr("serial"),
                "compatible" to cstr("arm,pl011"),
                "reg" to u64(PL011 - SOC_BASE, 0x1000),
            )),
        ))
        val fbcon = node("sweet-fbcon", listOf(
            ph(),
            "reg" to u64(FB, FB_SIZE),
            "width" to u32(FB_W.toLong()),
            "height" to u32(FB_H.toLong()),
            "stride" to u32(FB_W * 4L),
        ))
        ph() // mkadt.py builds its sweet-ufs node (phone only) here too: keep the phandle numbering
        val defaults = node("defaults", listOf(ph(), "no-effaceable-storage" to u32(1)))
        val buttons = node("buttons", listOf(
            ph(),
            "button-names" to cstr("volup", "voldown", "hold"),
            "home-button-type" to u32(2),
            "opposed-power-vol-buttons" to u32(1),
        ))
        val virtio = node("sweet-virtio", listOf(
            ph(),
            "compatible" to cstr("sweet,virt"),
            "reg" to u64(VIRTIO, 0x200, 32),
            "psci-hvc" to u32(1),
        ))
        val productNode = if (product.isNotEmpty()) listOf(node("product", listOf(ph()) + product)) else emptyList()
        val pram = node("pram", listOf(ph(), "reg" to u64(panicBase, PANIC_LOG_SIZE)))

        return node("device-tree", listOf(
            ph(),
            "compatible" to cstr("D22AP", "iPhone10,3", "xiaomi,sweet", "qcom,sm7150", "AppleARM"),
            "model" to cstr("iPhone10,3"),
            "target-type" to cstr("D22"),
            "target-sub-type" to cstr("D22AP"),
            "manufacturer" to cstr("Apple Inc."),
            "#address-cells" to u32(2),
            "#size-cells" to u32(2),
        ), listOf(chosen, defaults, cpus, armIo, fbcon, buttons, pram) + productNode + listOf(virtio))
    }
}
