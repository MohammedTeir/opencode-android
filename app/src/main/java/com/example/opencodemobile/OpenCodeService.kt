package com.example.opencodemobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

class OpenCodeService : Service() {
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val starting = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        val installedVersion = runCatching { File(filesDir, "setup_ready").readText().trim() }.getOrNull()
        val binaryReady = File(filesDir, "rootfs/usr/local/bin/opencode").canExecute()
        if (installedVersion == SetupManager.OPENCODE_VERSION && binaryReady) {
            stopping = false
            if (wakeLock?.isHeld != true) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCodeMobile:server")
                    .apply { setReferenceCounted(false); acquire() }
            }
            if (process?.isAlive != true) startServer()
        } else {
            appendLog("OpenCode V${SetupManager.OPENCODE_VERSION} is not installed; server not started.\n")
            stopSelf()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (!starting.compareAndSet(false, true)) return
        Thread {
            var backoffMs = 1_000L
            try {
                while (!stopping) {
                    try {
                        killStale()
                        val password = SecretStore.getOrCreate(this)
                        val child = ProotRunner.start(
                            this,
                            listOf("/usr/local/bin/opencode", "serve", "--port", "4096", "--hostname", "127.0.0.1"),
                            mapOf("OPENCODE_PASSWORD" to password)
                        )
                        process = child
                        appendLog("OpenCode server process started.\n")
                        if (stopping) child.destroyForcibly()
                        child.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                Log.i(TAG, line)
                                appendLog(line + "\n")
                            }
                        }
                        val code = runCatching { child.waitFor() }.getOrDefault(-1)
                        appendLog("OpenCode server exited with code $code.\n")
                        process = null
                    } catch (t: Throwable) {
                        Log.e(TAG, "Could not start OpenCode", t)
                        appendLog("Server start failed: ${t.message}\n")
                    }
                    if (stopping) break
                    appendLog("Restarting OpenCode in ${backoffMs}ms.\n")
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
            } catch (t: Throwable) {
                if (!stopping) {
                    Log.e(TAG, "Server restart loop failed", t)
                    appendLog("Server restart loop failed: ${t.message}\n")
                }
            } finally {
                process = null
                starting.set(false)
            }
        }.apply { name = "opencode-server" }.start()
    }

    private fun appendLog(line: String) {
        runCatching {
            val file = File(filesDir, "logs/opencode.log")
            file.parentFile?.mkdirs()
            synchronized(file.absolutePath.intern()) {
                file.appendText(line)
                if (file.length() > 2L * 1024 * 1024) {
                    val tail = file.readText().takeLast(1024 * 1024)
                    file.writeText(tail)
                }
            }
        }
    }

    private fun stopServer() {
        stopping = true
        val child = process
        process = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        Thread {
            child?.let {
                runCatching { it.destroy() }
                runCatching { if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly() }
            }
            killStale()
        }.start()
    }

    // A PRoot child can outlive its tracer. On service restart, use procps inside the
    // Debian rootfs to stop only matching OpenCode servers belonging to this app UID.
    // This is best-effort and must be tested on supported Android builds.
    private fun killStale() {
        runCatching {
            val p = ProotRunner.start(this, listOf("/usr/bin/pkill", "-f", "opencode serve"))
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        clearProotTmpNoFollow()
    }

    private fun clearProotTmpNoFollow() {
        val tmp = File(filesDir, "tmp").toPath()
        if (!Files.exists(tmp, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(tmp)) return
        runCatching {
            Files.newDirectoryStream(tmp).use { entries -> entries.forEach { removeTreeNoFollow(it) } }
        }
    }

    private fun removeTreeNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "OpenCode server", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows when the local OpenCode server is running" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OpenCodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("OpenCode server")
            .setContentText("Available on this phone at 127.0.0.1:4096")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.example.opencodemobile.STOP_SERVER"
        private const val TAG = "OpenCodeMobile"
        private const val CHANNEL_ID = "opencode_server"
        private const val NOTIFICATION_ID = 4096
    }
}
