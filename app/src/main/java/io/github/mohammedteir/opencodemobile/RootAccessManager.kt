package io.github.mohammedteir.opencodemobile

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Narrow root-operation gateway.
 *
 * Deliberately exposes fixed operations only; callers cannot submit arbitrary
 * shell commands. Every operation is user-initiated and independently requests
 * authorization through the installed root manager.
 */
object RootAccessManager {
    data class OperationResult(
        val success: Boolean,
        val title: String,
        val message: String,
        val output: String = ""
    )

    fun checkAccess(timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OperationResult =
        executeFixed("Root authorization check", "id -u", timeoutSeconds) { output, exitCode ->
            val granted = exitCode == 0 && output.lineSequence().any { it.trim() == "0" }
            OperationResult(
                success = granted,
                title = if (granted) "Root access available" else "Root access unavailable",
                message = if (granted) {
                    "The root manager granted this app UID 0. No system settings were changed."
                } else {
                    "Root authorization was denied or is unavailable. Standard PRoot mode remains available."
                },
                output = output
            )
        }

    /**
     * Read-only diagnostics of the app-private cache filesystem and Android
     * security state. The path is canonicalized and shell-quoted internally.
     */
    fun runDiagnostics(appPrivateDirectory: File, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OperationResult {
        val directory = validatedPrivateDirectory(appPrivateDirectory)
        val path = shellQuote(directory.absolutePath)
        val script = """
            set -u
            echo '=== UID ==='
            id
            echo '=== SELinux ==='
            if command -v getenforce >/dev/null 2>&1; then getenforce; else echo 'getenforce unavailable'; fi
            echo '=== App-private directory ==='
            ls -ld $path
            echo '=== Filesystem capacity ==='
            df -h $path
            echo '=== Filesystem details ==='
            stat -f $path 2>&1 || true
        """.trimIndent()
        return executeFixed("Root filesystem diagnostics", script, timeoutSeconds) { output, exitCode ->
            OperationResult(
                success = exitCode == 0,
                title = "Root filesystem diagnostics",
                message = if (exitCode == 0) "Diagnostics completed. No files were modified."
                    else "Diagnostics did not complete successfully (exit $exitCode).",
                output = output
            )
        }
    }

    /**
     * Tests whether root can create and verify a hard link inside the app-private
     * cache filesystem. All probe files are created in a unique temporary
     * directory and removed by a shell trap on success or failure.
     */
    fun testHardLinks(appPrivateDirectory: File, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OperationResult {
        val directory = validatedPrivateDirectory(appPrivateDirectory)
        val parent = shellQuote(directory.absolutePath)
        val probeName = ".opencode-root-link-probe-" + UUID.randomUUID().toString().replace("-", "")
        val probePath = shellQuote(File(directory, probeName).absolutePath)
        val source = shellQuote(File(File(directory, probeName), "source").absolutePath)
        val link = shellQuote(File(File(directory, probeName), "link").absolutePath)
        val script = """
            set -eu
            probe=$probePath
            mkdir "$probe"
            trap 'rm -f $source $link; rmdir "$probe" 2>/dev/null || true' EXIT
            printf 'opencode-root-hardlink-probe' > $source
            ln $source $link
            source_inode=$(stat -c %i $source)
            link_inode=$(stat -c %i $link)
            if [ "$source_inode" != "$link_inode" ]; then
                echo 'Hard-link verification failed: inode mismatch.'
                exit 20
            fi
            echo 'HARD_LINK_SUPPORTED'
            echo "Filesystem: $(stat -f -c %T $parent 2>/dev/null || echo unknown)"
            echo "Device/inode: $(stat -c %d:%i $source)"
        """.trimIndent()
        return executeFixed("Root hard-link capability test", script, timeoutSeconds) { output, exitCode ->
            val supported = exitCode == 0 && output.contains("HARD_LINK_SUPPORTED")
            OperationResult(
                success = supported,
                title = "Root hard-link capability test",
                message = when {
                    supported -> "Root successfully created and verified a native hard link on the app-private filesystem."
                    exitCode == 0 -> "The probe completed but did not verify a native hard link."
                    else -> "Native hard-link creation was not verified (exit $exitCode). Keep the copy fallback enabled."
                },
                output = output
            )
        }
    }

    private fun validatedPrivateDirectory(directory: File): File {
        val canonical = directory.canonicalFile
        require(canonical.isDirectory && canonical.canRead()) {
            "The app-private diagnostic directory is unavailable."
        }
        val siblingFiles = File(directory.parentFile, "files").canonicalFile
        // cacheDir is normally a sibling of filesDir under the app's private data
        // directory. This prevents accidentally probing arbitrary user paths.
        require(canonical.parentFile == siblingFiles.parentFile) {
            "Refusing to run a root filesystem probe outside this app's private data directory."
        }
        return canonical
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private inline fun executeFixed(
        title: String,
        command: String,
        timeoutSeconds: Long,
        classify: (String, Int) -> OperationResult
    ): OperationResult {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: IOException) {
            return OperationResult(false, title, "No usable su executable was found: ${error.message.orEmpty()}")
        } catch (error: SecurityException) {
            return OperationResult(false, title, "Android blocked starting su: ${error.message.orEmpty()}")
        }

        val reader = FutureTask {
            process.inputStream.bufferedReader().use { it.readText() }.take(MAX_OUTPUT_CHARS)
        }
        Thread(reader, "root-operation-output").apply { isDaemon = true; start() }

        return try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.cancel(true)
                OperationResult(false, title, "Root operation timed out after $timeoutSeconds seconds.")
            } else {
                val output = runCatching { reader.get(2, TimeUnit.SECONDS) }
                    .getOrElse { "Could not collect complete command output: ${it.message.orEmpty()}" }
                classify(output.trim(), process.exitValue())
            }
        } catch (error: Exception) {
            process.destroyForcibly()
            reader.cancel(true)
            OperationResult(false, title, "Root operation failed: ${error.message.orEmpty()}")
        }
    }

    private const val DEFAULT_TIMEOUT_SECONDS = 15L
    private const val MAX_OUTPUT_CHARS = 12_000
}
