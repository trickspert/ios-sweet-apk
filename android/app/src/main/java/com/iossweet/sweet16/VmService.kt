package com.iossweet.sweet16

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** What the VM is doing; observed by the activities (main thread). */
object VmState {
    enum class Phase { STOPPED, STARTING, RUNNING, FAILED }

    @Volatile var phase: Phase = Phase.STOPPED
        private set
    @Volatile var message: String = ""
        private set
    @Volatile var startedAt: Long = 0
        private set
    @Volatile var profile: String = Launch.IOS
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun set(p: Phase, msg: String = "", prof: String? = null) {
        phase = p
        message = msg
        if (prof != null) profile = prof
        if (p == Phase.STARTING) startedAt = System.currentTimeMillis()
        main.post { listeners.forEach { it() } }
    }

    fun observe(l: () -> Unit) { listeners += l }
    fun remove(l: () -> Unit) { listeners -= l }
    val active get() = phase == Phase.STARTING || phase == Phase.RUNNING
}

/**
 * Runs QEMU (Termux's qemu-system-aarch64, shipped as libqemu.so in
 * nativeLibraryDir) as a child process in a foreground service.
 *
 * The guest is the sweet-virt machine of the ios-sweet project: QEMU virt,
 * the XNU kernel with the sweet patches, iOS 16 root on virtio-blk, display
 * through ramfb (drawn by the kernel), input through virtio tablet/keyboard.
 * The app talks to QEMU over two unix sockets: VNC (screen and input) and QMP.
 */
class VmService : Service() {
    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVm()
                return START_NOT_STICKY
            }
            else -> startVm(intent?.getStringExtra(EXTRA_PROFILE) ?: Launch.IOS)
        }
        return START_NOT_STICKY
    }

    private fun startVm(profile: String) {
        if (process != null) return
        goForeground()
        VmState.set(VmState.Phase.STARTING, "Building the boot image", profile)
        Thread({ launch(profile) }, "qemu-launch").start()
    }

    private fun launch(profile: String) {
        val store = Store(this)
        try {
            store.installQemuData()
            val qemu = store.qemuBinary()
            if (!qemu.isFile) throw IllegalStateException("QEMU is not in this APK (${qemu.path})")
            val launch = Launch.prepare(store, profile)
            store.vncSocket.delete()
            store.qmpSocket.delete()
            store.serialSocket.delete()
            store.consoleLog.delete()
            store.qemuDebugLog.delete()
            // Exactly what runs, for "Boot details" and bug reports.
            store.lastLaunch.writeText(launch.describe(store))
            val args = launch.args
            Log.i(TAG, "qemu " + args.joinToString(" "))
            val pb = ProcessBuilder(listOf(qemu.path) + args)
                .directory(filesDir)
                .redirectErrorStream(true)
                .redirectOutput(store.qemuLog)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", store.nativeLibDir())
                put("TMPDIR", cacheDir.path)
                put("HOME", filesDir.path)
            }
            store.qemuLog.writeText("")
            val p = pb.start()
            process = p
            acquireWakeLock()
            // Wait for QEMU's VNC socket: then the machine is up.
            val deadline = System.currentTimeMillis() + 30_000
            while (!store.vncSocket.exists() && p.isAliveCompat() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            if (!p.isAliveCompat()) {
                throw IllegalStateException("QEMU exited: " + store.qemuLog.readTail(600))
            }
            VmState.set(VmState.Phase.RUNNING, "QEMU is running (pid ${pidOf(p)})", profile)
            val code = p.waitFor()
            process = null
            if (VmState.phase != VmState.Phase.STOPPED) {
                if (code == 0) VmState.set(VmState.Phase.STOPPED, "The virtual machine stopped")
                else VmState.set(VmState.Phase.FAILED, "QEMU exited ($code): " + store.qemuLog.readTail(400))
            }
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
            process?.destroy()
            process = null
            VmState.set(VmState.Phase.FAILED, e.message ?: e.toString())
        } finally {
            releaseWakeLock()
            Handler(Looper.getMainLooper()).post {
                if (process == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun stopVm() {
        val p = process
        VmState.set(VmState.Phase.STOPPED, "Stopping")
        if (p != null) {
            Thread({
                // Ask QEMU to quit (writes back its disk cache), then make sure.
                qmp(Store(this), """{"execute":"quit"}""")
                if (!p.waitForCompat(5000)) p.destroyForcibly()
                process = null
                VmState.set(VmState.Phase.STOPPED, "The virtual machine stopped")
            }, "qemu-stop").start()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        process?.destroy()
        process = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Virtual machine", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, VmActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, VmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Sweet16 virtual machine is running")
            .setContentText("Tap to open. The virtual machine keeps running in the background.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sweet16:vm").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun pidOf(p: Process): String = try {
        p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.get(p).toString()
    } catch (e: Exception) {
        "?"
    }

    companion object {
        private const val TAG = "Sweet16"
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL = "vm"
        private const val NOTIFICATION_ID = 16
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        fun start(context: Context, profile: String = Launch.IOS) {
            context.startForegroundService(Intent(context, VmService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE, profile))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VmService::class.java).setAction(ACTION_STOP))
        }

        /** One QMP command; returns the raw reply line or null. */
        fun qmp(store: Store, command: String): String? = try {
            LocalSocket().use { s ->
                s.connect(LocalSocketAddress(store.qmpSocket.path, LocalSocketAddress.Namespace.FILESYSTEM))
                s.soTimeout = 3000
                val r = s.inputStream.bufferedReader()
                val w = s.outputStream.bufferedWriter()
                r.readLine() // greeting
                w.write("{\"execute\":\"qmp_capabilities\"}\n"); w.flush()
                r.readLine()
                w.write(command + "\n"); w.flush()
                r.readLine()
            }
        } catch (e: Exception) {
            null
        }
    }
}

fun Process.isAliveCompat(): Boolean = isAlive

fun Process.waitForCompat(ms: Long): Boolean = waitFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

fun File.readTail(max: Int): String = try {
    val t = readText()
    if (t.length > max) t.substring(t.length - max) else t
} catch (e: Exception) {
    ""
}
