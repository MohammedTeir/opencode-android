package io.github.mohammedteir.opencodemobile

import java.io.File
import java.io.IOException
import java.util.UUID
import java.nio.file.Files
import java.nio.file.Path
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

    private const val PREFS_NAME = "root_mode"
    private const val PREF_ROOT_HARDLINKS_ENABLED = "root_hardlinks_enabled"
    private const val PREF_ROOT_HARDLINKS_VERIFIED = "root_hardlinks_verified"

    fun isRootHardLinksVerified(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, 0).getBoolean(PREF_ROOT_HARDLINKS_VERIFIED, false)

    fun isRootHardLinksEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, 0).getBoolean(PREF_ROOT_HARDLINKS_ENABLED, false) &&
            isRootHardLinksVerified(context)

    fun setRootHardLinksEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, 0).edit()
            .putBoolean(PREF_ROOT_HARDLINKS_ENABLED, enabled)
            .apply()
    }

    fun setRootHardLinksVerified(context: android.content.Context, verified: Boolean) {
        val preferences = context.getSharedPreferences(PREFS_NAME, 0)
        preferences.edit()
            .putBoolean(PREF_ROOT_HARDLINKS_VERIFIED, verified)
            .putBoolean(PREF_ROOT_HARDLINKS_ENABLED, if (verified) preferences.getBoolean(PREF_ROOT_HARDLINKS_ENABLED, false) else false)
            .apply()
    }

    /**
     * Create a hard link only between two validated paths inside the current
     * private staging root. This is the sole privileged filesystem mutation
     * exposed by the root gateway.
     */
    fun createHardLink(stagingRoot: File, target: Path, link: Path, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OperationResult {
        val root = stagingRoot.canonicalFile.toPath()
        val targetFile = target.toFile().canonicalFile
        val linkFile = link.toFile().canonicalFile
        require(targetFile.toPath().startsWith(root) && linkFile.toPath().startsWith(root)) {
            "Refusing a privileged hard link outside the app's staging directory."
        }
        require(targetFile.isFile && !Files.exists(linkFile.toPath())) {
            "The hard-link target must be a regular file and the destination must not exist."
        }
        require(linkFile.parentFile?.isDirectory == true) {
            "The hard-link destination directory does not exist."
        }
        val targetArg = shellQuote(targetFile.absolutePath)
        val linkArg = shellQuote(linkFile.absolutePath)
        val script = """
            set -u
            if ! ln $targetArg $linkArg; then
                exit 1
            fi
            target_inode=$(stat -c %i $targetArg 2>/dev/null) || { rm -f $linkArg; exit 2; }
            link_inode=$(stat -c %i $linkArg 2>/dev/null) || { rm -f $linkArg; exit 3; }
            if [ "$target_inode" != "$link_inode" ]; then
                rm -f $linkArg
                exit 4
            fi
            echo ROOT_HARDLINK_CREATED
        """.trimIndent()
        return executeFixed("Create Debian hard link with root", script, timeoutSeconds) { output, exitCode ->
            val created = exitCode == 0 && output.contains("ROOT_HARDLINK_CREATED")
            OperationResult(
                success = created,
                title = "Root-assisted hard-link creation",
                message = if (created) "Native hard link created and inode-verified." else "Root could not create or verify this hard link (exit $exitCode).",
                output = output
            )
        }
    }

    /**
     * Read-only diagnostics of the app-private cache filesystem and Android
     * security state. The path is canonicalized and shell-quoted internally.
     */
    fun runDiagnostics(appPrivateDirectory: File, rootfsDirectory: File, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OperationResult {
        val directory = validatedPrivateDirectory(appPrivateDirectory)
        val path = shellQuote(directory.absolutePath)
        val rootfs = rootfsDirectory.canonicalFile
        val expectedFilesDirectory = File(directory.parentFile, "files").canonicalFile
        require(rootfs.parentFile == expectedFilesDirectory) {
            "Refusing to inspect a rootfs outside this app's private files directory."
        }
        val rootfsPath = shellQuote(rootfs.absolutePath)
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
            echo '=== Debian rootfs ==='
            if [ -d $rootfsPath ]; then
                ls -ld $rootfsPath
                du -sh $rootfsPath 2>&1 || true
            else
                echo 'Debian rootfs is not installed.'
            fi
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
            mkdir "${'$'}probe"
            trap "rm -rf $probePath" EXIT
            printf 'opencode-root-hardlink-probe' > $source
            ln $source $link
            source_inode=$(stat -c %i $source)
            link_inode=$(stat -c %i $link)
            if [ "${'$'}source_inode" != "${'$'}link_inode" ]; then
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
        // cacheDir and filesDir must be siblings inside this application's private data directory.
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
