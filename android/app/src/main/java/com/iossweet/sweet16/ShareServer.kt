package com.iossweet.sweet16

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Photos (and other files) shared from Android with iOS, as a tiny web page.
 *
 * QEMU's user networking makes the guest address 10.0.2.2 a connection to this
 * phone's own 127.0.0.1, so iOS Safari opens http://10.0.2.2:[PORT] and shows
 * the files in [dir]; touch and hold a photo -> Add to Photos. No internet is
 * needed. Only loopback is bound, and only while iOS runs (VmService).
 *
 * No Android dependencies: tested on the JVM (ShareServerTest).
 */
class ShareServer(
    private val dir: File,
    private val port: Int = PORT,
    /** Notes of the /notes web app (NotesPage), saved here as JSON. */
    private val notesFile: File = File(dir.parentFile ?: dir, "notes.json"),
) {
    @Volatile private var server: ServerSocket? = null

    /** The port actually bound (useful with port 0 in tests). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        if (server != null) return
        val s = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                Thread({ c.use { handle(it) } }, "share-client").start()
            }
        }, "share-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val inp = BufferedInputStream(c.getInputStream())
        val request = readLine(inp) ?: return
        var length = 0
        while (true) {
            val h = readLine(inp) ?: return
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val parts = request.split(' ')
        val out = c.getOutputStream()
        if (parts.size >= 2 && parts[0] == "POST" && parts[1] == "/api/notes") {
            saveNotes(inp, length, out)
            return
        }
        if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
            reply(out, 405, "text/plain", "method not allowed\n".toByteArray(), parts.getOrNull(0) == "HEAD")
            return
        }
        val head = parts[0] == "HEAD"
        val path = parts[1].substringBefore('?')
        when {
            path == "/" || path == "/index.html" -> reply(out, 200, "text/html; charset=utf-8", index().toByteArray(), head)
            path == "/notes" || path == "/notes/" -> reply(out, 200, "text/html; charset=utf-8", NotesPage.HTML.toByteArray(), head)
            path == "/notes-icon.png" || path == "/apple-touch-icon.png" -> reply(out, 200, "image/png", NotesPage.ICON, head)
            path == "/api/notes" -> {
                val body = synchronized(this) { if (notesFile.isFile) notesFile.readBytes() else "[]".toByteArray() }
                reply(out, 200, "application/json", body, head)
            }
            path.startsWith("/f/") -> {
                val name = URLDecoder.decode(path.removePrefix("/f/"), "UTF-8")
                val f = File(dir, name)
                if (name.isEmpty() || name.contains('/') || name.startsWith(".") || !f.isFile) {
                    reply(out, 404, "text/plain", "not found\n".toByteArray(), head)
                } else {
                    sendFile(out, f, head)
                }
            }
            else -> reply(out, 404, "text/plain", "not found\n".toByteArray(), head)
        }
    }

    private fun saveNotes(inp: BufferedInputStream, length: Int, out: OutputStream) {
        if (length <= 0 || length > MAX_NOTES) {
            reply(out, 413, "text/plain", "too large\n".toByteArray(), false)
            return
        }
        val body = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = inp.read(body, n, length - n)
            if (r < 0) return
            n += r
        }
        val text = String(body, Charsets.UTF_8).trim()
        if (!text.startsWith("[") || !text.endsWith("]")) {
            reply(out, 400, "text/plain", "expected a JSON array\n".toByteArray(), false)
            return
        }
        synchronized(this) {
            notesFile.parentFile?.mkdirs()
            val tmp = File(notesFile.path + ".tmp")
            tmp.writeBytes(body)
            tmp.renameTo(notesFile)
        }
        reply(out, 200, "application/json", "{\"ok\":true}".toByteArray(), false)
    }

    private fun files(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }

    private fun index(): String = buildString {
        val list = files()
        append("<!doctype html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>Photos from Android</title><style>")
        append("body{font-family:-apple-system,sans-serif;margin:0;padding:16px;background:#111;color:#eee}")
        append("h1{font-size:24px;margin:8px 0}p{color:#aaa;line-height:1.4}")
        append(".g{display:grid;grid-template-columns:1fr 1fr;gap:8px}")
        append(".g a{display:block}.g img{width:100%;border-radius:8px;display:block}")
        append(".f{padding:12px;border-radius:8px;background:#222;color:#9cf;word-break:break-all}")
        append(".card{display:block;padding:14px 16px;margin:8px 0 18px;border-radius:12px;background:#2a2410;color:#ffd24d;text-decoration:none}")
        append("</style></head><body>")
        append("<a class=\"card\" href=\"/notes\"><b>Notes</b><br><span style=\"color:#ccc\">Open, then Share &rarr; Add to Home Screen for a Notes app icon.</span></a>")
        append("<h1>Photos from Android</h1>")
        if (list.isEmpty()) {
            append("<p>Nothing shared yet. In Sweet16 on Android: &#8942; &rarr; Share photos with iOS&hellip;, then reload this page.</p>")
        } else {
            append("<p>Touch and hold a photo, then <b>Add to Photos</b>. For a wallpaper: Settings &rarr; Wallpaper.</p><div class=\"g\">")
            for (f in list) {
                val url = "/f/" + java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
                if (contentType(f.name).startsWith("image/")) {
                    append("<a href=\"").append(url).append("\"><img src=\"").append(url).append("\" alt=\"\"></a>")
                } else {
                    append("<a class=\"f\" href=\"").append(url).append("\">").append(escape(f.name)).append("</a>")
                }
            }
            append("</div>")
        }
        append("</body></html>")
    }

    private fun sendFile(out: OutputStream, f: File, head: Boolean) {
        val hdr = "HTTP/1.0 200 OK\r\nContent-Type: ${contentType(f.name)}\r\nContent-Length: ${f.length()}\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray())
        if (!head) f.inputStream().use { it.copyTo(out, 1 shl 16) }
        out.flush()
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray, head: Boolean) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Method Not Allowed" }
        out.write("HTTP/1.0 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
        if (!head) out.write(body)
        out.flush()
    }

    private fun readLine(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("request line too long")
            sb.append(b.toChar())
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        /** Seen from iOS as http://10.0.2.2:16016 (QEMU maps 10.0.2.2 to this phone's loopback). */
        const val PORT = 16016
        const val IOS_URL = "http://10.0.2.2:16016"
        const val NOTES_URL = "http://10.0.2.2:16016/notes"
        private const val MAX_NOTES = 4 shl 20

        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
