package com.iossweet.sweet16.boot

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Apple Device Tree (ADT), as tools/adt.py:
 * node = {u32 nprops, u32 nchildren, props..., children...};
 * prop = {char name[32], u32 size (bit 31 = placeholder flag), value padded to 4}.
 */
class AdtNode(
    val props: MutableList<AdtProp> = mutableListOf(),
    val children: MutableList<AdtNode> = mutableListOf(),
) {
    val name: String
        get() = prop("name")?.let { v -> String(v, Charsets.US_ASCII).substringBefore('\u0000') } ?: "?"

    fun prop(n: String): ByteArray? = props.firstOrNull { it.name == n }?.value

    fun child(n: String): AdtNode? = children.firstOrNull { it.name == n }

    fun set(n: String, v: ByteArray) {
        val i = props.indexOfFirst { it.name == n }
        if (i >= 0) props[i] = AdtProp(n, 0, v) else props += AdtProp(n, 0, v)
    }
}

class AdtProp(val name: String, val flags: Int, val value: ByteArray)

object Adt {
    fun parse(buf: ByteArray): AdtNode {
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        return parseNode(b)
    }

    private fun parseNode(b: ByteBuffer): AdtNode {
        val nprops = b.int
        val nchildren = b.int
        require(nprops in 0..4096 && nchildren in 0..4096) { "not an Apple device tree" }
        val node = AdtNode()
        repeat(nprops) {
            val nameBytes = ByteArray(32).also { b.get(it) }
            val name = String(nameBytes, Charsets.US_ASCII).substringBefore('\u0000')
            val size = b.int
            val len = size and 0x7fffffff
            require(len <= b.remaining()) { "device tree property $name is truncated" }
            val v = ByteArray(len).also { b.get(it) }
            b.position(b.position() + ((4 - len % 4) % 4))
            node.props += AdtProp(name, size ushr 31, v)
        }
        repeat(nchildren) { node.children += parseNode(b) }
        return node
    }

    fun serialize(node: AdtNode): ByteArray = ByteArrayOutputStream().also { write(node, it) }.toByteArray()

    private fun write(node: AdtNode, out: ByteArrayOutputStream) {
        out.write(le32(node.props.size))
        out.write(le32(node.children.size))
        for (p in node.props) {
            out.write(p.name.toByteArray(Charsets.US_ASCII).copyOf(32))
            out.write(le32(p.value.size or (p.flags shl 31)))
            out.write(p.value)
            out.write(ByteArray((4 - p.value.size % 4) % 4))
        }
        for (c in node.children) write(c, out)
    }

    /**
     * The raw tree from an Apple DeviceTree file: raw ADT, or an unencrypted,
     * uncompressed IM4P (`ipsw img4 im4p extract` gives the raw tree directly).
     */
    fun load(bytes: ByteArray): AdtNode {
        if (bytes.size > 16 && bytes[0] == 0x30.toByte() && String(bytes, 0, 16, Charsets.ISO_8859_1).contains("IM4P")) {
            return parse(Im4p.payload(bytes))
        }
        return parse(bytes)
    }

    fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    fun u32(vararg v: Long): ByteArray =
        ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it.toInt()) } }.array()
    fun u64(vararg v: Long): ByteArray =
        ByteBuffer.allocate(8 * v.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putLong(it) } }.array()
    fun cstr(vararg s: String): ByteArray =
        ByteArrayOutputStream().also { o -> s.forEach { o.write(it.toByteArray(Charsets.US_ASCII)); o.write(0) } }.toByteArray()
    fun node(name: String, props: List<Pair<String, ByteArray>> = emptyList(), children: List<AdtNode> = emptyList()) =
        AdtNode((listOf("name" to cstr(name)) + props).map { AdtProp(it.first, 0, it.second) }.toMutableList(),
            children.toMutableList())
}

/** Minimal DER reader for IM4P containers (tools/adt.py im4p_payload). */
object Im4p {
    private fun der(b: ByteArray, at: Int): Triple<Int, Int, Int> {
        var off = at
        val tag = b[off].toInt() and 0xff
        var len = b[off + 1].toInt() and 0xff
        off += 2
        if (len and 0x80 != 0) {
            val n = len and 0x7f
            len = 0
            repeat(n) { len = (len shl 8) or (b[off + it].toInt() and 0xff) }
            off += n
        }
        return Triple(tag, off, off + len)
    }

    /** The payload of an IM4P; refuses encrypted (KBAG) and compressed ones. */
    fun payload(b: ByteArray): ByteArray {
        val (tag, s, e) = der(b, 0)
        require(tag == 0x30) { "not an IM4P container" }
        val items = mutableListOf<Pair<Int, ByteArray>>()
        var off = s
        while (off < e) {
            val (t, vs, ve) = der(b, off)
            items += t to b.copyOfRange(vs, ve)
            off = ve
        }
        require(items.size >= 4 && String(items[0].second) == "IM4P") { "not an IM4P container" }
        require(items.drop(4).none { it.first == 0x04 }) {
            "this IM4P is encrypted (KBAG): decrypt it first, e.g. with ipsw img4 im4p extract --iv-key"
        }
        val p = items[3].second
        val magic = String(p, 0, minOf(4, p.size), Charsets.ISO_8859_1)
        require(magic != "bvx2" && magic != "comp") {
            "this IM4P is compressed ($magic): extract it on a PC with `ipsw img4 im4p extract` first"
        }
        return p
    }
}
