package com.iossweet.sweet16.boot

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds what QEMU loads for one boot, from the files the user picked or the
 * ones shipped in the APK. Port of tools/mkpayload.py + tools/qemu-sweet.py
 * build_image + tools/mkvirtbundle.py (BootBuilderTest compares the bytes):
 *
 *   ram.bin    kernel segments (chained fixups resolved), optional RAM disk,
 *              ADT, boot_args; loaded at [BootImage.physBase]
 *   stub.bin   tools/virt-stub with its parameters; CPU 0 starts there
 *
 * Kernels: a Mach-O MH_EXECUTE (an xnu build such as the sweet kernel) or an
 * MH_FILESET kernelcache (entry from its com.apple.kernel), decrypted and
 * decompressed (raw Mach-O, not IM4P).
 */
object BootBuilder {
    const val STUB_PA = 0x40200000L
    private const val PAGE = 0x4000L
    private const val BOOT_LINE_LENGTH = 608
    private const val BA_CMDLINE = 108

    enum class AdtMode {
        /** mkadt.py --virt; /product copied from the Apple tree if one is given. */
        GENERATED,
        /** The Apple tree itself with iBoot-style /chosen fixes (stock kernelcaches; experimental). */
        APPLE_AS_IS,
    }

    class Plan(
        val kernel: ByteArray,
        val ramMb: Int,
        val cmdline: String,
        val adtMode: AdtMode = AdtMode.GENERATED,
        val appleAdt: ByteArray? = null,
        val ramdisk: File? = null,
        val seed: ByteArray? = null,
    )

    class BootImage(
        val ramBin: File,
        val stubBin: File,
        val physBase: Long,
        val stubPa: Long,
        val entryPa: Long,
        val bootArgsPa: Long,
        val cmdline: String,
        val cpuModel: String,
        val summary: String,
    )

    fun build(plan: Plan, stubTemplate: ByteArray, outDir: File): BootImage {
        val k = MachO.parse(plan.kernel)
        val nfix = MachO.applyChainedFixups(plan.kernel, k)
        val segs = k.segs.filter { it.vmsize != 0L }
        val text = k.segs.first()
        require(text.name == "__TEXT" && text.fileoff == 0L) { "the kernel's first segment must be __TEXT at file offset 0" }
        val virtBase = text.vmaddr and (0x2000000L - 1).inv()
        var kernelEnd = segs.maxOf { it.vmaddr + it.vmsize }
        kernelEnd = (kernelEnd + PAGE - 1) and (PAGE - 1).inv()

        // Device tree and the kernel's RAM.
        val ramEnd = VirtAdt.RAM_BASE + (plan.ramMb.toLong() shl 20)
        val adt: AdtNode = when (plan.adtMode) {
            AdtMode.GENERATED -> VirtAdt.build(plan.ramMb,
                plan.appleAdt?.let { VirtAdt.productOf(Adt.load(it)) } ?: emptyList(), plan.seed)
            AdtMode.APPLE_AS_IS -> {
                val t = Adt.load(requireNotNull(plan.appleAdt) { "pick the Apple device tree" })
                val chosen = requireNotNull(t.child("chosen")) { "the device tree has no /chosen" }
                chosen.set("dram-base", Adt.u64(VirtAdt.PHYS_BASE))
                chosen.set("dram-size", Adt.u64(ramEnd - VirtAdt.PHYS_BASE))
                t
            }
        }
        val chosen = adt.child("chosen")!!
        val physBase = chosen.prop("dram-base")!!.le64()
        val memSize = chosen.prop("dram-size")!!.le64()

        class Piece(val vmaddr: Long, val vmsize: Long, val src: (RandomAccessFile, Long) -> Unit, val len: Long)
        val pieces = segs.map { s ->
            Piece(s.vmaddr, s.vmsize, { out, at -> out.seek(at); out.write(plan.kernel, s.fileoff.toInt(), s.filesize.toInt()) }, s.filesize)
        }.toMutableList()
        var ramdiskInfo = ""
        plan.ramdisk?.let { rd ->
            val len = rd.length()
            val size = (len + PAGE - 1) and (PAGE - 1).inv()
            val va = kernelEnd
            kernelEnd = va + size
            val pa = va - virtBase + physBase
            pieces += Piece(va, size, { out, at -> copyFile(rd, out, at) }, len)
            var mmap = chosen.child("memory-map")
            if (mmap == null) {
                mmap = Adt.node("memory-map")
                chosen.children += mmap
            }
            mmap.props.removeAll { it.name == "RAMDisk" }
            mmap.props += AdtProp("RAMDisk", 0, Adt.u64(pa, size))
            ramdiskInfo = "RAM disk ${len shr 10} KiB at 0x%x\n".format(pa)
        }
        val adtBlob = Adt.serialize(adt)

        // build_image(): physical layout below the kernel's dram top.
        val delta = physBase - virtBase
        val adtPa = kernelEnd + delta
        val baPa = (adtPa + adtBlob.size + PAGE - 1) and (PAGE - 1).inv()
        val top = baPa + PAGE
        require(top < physBase + memSize) { "kernel + RAM disk do not fit in ${plan.ramMb} MB" }
        val line = plan.cmdline.trim().toByteArray(Charsets.US_ASCII)
        require(line.size < BOOT_LINE_LENGTH) { "boot-args longer than ${BOOT_LINE_LENGTH - 1} bytes" }
        val fb = VirtAdt.FB
        val ba = ByteBuffer.allocate(736).order(ByteOrder.LITTLE_ENDIAN)
        ba.putShort(2).putShort(2).putInt(0)
        ba.putLong(virtBase).putLong(physBase).putLong(memSize).putLong(top)
        ba.putLong(fb).putLong(1).putLong(VirtAdt.FB_W * 4L).putLong(VirtAdt.FB_W.toLong()).putLong(VirtAdt.FB_H.toLong()).putLong(32)
        ba.putInt(0).putInt(0)
        ba.putLong(adtPa - delta).putInt(adtBlob.size)
        ba.put(line.copyOf(BOOT_LINE_LENGTH))
        ba.putInt(0).putLong(0).putLong(memSize)
        check(ba.position() == 736)

        outDir.mkdirs()
        val ram = File(outDir, "ram.bin")
        ram.delete()
        RandomAccessFile(ram, "rw").use { out ->
            out.setLength(top - physBase)
            for (p in pieces) p.src(out, p.vmaddr + delta - physBase)
            out.seek(adtPa - physBase)
            out.write(adtBlob)
            out.seek(baPa - physBase)
            out.write(ba.array())
        }

        val entryPa = k.entry + delta
        val stub = stubTemplate.copyOf()
        val at = indexOf(stub, "SWVSTUB1".toByteArray())
        require(at >= 0) { "boot stub without a parameter block" }
        ByteBuffer.wrap(stub, at + 8, 40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(baPa).putLong(entryPa).putLong(fb)
            .putInt(VirtAdt.FB_W).putInt(VirtAdt.FB_H).putInt(VirtAdt.FB_W * 4)
        val stubFile = File(outDir, "stub.bin")
        stubFile.writeBytes(stub)

        val summary = buildString {
            append(if (k.fileset) "Kernelcache (MH_FILESET), " else "Kernel (MH_EXECUTE), ")
            append(if (k.arm64e) "arm64e (PAC)" else "arm64").append('\n')
            append("${segs.size} segments, $nfix chained fixups resolved\n")
            append("entry 0x%x (VA 0x%x), boot_args 0x%x\n".format(entryPa, k.entry, baPa))
            append(ramdiskInfo)
            append("device tree: ${if (plan.adtMode == AdtMode.GENERATED) "sweet-virt (generated)" else "Apple, as-is"}, ${adtBlob.size} bytes\n")
            append("kernel RAM 0x%x + %d MB\n".format(physBase, memSize shr 20))
        }
        return BootImage(ram, stubFile, physBase, STUB_PA, entryPa, baPa, String(line, Charsets.US_ASCII),
            if (k.arm64e) "max" else "cortex-a76", summary)
    }

    private fun copyFile(src: File, out: RandomAccessFile, at: Long) {
        out.seek(at)
        src.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun ByteArray.le64(): Long = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).long
}

/** Mach-O kernel / kernelcache reader (tools/mkpayload.py parse_macho). */
object MachO {
    class Seg(val name: String, val vmaddr: Long, val vmsize: Long, val fileoff: Long, val filesize: Long)
    class Image(val segs: List<Seg>, val entry: Long, val fixups: Pair<Int, Int>?, val fileset: Boolean, val arm64e: Boolean)

    private const val LC_SEGMENT_64 = 0x19
    private const val LC_UNIXTHREAD = 0x5
    private const val LC_DYLD_CHAINED_FIXUPS = 0x80000034.toInt()
    private const val LC_FILESET_ENTRY = 0x80000035.toInt()
    private const val MH_FILESET = 0xc

    fun parse(buf: ByteArray): Image {
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.size > 32) { "file too small for a kernel" }
        val magic = b.getInt(0)
        if (magic != 0xfeedfacf.toInt()) {
            val head = String(buf, 0, minOf(16, buf.size), Charsets.ISO_8859_1)
            require(!(buf[0] == 0x30.toByte() && head.contains("IM4P"))) {
                "this is an IM4P container: extract the kernelcache first (ipsw kernel dec / img4tool -e), " +
                    "then pick the decompressed Mach-O"
            }
            require(!head.startsWith("comp") && !head.startsWith("bvx2")) { "this kernelcache is compressed: decompress it first" }
            throw IllegalArgumentException("not a 64-bit Mach-O kernel")
        }
        require(b.getInt(4) == 0x0100000c) { "not an arm64 kernel (cputype 0x%x)".format(b.getInt(4)) }
        val arm64e = (b.getInt(8) and 0xff) == 2
        val fileset = b.getInt(12) == MH_FILESET
        val (segs, entry, fixups, entries) = commands(b, 0)
        var e = entry
        if (fileset) {
            val kern = entries.firstOrNull { it.first == "com.apple.kernel" }
                ?: throw IllegalArgumentException("kernelcache without com.apple.kernel")
            e = commands(b, kern.second.toInt()).entry
        }
        requireNotNull(e) { "no LC_UNIXTHREAD entry point" }
        return Image(segs, e, fixups, fileset, arm64e)
    }

    private data class Cmds(val segs: List<Seg>, val entry: Long?, val fixups: Pair<Int, Int>?, val entries: List<Pair<String, Long>>)

    private fun commands(b: ByteBuffer, hdr: Int): Cmds {
        val ncmds = b.getInt(hdr + 16)
        var off = hdr + 32
        val segs = mutableListOf<Seg>()
        var entry: Long? = null
        var fixups: Pair<Int, Int>? = null
        val entries = mutableListOf<Pair<String, Long>>()
        repeat(ncmds) {
            val cmd = b.getInt(off)
            val size = b.getInt(off + 4)
            when (cmd) {
                LC_SEGMENT_64 -> {
                    val nb = ByteArray(16).also { a -> for (i in 0 until 16) a[i] = b.get(off + 8 + i) }
                    segs += Seg(String(nb, Charsets.US_ASCII).substringBefore('\u0000'),
                        b.getLong(off + 24), b.getLong(off + 32), b.getLong(off + 40), b.getLong(off + 48))
                }
                LC_UNIXTHREAD -> entry = b.getLong(off + 16 + 32 * 8)
                LC_DYLD_CHAINED_FIXUPS -> fixups = b.getInt(off + 8) to b.getInt(off + 12)
                LC_FILESET_ENTRY -> {
                    val fileoff = b.getLong(off + 16)
                    val nameOff = b.getInt(off + 24)
                    val sb = StringBuilder()
                    var i = off + nameOff
                    while (i < off + size && b.get(i).toInt() != 0) sb.append(b.get(i++).toInt().toChar())
                    entries += sb.toString() to fileoff
                }
            }
            off += size
        }
        return Cmds(segs, entry, fixups, entries)
    }

    /** Rewrite every chained rebase in [buf] to its plain target VA; returns the count. */
    fun applyChainedFixups(buf: ByteArray, m: Image): Int {
        val (dataOff, _) = m.fixups ?: return 0
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val startsOff = b.getInt(dataOff + 4)
        val importsCount = b.getInt(dataOff + 16)
        require(importsCount == 0) { "the kernel has binds; only rebases are supported" }
        val imageBase = m.segs[0].vmaddr
        val segCount = b.getInt(dataOff + startsOff)
        var n = 0
        for (i in 0 until segCount) {
            val segInfo = b.getInt(dataOff + startsOff + 4 + 4 * i)
            if (segInfo == 0) continue
            val base = dataOff + startsOff + segInfo
            val pageSize = b.getShort(base + 4).toInt() and 0xffff
            val fmt = b.getShort(base + 6).toInt() and 0xffff
            val pageCount = b.getShort(base + 20).toInt() and 0xffff
            val seg = m.segs[i]
            for (p in 0 until pageCount) {
                val start = b.getShort(base + 22 + 2 * p).toInt() and 0xffff
                if (start == 0xffff) continue
                require(start and 0x8000 == 0) { "DYLD_CHAINED_PTR_START_MULTI not supported" }
                var loc = (seg.fileoff + p.toLong() * pageSize + start).toInt()
                while (true) {
                    val raw = b.getLong(loc)
                    val (target, next, stride) = decode(raw, fmt, imageBase)
                    b.putLong(loc, target)
                    n++
                    if (next == 0) break
                    loc += next * stride
                }
            }
        }
        return n
    }

    private fun decode(raw: Long, fmt: Int, imageBase: Long): Triple<Long, Int, Int> = when (fmt) {
        2, 6 -> {
            require(raw ushr 63 == 0L) { "bind in the kernel" }
            var target = raw and ((1L shl 36) - 1)
            val high8 = (raw ushr 36) and 0xff
            val next = ((raw ushr 51) and 0xfff).toInt()
            if (fmt == 6) target += imageBase
            Triple(target or (high8 shl 56), next, 4)
        }
        8 -> Triple(imageBase + (raw and ((1L shl 30) - 1)), ((raw ushr 51) and 0xfff).toInt(), 4)
        1, 7, 9, 12 -> {
            val auth = raw ushr 63
            val next = ((raw ushr 51) and 0x7ff).toInt()
            val stride = if (fmt == 7 || fmt == 12) 4 else 8
            var target = if (auth != 0L) raw and 0xffffffffL
            else (raw and ((1L shl 43) - 1)) or (((raw ushr 43) and 0xff) shl 56)
            if (fmt == 7 || fmt == 9 || fmt == 12 || auth != 0L) target += imageBase
            Triple(target, next, stride)
        }
        else -> throw IllegalArgumentException("unsupported chained pointer format $fmt")
    }
}
