package com.iossweet.sweet16

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class ShareServerTest {
    private fun get(port: Int, path: String): Pair<Int, ByteArray> {
        val c = URL("http://127.0.0.1:$port$path").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).use { it.readBytes() }
        return code to body
    }

    @Test
    fun servesIndexAndFiles() {
        val dir = Files.createTempDirectory("share").toFile()
        val photo = ByteArray(300_000) { (it * 7).toByte() }
        File(dir, "photo 1.jpg").writeBytes(photo)
        File(dir, ".hidden").writeText("x")
        val s = ShareServer(dir, 0)
        s.start()
        try {
            val (code, index) = get(s.boundPort, "/")
            assertEquals(200, code)
            val html = String(index)
            assertTrue(html, html.contains("<img src=\"/f/photo%201.jpg\""))
            assertTrue(!html.contains(".hidden"))
            val (fc, body) = get(s.boundPort, "/f/photo%201.jpg")
            assertEquals(200, fc)
            assertArrayEquals(photo, body)
            assertEquals(404, get(s.boundPort, "/f/..%2Fetc%2Fpasswd").first)
            assertEquals(404, get(s.boundPort, "/f/.hidden").first)
            assertEquals(404, get(s.boundPort, "/nothing").first)
        } finally {
            s.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun notesAppSavesAndLoads() {
        val root = Files.createTempDirectory("share").toFile()
        val dir = File(root, "share").apply { mkdirs() }
        val s = ShareServer(dir, 0)
        s.start()
        try {
            val (pc, page) = get(s.boundPort, "/notes")
            assertEquals(200, pc)
            assertTrue(String(page).contains("apple-mobile-web-app-capable"))
            val (ic, icon) = get(s.boundPort, "/notes-icon.png")
            assertEquals(200, ic)
            assertEquals(0x89.toByte(), icon[0])
            assertEquals("[]", String(get(s.boundPort, "/api/notes").second))
            val json = "[{\"id\":\"a1\",\"text\":\"Hello \u00e9\",\"updated\":1}]"
            val c = URL("http://127.0.0.1:${s.boundPort}/api/notes").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
            assertEquals(200, c.responseCode)
            assertEquals(json, String(get(s.boundPort, "/api/notes").second))
            assertEquals(json, File(root, "notes.json").readText())
        } finally {
            s.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyDirectoryExplainsWhatToDo() {
        val dir = Files.createTempDirectory("share").toFile()
        val s = ShareServer(dir, 0)
        s.start()
        try {
            val (code, index) = get(s.boundPort, "/")
            assertEquals(200, code)
            assertTrue(String(index).contains("Share photos with iOS"))
        } finally {
            s.stop()
            dir.deleteRecursively()
        }
    }
}
