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
