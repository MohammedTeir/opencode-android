package io.github.mohammedteir.opencodemobile

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures this app's own startup milestones and uncaught JVM exceptions.
 *
 * Reports stay in app-private storage until the user explicitly shares them.
 */
class OpenCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupDiagnostics.install(this)
        StartupDiagnostics.mark(this, "Application.onCreate completed")
    }
}

object StartupDiagnostics {
    private const val TAG = "OpenCodeStartup"
    private const val DIRECTORY = "diagnostics"
    private const val LOG_FILE = "startup.log"
    private const val MAX_LOG_CHARS = 512 * 1024
    private val installed = AtomicBoolean(false)
    @Volatile private var appContext: Application? = null

    fun install(application: Application) {
        appContext = application
        if (!installed.compareAndSet(false, true)) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(application, thread, throwable) }
                .onFailure { Log.e(TAG, "Could not persist crash report", it) }

            // Preserve Android's normal crash handling and process termination.
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        mark(application, "Uncaught-exception handler installed")
    }

    fun mark(context: android.content.Context, stage: String) {
        val line = "${timestamp()}  STARTUP  $stage"
        append(context, line)
        Log.i(TAG, stage)
    }

    fun shareReport(activity: Activity) {
        val context = activity.applicationContext
        val report = buildString {
            appendLine("OpenCode Android diagnostic report")
            appendLine("Package: ${context.packageName}")
            appendLine("App version: ${runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() + " / " + info.versionName
                else info.versionCode.toString() + " / " + info.versionName
            }.getOrDefault("unknown")}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("----- Startup and crash log -----")
            val file = logFile(context)
            if (file.isFile) append(file.readText()) else appendLine("No diagnostic log has been written yet.")
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OpenCode Android startup diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        activity.startActivity(Intent.createChooser(sendIntent, "Share diagnostic report"))
    }

    private fun recordCrash(application: Application, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        append(
            application,
            buildString {
                appendLine("${timestamp()}  FATAL JVM CRASH")
                appendLine("Process: ${Process.myPid()}")
                appendLine("Thread: ${thread.name} (id=${thread.id})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine(stack)
            }
        )
        Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
    }

    private fun append(context: android.content.Context, text: String) {
        runCatching {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            synchronized(StartupDiagnostics::class.java) {
                if (file.isFile && file.length() > MAX_LOG_CHARS) {
                    val tail = file.readText().takeLast(MAX_LOG_CHARS / 2)
                    file.writeText(tail)
                }
                file.appendText(text + if (text.endsWith("\n")) "" else "\n")
            }
        }.onFailure { Log.e(TAG, "Diagnostic write failed", it) }
    }

    private fun logFile(context: android.content.Context) =
        java.io.File(context.filesDir, "$DIRECTORY/$LOG_FILE")

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
