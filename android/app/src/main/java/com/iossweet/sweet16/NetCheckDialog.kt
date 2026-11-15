package com.iossweet.sweet16

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView

/** Shows NetCheck's report (kernel milestones + a DNS test from this phone). */
object NetCheckDialog {
    fun show(activity: Activity, store: Store) {
        Thread({
            val console = try {
                java.io.RandomAccessFile(store.consoleLog, "r").use { r ->
                    val len = r.length()
                    // The SWEET NET lines are early and sparse: read the whole log (it is a few MB at most).
                    val b = ByteArray(minOf(len, 64L shl 20).toInt())
                    r.seek(len - b.size)
                    r.readFully(b)
                    String(b, Charsets.UTF_8)
                }
            } catch (e: Exception) { "" }
            val servers = try {
                store.dnsFile.readLines().mapNotNull { l -> l.trim().takeIf { it.startsWith("nameserver ") }?.substringAfter(' ')?.trim() }
            } catch (e: Exception) { emptyList() }
            val probe = servers.firstOrNull { ':' !in it }?.let { NetCheck.probeDns(it) } ?: "no IPv4 nameserver written yet (start iOS first)"
            val text = (if (!VmState.active) "iOS is not running: this is from the last run.\n\n" else "") +
                NetCheck.report(console, store.network, servers, probe) +
                "\nPhotos and Notes for iOS: ${ShareServer.IOS_URL}"
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val tv = TextView(activity).apply {
                    this.text = text; textSize = 13f; setTextIsSelectable(true); setPadding(40, 24, 40, 24)
                }
                AlertDialog.Builder(activity).setTitle("Internet check")
                    .setView(ScrollView(activity).apply { addView(tv) })
                    .setPositiveButton("Copy") { _, _ ->
                        activity.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Sweet16 internet check", text))
                    }
                    .setNegativeButton("Close", null).show()
            }
        }, "net-check").start()
    }
}
