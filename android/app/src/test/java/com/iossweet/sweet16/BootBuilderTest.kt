package com.iossweet.sweet16

import com.iossweet.sweet16.boot.Adt
import com.iossweet.sweet16.boot.BootBuilder
import com.iossweet.sweet16.boot.VirtAdt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The app's boot builder must produce exactly what the repository's Python
 * tools produce (mkadt.py --virt, mkpayload.py, mkvirtbundle.py): same
 * ram.bin, same stub.bin, same ADT. Needs python3 and the repo's tools/.
 */
class BootBuilderTest {
    private val repo = File("../..").canonicalFile
    private val tools = File(repo, "tools")
    private val seedHex = "ab".repeat(64)
    private val cmdline = "-v debug=0xc serial=3 rd=md0 sweetfb_scale=2"

    private fun py(vararg args: String, dir: File) {
        val p = ProcessBuilder(listOf("python3") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "python3 ${args.joinToString(" ")}: $out" }
    }

    @Test
    fun matchesPythonTools() {
        assumeTrue("repository tools not found", File(tools, "mkpayload.py").isFile)
        assumeTrue("python3 not available", try { ProcessBuilder("python3", "-V").start().waitFor() == 0 } catch (e: Exception) { false })
        val d = Files.createTempDirectory("bootbuilder").toFile()
        try {
            py(File(repo, "android/app/src/test/python/mkfakekernel.py").path, "k.macho", "apple.adt", dir = d)
            File(d, "rd.img").writeBytes(ByteArray(5000) { (it * 7 + 3).toByte() })
            py(File(tools, "mkadt.py").path, "v.adt", "--virt", "--ram-mb", "4096", "--seed", seedHex, "--product", "apple.adt", dir = d)
            py(File(tools, "mkpayload.py").path, "k.macho", "v.adt", "p.bin", "--ramdisk", "rd.img", "--cmdline", cmdline, dir = d)
            py(File(tools, "mkvirtbundle.py").path, "p.bin", "py", dir = d)

            val seed = ByteArray(64) { 0xab.toByte() }
            val apple = File(d, "apple.adt").readBytes()
            // The ADT alone (before the RAM disk entry).
            val ktAdt = Adt.serialize(VirtAdt.build(4096, VirtAdt.productOf(Adt.load(apple)), seed))
            assertArrayEquals("ADT", File(d, "v.adt").readBytes(), ktAdt)

            val img = BootBuilder.build(
                BootBuilder.Plan(File(d, "k.macho").readBytes(), 4096, cmdline,
                    appleAdt = apple, ramdisk = File(d, "rd.img"), seed = seed),
                File(tools, "virt-stub/stub.bin").readBytes(), File(d, "kt"))
            println(img.summary)
            assertEquals(0xb0000000L, img.physBase)
            assertArrayEquals("stub.bin", File(d, "py/stub.bin").readBytes(), img.stubBin.readBytes())
            assertArrayEquals("ram.bin", File(d, "py/ram.bin").readBytes(), img.ramBin.readBytes())
        } finally {
            d.deleteRecursively()
        }
    }
}
