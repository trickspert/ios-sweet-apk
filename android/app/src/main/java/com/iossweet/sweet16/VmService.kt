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
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        if (process != null || launching) return
        launching = true
        val store = Store(this)
        val log = LaunchLog(store.launchLog)
        log.line("service started: profile=$profile, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, ABIs ${Build.SUPPORTED_ABIS.joinToString(",")}")
        try {
            goForeground()
            log.line("foreground service: ok")
        } catch (t: Throwable) {
            // Keep going without it: QEMU may still run while the app is open.
            log.error("foreground service refused (VM may be stopped in the background)", t)
        }
        VmState.set(VmState.Phase.STARTING, "Building the boot image", profile)
        Thread({ launch(profile, store, log) }, "qemu-launch").start()
    }

    @Volatile private var launching = false

    private fun launch(profile: String, store: Store, log: LaunchLog) {
        try {
            store.installQemuData()
            val libDir = File(store.nativeLibDir())
            val qemu = store.qemuBinary()
            log.line("nativeLibraryDir: ${libDir.path} (${libDir.list()?.size ?: -1} files)")
            log.line("libqemu.so: exists=${qemu.isFile} size=${qemu.length()} canExecute=${qemu.canExecute()}")
            if (!qemu.isFile) throw IllegalStateException("QEMU is not in this APK (${qemu.path}); is extractNativeLibs on?")
            log.line("library name links: ${store.prepareQemuLibLinks()} in ${store.qemuLibLinks.path}; " +
                "LD_LIBRARY_PATH=${store.qemuLibraryPath()}")

            // Pre-flight: does the binary load at all (dynamic linker, libraries)?
            VmState.set(VmState.Phase.STARTING, "Checking QEMU", profile)
            val pre = runCaptured(store, listOf(qemu.path, "--version"), 15_000)
            log.line("pre-flight `libqemu.so --version`: exit=${pre.first}${signalName(pre.first)}\n" +
                pre.second.trim().prependIndent("    "))
            if (pre.first != 0) throw IllegalStateException("QEMU does not start on this phone (exit ${pre.first}):\n" + pre.second.trim())

            VmState.set(VmState.Phase.STARTING, "Building the boot image", profile)
            val t0 = System.currentTimeMillis()
            val launch = Launch.prepare(store, profile)
            log.line("boot image built in ${System.currentTimeMillis() - t0} ms:\n" + launch.image.summary.trim().prependIndent("    "))
            store.vncSocket.delete()
            store.qmpSocket.delete()
            store.serialSocket.delete()
            store.consoleLog.delete()
            store.qemuDebugLog.delete()
            // Exactly what runs, for "Boot details" and bug reports.
            store.lastLaunch.writeText(launch.describe(store))
            val args = launch.args
            log.line("command:\n" + launch.commandLine(store).prependIndent("    "))
            Log.i(TAG, "qemu " + args.joinToString(" "))
            val pb = ProcessBuilder(listOf(qemu.path) + args)
                .directory(filesDir)
                .redirectErrorStream(true)
                .redirectOutput(store.qemuLog)
            env(pb, store)
            store.qemuLog.writeText("")
            VmState.set(VmState.Phase.STARTING, "Starting QEMU", profile)
            val p = pb.start()
            process = p
            log.line("QEMU started, pid ${pidOf(p)}")
            acquireWakeLock()
            // Wait for QEMU's VNC socket: then the machine is up.
            val deadline = System.currentTimeMillis() + 30_000
            while (!store.vncSocket.exists() && p.isAliveCompat() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            if (!p.isAliveCompat()) {
                throw IllegalStateException("QEMU exited (${p.exitValue()}): " + store.qemuLog.readTail(1500))
            }
            log.line("VNC socket ${if (store.vncSocket.exists()) "up" else "not up after 30 s"}; machine running")
            VmState.set(VmState.Phase.RUNNING, "QEMU is running (pid ${pidOf(p)})", profile)
            val code = p.waitFor()
            process = null
            log.line("QEMU exited with $code")
            if (VmState.phase != VmState.Phase.STOPPED) {
                if (code == 0) VmState.set(VmState.Phase.STOPPED, "The virtual machine stopped")
                else VmState.set(VmState.Phase.FAILED, "QEMU exited ($code): " + store.qemuLog.readTail(400))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "launch failed", t)
            log.error("launch failed", t)
            process?.destroy()
            process = null
            VmState.set(VmState.Phase.FAILED, t.message ?: t.toString())
        } finally {
            launching = false
            releaseWakeLock()
            Handler(Looper.getMainLooper()).post {
                if (process == null) {
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
                    stopSelf()
                }
            }
        }
    }

    /** exit 128+N = killed by signal N (139 = SIGSEGV). */
    private fun signalName(code: Int): String = when (code) {
        134 -> " (SIGABRT)"; 135 -> " (SIGBUS)"; 137 -> " (SIGKILL)"; 139 -> " (SIGSEGV)"; 159 -> " (SIGSYS: seccomp)"
        else -> if (code > 128) " (signal ${code - 128})" else ""
    }

    private fun env(pb: ProcessBuilder, store: Store) {
        pb.environment().apply {
            put("LD_LIBRARY_PATH", store.qemuLibraryPath())
            put("TMPDIR", cacheDir.path)
            put("HOME", filesDir.path)
        }
    }

    /** Run a short command; returns (exit code, combined output). */
    private fun runCaptured(store: Store, cmd: List<String>, timeoutMs: Long): Pair<Int, String> {
        val pb = ProcessBuilder(cmd).directory(filesDir).redirectErrorStream(true)
        env(pb, store)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            return -1 to "cannot execute ${cmd[0]}: $e"
        }
        val out = StringBuilder()
        val reader = Thread { try { out.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }
        reader.start()
        if (!p.waitForCompat(timeoutMs)) {
            p.destroyForcibly()
            reader.join(1000)
            return -2 to "timed out after $timeoutMs ms\n$out"
        }
        reader.join(2000)
        return p.exitValue() to out.toString()
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

/** Step-by-step record of one launch (files/launch.log), shown on the Console screen. */
class LaunchLog(private val file: File) {
    init {
        file.writeText("")
    }

    @Synchronized
    fun line(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        file.appendText("[$ts] $msg\n")
    }

    fun error(msg: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        line("ERROR: $msg: $t\n" + sw.toString().prependIndent("    "))
    }
}
