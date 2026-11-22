package io.github.mohammedteir.opencodemobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges OpenCode's authenticated server event stream into native Android notifications.
 *
 * The server owns the event lifecycle, so this remains active while the WebView is backgrounded
 * and does not depend on WebView JavaScript notification support.
 */
class OpenCodeEventNotifications(
    context: Context,
    private val password: String
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var activeCall: Call? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        createChannel()
        worker = Thread(::listenLoop, "opencode-android-events").apply {
            isDaemon = true
            start()
        }
    }

    private fun listenLoop() {
        var retryMs = 1_000L
        while (running.get()) {
            try {
                val request = Request.Builder()
                    .url("http://127.0.0.1:4096/api/event")
                    .header("Authorization", Credentials.basic("opencode", password))
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .build()
                val call = client.newCall(request)
                activeCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("OpenCode event stream returned HTTP ${response.code}")
                    }
                    val reader = response.body?.charStream()?.buffered()
                        ?: throw IOException("OpenCode event stream returned an empty body")
                    retryMs = 1_000L
                    Log.i(TAG, "Connected to OpenCode event stream")
                    var payload = StringBuilder()
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("data:") -> {
                                if (payload.isNotEmpty()) payload.append('\n')
                                payload.append(line.substringAfter("data:").trimStart())
                            }
                            line.isBlank() -> {
                                if (payload.isNotEmpty()) {
                                    handleEvent(payload.toString())
                                    payload = StringBuilder()
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (running.get()) Log.w(TAG, "OpenCode event stream disconnected: ${error.message}")
            } finally {
                activeCall = null
            }
            if (!running.get()) break
            try {
                Thread.sleep(retryMs)
            } catch (_: InterruptedException) {
                if (!running.get()) break
            }
            retryMs = (retryMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun handleEvent(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        val sessionID = data.optString("sessionID").ifBlank {
            data.optJSONObject("form")?.optString("sessionID").orEmpty()
        }
        if (sessionID.isBlank()) {
            Log.d(TAG, "Ignored event without session ID: $type")
            return
        }

        val details = when (type) {
            "session.execution.succeeded" ->
                "Your OpenCode task has finished."
            "session.execution.failed" ->
                "An OpenCode task encountered an error."
            "permission.asked" ->
                "OpenCode is waiting for permission."
            "form.created" -> {
                val form = data.optJSONObject("form") ?: return
                val kind = form.optJSONObject("metadata")?.optString("kind").orEmpty()
                if (kind !in setOf("question", "mcp-elicitation", "websearch.provider")) return
                if (kind == "websearch.provider") "OpenCode needs provider authorization."
                else "OpenCode is waiting for your answer."
            }
            else -> return
        }

        val eventID = event.optString("id").ifBlank { "$type:$sessionID:${System.currentTimeMillis()}" }
        Log.i(TAG, "Received activity event: $type for session ${sessionID.take(12)}")
        postNotification(eventID, sessionID, type, details)
    }

    private fun postNotification(eventID: String, sessionID: String, type: String, details: String) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Notification permission is not granted; skipped event $type")
            return
        }

        val open = PendingIntent.getActivity(
            appContext,
            eventID.hashCode(),
            Intent(appContext, MainActivity::class.java)
                .setAction(ACTION_OPEN_WORKSPACE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (type) {
            "session.execution.succeeded" -> "OpenCode · Task completed"
            "session.execution.failed" -> "OpenCode · Task failed"
            else -> "OpenCode · Action required"
        }
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "App notifications are disabled in Android settings")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            Log.w(TAG, "OpenCode activity notification channel is disabled")
            return
        }
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle(title)
            .setContentText(details)
            .setStyle(Notification.BigTextStyle().bigText("$details\nSession: ${sessionID.take(12)}"))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        val notificationKey = "$type:$sessionID"
        manager.notify("opencode-$notificationKey", NOTIFICATION_ID_BASE + (notificationKey.hashCode() and 0x3fffffff), notification)
        Log.i(TAG, "Posted Android activity notification: $type")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OpenCode activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Task completions, errors, questions, and permission requests from OpenCode"
                }
            )
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        activeCall?.cancel()
        worker?.interrupt()
        worker = null
        client.dispatcher.cancelAll()
    }

    companion object {
        const val ACTION_OPEN_WORKSPACE = "io.github.mohammedteir.opencodemobile.OPEN_WORKSPACE_FROM_NOTIFICATION"
        private const val TAG = "OpenCodeEvents"
        private const val CHANNEL_ID = "opencode_activity"
        private const val NOTIFICATION_ID_BASE = 20_000
    }
}
