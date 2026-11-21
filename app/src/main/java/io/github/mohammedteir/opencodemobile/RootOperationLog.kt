package io.github.mohammedteir.opencodemobile

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small bounded audit log for user-requested root operations. */
object RootOperationLog {
    private const val FILE_NAME = "root-operations.log"
    private const val MAX_BYTES = 128 * 1024
    private const val DISPLAY_BYTES = 12 * 1024

    @Synchronized
    fun append(context: Context, operation: String, success: Boolean, details: String) {
        val file = File(context.filesDir, FILE_NAME)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sanitized = Regex("(?i)(password|token|authorization)=\\S+")
            .replace(details) { match -> match.groupValues[1] + "=[redacted]" }
            .take(8_000)
        file.appendText(
            "[$timestamp] ${if (success) "SUCCESS" else "FAILED"} · $operation\n" +
                sanitized + "\n\n"
        )
        if (file.length() > MAX_BYTES) {
            val tail = file.readText().takeLast(DISPLAY_BYTES)
            file.writeText(tail)
        }
    }

    @Synchronized
    fun read(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return ""
        return runCatching { file.readText().takeLast(DISPLAY_BYTES) }.getOrDefault("")
    }
}
