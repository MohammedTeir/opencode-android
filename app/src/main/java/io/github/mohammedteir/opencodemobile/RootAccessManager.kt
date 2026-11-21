package io.github.mohammedteir.opencodemobile

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Performs an explicit, user-initiated root availability check.
 *
 * This class does not run arbitrary commands or change device state. The only
 * command executed is the fixed, read-only identity query: `id -u`.
 */
object RootAccessManager {
    data class CheckResult(
        val granted: Boolean,
        val message: String,
        val output: String = ""
    )

    fun checkAccess(timeoutSeconds: Long = 12): CheckResult {
        val process = try {
            ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            return CheckResult(
                granted = false,
                message = "No usable su executable was found. Root access is not available to this app."
            )
        } catch (error: SecurityException) {
            return CheckResult(
                granted = false,
                message = "Android blocked starting the su executable: ${error.message.orEmpty()}"
            )
        }

        return try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                CheckResult(
                    granted = false,
                    message = "The root authorization request timed out or was not answered."
                )
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim().take(300)
                val granted = process.exitValue() == 0 && output.lineSequence().any { it.trim() == "0" }
                if (granted) {
                    CheckResult(
                        granted = true,
                        message = "Root access was granted. No privileged changes were made.",
                        output = output
                    )
                } else {
                    CheckResult(
                        granted = false,
                        message = "Root access was denied or unavailable. OpenCode will continue working without root.",
                        output = output
                    )
                }
            }
        } catch (error: Exception) {
            process.destroyForcibly()
            CheckResult(
                granted = false,
                message = "Unable to verify root access: ${error.message.orEmpty()}"
            )
        }
    }
}
