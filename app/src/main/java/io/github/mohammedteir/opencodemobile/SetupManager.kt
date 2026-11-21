package io.github.mohammedteir.opencodemobile

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class SetupManager(private val context: Context) {
    data class OpenCodeUpdate(val version: List<Int>, val url: String, val sha256: String) {
        val displayVersion: String get() = version.joinToString(".")
    }

    private val rootfs = File(context.filesDir, "rootfs")
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun hasRootfs() = File(rootfs, "bin/bash").exists()
    fun hasOpenCodeBinary() = File(rootfs, "usr/local/bin/opencode").canExecute()
    fun installedVersion(): String? = File(context.filesDir, "setup_ready").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    fun isReady() = hasOpenCodeBinary() && runCatching {
        installedVersion() != null
    }.getOrDefault(false)
    fun isNewerThanInstalled(update: OpenCodeUpdate): Boolean {
        val current = installedVersion()?.let(::parseVersion) ?: return false
        return compareVersions(update.version, current) > 0
    }

    fun deletePreviousBinary(): Boolean = File(rootfs, "usr/local/bin/opencode.previous").delete()

    suspend fun checkForUpdate(): OpenCodeUpdate = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OpenCode-Mobile")
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Update check failed: HTTP ${response.code}" }
            val releases = JSONArray(response.body?.string().orEmpty())
            // Pin updates to the major version bundled by this app. A deliberate
            // bootstrap upgrade changes OPENCODE_VERSION and therefore the update channel.
            val supportedMajor = parseVersion(OPENCODE_VERSION)?.firstOrNull()
                ?: error("Configured OpenCode version is invalid: $OPENCODE_VERSION")

            val latestSupportedRelease = (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .mapNotNull { release ->
                    if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                        return@mapNotNull null
                    }
                    val version = parseVersion(release.optString("tag_name").removePrefix("v"))
                        ?: return@mapNotNull null
                    if (version.firstOrNull() != supportedMajor) return@mapNotNull null
                    release to version
                }
                .maxWithOrNull { left, right -> compareVersions(left.second, right.second) }
                ?: error("No stable OpenCode $supportedMajor.x release was found.")

            val release = latestSupportedRelease.first
            val version = latestSupportedRelease.second
            val assets = release.optJSONArray("assets")
                ?: error("Latest OpenCode $supportedMajor.x release has no assets.")
            var asset: JSONObject? = null
            for (index in 0 until assets.length()) {
                val candidate = assets.optJSONObject(index) ?: continue
                if (candidate.optString("name") == OPENCODE_ASSET_NAME) {
                    asset = candidate
                    break
                }
            }
            val selected = asset ?: error("Latest OpenCode $supportedMajor.x release has no ARM64 OpenCode asset.")
            val url = selected.optString("browser_download_url")
            check(url.startsWith("https://github.com/")) { "Update asset is not hosted on GitHub." }
            val digest = selected.optString("digest").removePrefix("sha256:").lowercase()
            check(digest.matches(Regex("[0-9a-f]{64}"))) { "Latest OpenCode $supportedMajor.x release has no valid SHA-256 digest." }
            OpenCodeUpdate(version, url, digest)
        }
    }

    suspend fun installOpenCodeUpdate(update: OpenCodeUpdate, log: (String) -> Unit, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        installOpenCodeRelease(update, requireNewer = true, log = log, progress = progress)
    }

    /**
     * Repairs only the OpenCode executable using the latest stable ARM64 release
     * in the app's supported major-version channel. Debian and user configuration
     * are not reinstalled or rewritten.
     */
    suspend fun repairOpenCode(log: (String) -> Unit, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        check(hasRootfs()) { "Debian rootfs is not installed; OpenCode repair cannot run." }
        check(!OpenCodeService.isRunning) { "Stop the OpenCode server before repairing OpenCode." }
        val latest = checkForUpdate()
        val installed = installedVersion()?.let(::parseVersion)
        check(installed == null || compareVersions(latest.version, installed) >= 0) {
            "The latest supported release is older than the installed version. Repair was stopped to prevent a downgrade."
        }
        installOpenCodeRelease(latest, requireNewer = false, log = log, progress = progress)
    }

    fun hasPreviousOpenCode(): Boolean {
        val backup = File(rootfs, "usr/local/bin/opencode.previous")
        val version = File(context.filesDir, "opencode.previous.version")
        return runCatching {
            backup.isFile && backup.canExecute() && version.isFile &&
                parseVersion(version.readText().trim()) != null
        }.getOrDefault(false)
    }

    /**
     * Restores the last known-good binary while retaining the currently installed
     * executable under a timestamped failed-binary name for manual recovery.
     */
    suspend fun restorePreviousOpenCode(log: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(!OpenCodeService.isRunning) { "Stop the OpenCode server before restoring OpenCode." }
        val backup = File(rootfs, "usr/local/bin/opencode.previous")
        val versionFile = File(context.filesDir, "opencode.previous.version")
        val previousVersion = versionFile.takeIf { it.isFile }?.readText()?.trim()
            ?: error("No previous OpenCode version metadata is available.")
        check(backup.isFile && backup.canExecute() && parseVersion(previousVersion) != null) {
            "The previous OpenCode binary or its version metadata is missing or invalid."
        }
        val target = File(rootfs, "usr/local/bin/opencode")
        val failed = File(rootfs, "usr/local/bin/opencode.failed-${System.currentTimeMillis()}")
        var currentMoved = false
        try {
            if (target.exists()) {
                Files.move(target.toPath(), failed.toPath())
                currentMoved = true
            }
            Files.move(backup.toPath(), target.toPath())
            writeVersionAtomically(File(context.filesDir, "setup_ready"), previousVersion)
            if (!versionFile.delete() && versionFile.exists()) {
                log("Previous-version metadata could not be cleared; rollback is disabled until it is cleaned up.")
            }
            log("Restored OpenCode V$previousVersion. The replaced executable was retained as ${failed.name}.")
        } catch (t: Throwable) {
            if (target.exists() && !backup.exists()) {
                runCatching { Files.move(target.toPath(), backup.toPath()) }
            }
            if (currentMoved && failed.exists() && !target.exists()) {
                runCatching { Files.move(failed.toPath(), target.toPath()) }
            }
            throw t
        }
    }

    private suspend fun installOpenCodeRelease(
        update: OpenCodeUpdate,
        requireNewer: Boolean,
        log: (String) -> Unit,
        progress: (Long, Long) -> Unit
    ) {
        check(hasRootfs()) { "Install the Debian rootfs before installing OpenCode." }
        check(!OpenCodeService.isRunning) { "Stop the OpenCode server before replacing OpenCode." }
        val currentVersionText = installedVersion()
        val currentVersion = currentVersionText?.let(::parseVersion)
        if (requireNewer) {
            check(currentVersion != null && compareVersions(update.version, currentVersion) > 0) {
                "OpenCode update is not newer than the installed version."
            }
        } else {
            check(currentVersion == null || compareVersions(update.version, currentVersion) >= 0) {
                "Refusing to downgrade OpenCode."
            }
        }

        val archive = File(downloadDirectory(), "opencode-${update.displayVersion}-linux-arm64.tar.gz")
        downloadVerified(update.url, archive, update.sha256, progress) { log(it) }
        val target = File(rootfs, "usr/local/bin/opencode")
        val staging = File(rootfs, ".opencode-update-${System.currentTimeMillis()}")
        val backup = File(rootfs, "usr/local/bin/opencode.previous")
        val previousVersionFile = File(context.filesDir, "opencode.previous.version")
        var previousMoved = false
        var newBinaryInstalled = false
        try {
            installOpenCodeTar(archive, staging)
            check(staging.isFile && staging.canExecute()) {
                "Downloaded release did not produce a usable executable OpenCode binary."
            }

            if (target.isFile) {
                val previousVersion = currentVersionText?.takeIf { parseVersion(it) != null }
                Files.move(target.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                previousMoved = true
                if (previousVersion != null) writeVersionAtomically(previousVersionFile, previousVersion)
                else {
                    previousVersionFile.delete()
                    log("Previous binary retained, but its version is unknown; one-tap rollback is unavailable for it.")
                }
            }
            Files.move(staging.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            newBinaryInstalled = true
            writeVersionAtomically(File(context.filesDir, "setup_ready"), update.displayVersion)
            pruneOpenCodeArchiveCache(archive)
            log("OpenCode V${update.displayVersion} installed and verified. Previous binary retained when available.")
        } catch (t: Throwable) {
            if (newBinaryInstalled && target.exists()) {
                val failed = File(rootfs, "usr/local/bin/opencode.failed-${System.currentTimeMillis()}")
                runCatching { Files.move(target.toPath(), failed.toPath()) }
            }
            if (previousMoved && backup.exists()) {
                runCatching { Files.move(backup.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            }
            if (currentVersionText != null) {
                runCatching { writeVersionAtomically(File(context.filesDir, "setup_ready"), currentVersionText) }
            }
            throw t
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun writeVersionAtomically(destination: File, version: String) {
        val temp = File(destination.parentFile ?: context.filesDir, "${destination.name}.tmp-${System.currentTimeMillis()}")
        try {
            FileOutputStream(temp).use { output ->
                output.write((version + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Repairs only the baseline Debian command-line packages. It never reinstalls
     * Debian, replaces OpenCode, or modifies OpenCode configuration and projects.
     */
    suspend fun repairRequiredPackages(log: (String) -> Unit) = withContext(Dispatchers.IO) {
        check(hasRootfs()) { "Debian rootfs is not installed; package repair cannot run." }
        check(!OpenCodeService.isRunning) { "Stop the OpenCode server before repairing Debian packages." }
        check(File(rootfs, "usr/bin/apt-get").isFile && File(rootfs, "bin/bash").isFile) {
            "Debian package manager or shell is missing. Package-only repair is unsafe; inspect the Debian rootfs instead."
        }

        postExtractFixes(rootfs, log)
        File(rootfs, "etc/apt/apt.conf.d/01-android-proot").apply {
            parentFile?.mkdirs()
            writeText("APT::Sandbox::User \"root\";\n")
        }
        val script = """
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            echo "Checking Debian mirror DNS from inside PRoot…"
            getent ahosts deb.debian.org >/dev/null || {
                echo "DNS preflight failed for deb.debian.org. Check Android connectivity, VPN, or Private DNS, then retry." >&2
                exit 42
            }
            echo "Refreshing Debian package indexes…"
            apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=25 update || {
                echo "APT index refresh failed; no package changes were attempted." >&2
                exit 43
            }
            echo "Repairing only the baseline command-line packages…"
            apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=25 install -y --no-install-recommends git ripgrep curl ca-certificates procps openssh-client unzip
        """.trimIndent()
        val process = ProotRunner.start(context, listOf("/bin/bash", "-lc", script))
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(log) }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            when (exitCode) {
                42 -> "Debian DNS preflight failed. Check the active Android network and retry."
                43 -> "APT could not refresh package indexes. No package installation was attempted."
                else -> "Debian package repair failed (exit code $exitCode)."
            }
        }
        log("Baseline Debian packages are installed. Debian rootfs, OpenCode binary, configuration, and project files were not replaced.")
    }

    /**
     * Runs the complete first-run installation in one resumable operation.
     * Completed stages are reused; recoverable files are retained after failures.
     */
    suspend fun installAll(
        log: (String) -> Unit,
        progress: (Long, Long) -> Unit,
        stage: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        check(!isReady()) { "OpenCode is already installed and ready." }

        if (!hasRootfs()) {
            check(!rootfs.exists()) {
                "An incomplete Debian rootfs directory exists. It was left untouched; inspect or repair it before retrying."
            }
            stage("Downloading and installing Debian…")
            installRootfs(log, progress)
        } else {
            log("Debian rootfs already exists; reusing it.")
        }

        stage("Verifying the bundled PRoot runtime…")
        log("Running a real command inside Debian through the bundled PRoot executable…")
        val probe = ProotRunner.start(context, listOf("/bin/echo", "opencode-proot-ready"))
        val output = try {
            val text = probe.inputStream.bufferedReader().use { it.readText() }
            val exitCode = probe.waitFor()
            check(exitCode == 0 && text.lineSequence().any { it.trim() == "opencode-proot-ready" }) {
                "Bundled PRoot runtime check failed (exit code $exitCode): $text"
            }
            text
        } finally {
            if (probe.isAlive) probe.destroyForcibly()
        }
        log("Bundled PRoot runtime verified: " + output.lineSequence().firstOrNull { it.trim() == "opencode-proot-ready" }?.trim())

        if (!isReady()) {
            stage("Installing OpenCode and Linux packages…")
            installOpenCodeAndPackages(log, progress)
        } else {
            log("OpenCode runtime already installed; reusing it.")
        }

        check(isReady()) { "Installation finished without producing a ready OpenCode runtime." }
        stage("Your OpenCode workspace is ready.")
        log("All setup stages completed successfully.")
    }
    suspend fun installRootfs(log: (String) -> Unit, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        check(!rootfs.exists()) {
            if (hasRootfs()) {
                "A Debian rootfs already exists."
            } else {
                "A rootfs directory already exists but is incomplete. It was left untouched; inspect it before removing it."
            }
        }

        val archive = File(context.cacheDir, "debian-bookworm-arm64-rootfs.tar.xz")
        val cachedArchiveIsValid = archive.isFile &&
            runCatching { sha256(archive) == ROOTFS_SHA256 }.getOrDefault(false)

        if (cachedArchiveIsValid) {
            log("Reusing previously downloaded Debian archive; SHA-256 verified.")
        } else {
            if (archive.exists()) {
                archive.delete()
                log("Discarded an incomplete or invalid cached Debian archive.")
            }
            log("Downloading pinned Debian Bookworm ARM64 rootfs…")
            download(ROOTFS_URL, archive, progress)
            log("Checking SHA-256…")
            if (sha256(archive) != ROOTFS_SHA256) {
                archive.delete()
                error("Rootfs checksum mismatch; download removed.")
            }
        }

        cleanupAbandonedRootfsStagingDirectories(log)

        val stage = File(context.filesDir, "rootfs-staging-${System.currentTimeMillis()}")
        try {
            log("Extracting rootfs (this may take several minutes)…")
            extractArchive(archive, stage, compressed = "xz", log = log)
            postExtractFixes(stage, log)
            check(File(stage, "bin/bash").exists()) { "Archive did not contain a Debian root filesystem." }
            check(stage.renameTo(rootfs)) { "Could not activate the extracted rootfs." }
            log("Debian rootfs installed.")
            // Keep the verified archive until activation succeeds so a failed extraction
            // can be retried without downloading the large rootfs again.
            archive.delete()
        } catch (t: Throwable) {
            // The unique staging directory is never followed through symlinks during cleanup.
            removeTreeNoFollow(stage.toPath())
            // Deliberately retain the verified archive for the next installation attempt.
            throw t
        }
    }

    suspend fun installOpenCodeAndPackages(log: (String) -> Unit, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        check(hasRootfs()) { "Install the Debian rootfs first." }
        val archive = File(downloadDirectory(), "opencode-${OPENCODE_VERSION}-linux-arm64.tar.gz")
        downloadVerified(OPENCODE_URL, archive, OPENCODE_SHA256, progress) { log(it) }
        installOpenCodeTar(archive, File(rootfs, "usr/local/bin/opencode"))
        pruneOpenCodeArchiveCache(archive)
        postExtractFixes(rootfs, log)
        writeOpenCodeDefaults()
        log("Installing Git, ripgrep, curl, CA certificates and procps…")
        File(rootfs, "etc/apt/apt.conf.d/01-android-proot").apply {
            parentFile?.mkdirs()
            writeText("APT::Sandbox::User \"root\";\n")
        }
        // Refresh DNS immediately before APT: Android may have changed networks since rootfs extraction.
        postExtractFixes(rootfs, log)
        val script = """
            set -eu
            export DEBIAN_FRONTEND=noninteractive
            echo "Checking Debian mirror DNS from inside PRoot…"
            if ! getent ahosts deb.debian.org; then
                echo "DNS preflight failed for deb.debian.org. Check connectivity, VPN, Private DNS, or the active network, then retry." >&2
                exit 42
            fi
            echo "Refreshing Debian package indexes…"
            if ! apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=25 update; then
                echo "APT index refresh failed. Package installation was not attempted; fix the DNS/network error and retry." >&2
                exit 43
            fi
            echo "Installing required packages…"
            apt-get -o Acquire::Retries=2 -o Acquire::http::Timeout=25 install -y --no-install-recommends git ripgrep curl ca-certificates procps openssh-client unzip
        """.trimIndent()
        val p = ProotRunner.start(context, listOf("/bin/bash", "-lc", script))
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach(log) }
        val exitCode = p.waitFor()
        check(exitCode == 0) {
            when (exitCode) {
                42 -> "Debian DNS preflight failed. Android could not resolve deb.debian.org inside PRoot."
                43 -> "APT could not refresh package indexes. Check the network/DNS log and retry."
                else -> "APT package installation failed (exit code $exitCode)."
            }
        }
        File(context.filesDir, "setup_ready").writeText("$OPENCODE_VERSION\n")
        log("OpenCode V$OPENCODE_VERSION and required packages installed.")
    }

    private fun downloadDirectory(): File =
        File(context.filesDir, "downloads").apply { check(isDirectory || mkdirs()) {
            "Could not create persistent download cache."
        } }

    private fun pruneOpenCodeArchiveCache(keep: File) {
        val cache = keep.parentFile ?: return
        val archivePattern = Regex("opencode-\\d+(?:\\.\\d+){1,2}-linux-arm64\\.tar\\.gz(?:\\.part(?:\\.properties)?)?")
        cache.listFiles()
            ?.filter { it.isFile && archivePattern.matches(it.name) && it.absoluteFile != keep.absoluteFile }
            ?.forEach { it.delete() }
    }

    private fun downloadVerified(
        url: String,
        destination: File,
        expectedSha256: String,
        progress: (Long, Long) -> Unit,
        log: (String) -> Unit
    ) {
        destination.parentFile?.let { check(it.isDirectory || it.mkdirs()) {
            "Could not create download cache directory."
        } }
        if (destination.isFile) {
            if (runCatching { sha256(destination) == expectedSha256 }.getOrDefault(false)) {
                log("Reusing cached OpenCode archive; SHA-256 verified.")
                progress(destination.length(), destination.length())
                return
            }
            destination.delete()
            log("Discarded an invalid cached OpenCode archive.")
        }
        log("Downloading OpenCode runtime archive…")
        download(url, destination, progress)
        log("Verifying OpenCode archive SHA-256…")
        if (sha256(destination) != expectedSha256) {
            destination.delete()
            error("OpenCode checksum mismatch; downloaded archive removed.")
        }
        log("OpenCode archive downloaded and verified.")
    }

    /**
     * Downloads to a .part file and retains it after network failures. A partial
     * download is resumed only when its sidecar identifies the same URL and the
     * server supplied an ETag or Last-Modified validator for If-Range.
     */
    private fun download(url: String, dest: File, progress: (Long, Long) -> Unit) {
        val partial = File(dest.parentFile, dest.name + ".part")
        val metadata = File(dest.parentFile, dest.name + ".part.properties")
        val properties = Properties().apply {
            if (partial.isFile && metadata.isFile) {
                runCatching { metadata.inputStream().use { input -> load(input) } }
            }
        }
        val savedUrl = properties.getProperty("url")
        val etag = properties.getProperty("etag").orEmpty()
        val lastModified = properties.getProperty("lastModified").orEmpty()
        val canResume = partial.isFile && partial.length() > 0L &&
            savedUrl == url && (etag.isNotBlank() || lastModified.isNotBlank())

        if (partial.exists() && !canResume) {
            partial.delete()
            metadata.delete()
        }

        val offset = if (canResume) partial.length() else 0L
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "OpenCode-Mobile")
        if (offset > 0L) {
            requestBuilder.header("Range", "bytes=$offset-")
            requestBuilder.header("If-Range", etag.ifBlank { lastModified })
            progress(offset, -1L)
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            if (offset > 0L && response.code == 416) {
                partial.delete()
                metadata.delete()
                return download(url, dest, progress)
            }

            val append = offset > 0L && response.code == 206
            if (append) {
                val range = response.header("Content-Range")
                val start = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
                    .matchEntire(range.orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()
                if (start != offset) {
                    partial.delete()
                    metadata.delete()
                    return download(url, dest, progress)
                }
            } else {
                check(response.code == 200) {
                    "Download failed: HTTP ${response.code}"
                }
                // A server that ignores Range returns the complete object. Never
                // append it to an existing partial file.
                if (offset > 0L) {
                    partial.delete()
                    metadata.delete()
                }
            }

            val body = response.body ?: error("Empty response")
            val responseEtag = response.header("ETag").orEmpty()
            val responseLastModified = response.header("Last-Modified").orEmpty()
            if (append &&
                ((etag.isNotBlank() && responseEtag.isNotBlank() && etag != responseEtag) ||
                    (lastModified.isNotBlank() && responseLastModified.isNotBlank() && lastModified != responseLastModified))
            ) {
                partial.delete()
                metadata.delete()
                response.close()
                return download(url, dest, progress)
            }
            val effectiveEtag = responseEtag.ifBlank { etag }
            val effectiveLastModified = responseLastModified.ifBlank { lastModified }
            metadata.delete()
            if (effectiveEtag.isNotBlank() || effectiveLastModified.isNotBlank()) {
                Properties().apply {
                    setProperty("url", url)
                    if (effectiveEtag.isNotBlank()) setProperty("etag", effectiveEtag)
                    if (effectiveLastModified.isNotBlank()) setProperty("lastModified", effectiveLastModified)
                }.let { saved ->
                    val temp = File(metadata.parentFile, metadata.name + ".tmp")
                    temp.outputStream().use { saved.store(it, "OpenCode download resume metadata") }
                    if (metadata.exists()) metadata.delete()
                    check(temp.renameTo(metadata)) { "Could not save download resume metadata." }
                }
            }

            val total = if (append) {
                Regex("^bytes \\d+-\\d+/(\\d+)$")
                    .find(response.header("Content-Range").orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            } else {
                body.contentLength()
            }
            var done = if (append) offset else 0L
            FileOutputStream(partial, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        done += count
                        progress(done, total)
                    }
                    out.fd.sync()
                }
            }
            if (total >= 0L) {
                check(done == total) {
                    "Download incomplete: received $done of $total bytes; partial file retained for resume."
                }
            }
        }

        if (dest.exists()) check(dest.delete()) { "Could not replace previous download archive." }
        check(partial.renameTo(dest)) { "Could not finalize downloaded file." }
        metadata.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installOpenCodeTar(archive: File, target: File) {
        target.parentFile?.mkdirs()
        GZIPInputStream(archive.inputStream().buffered()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (entry.isFile && entry.name.substringAfterLast('/') == "opencode") {
                        FileOutputStream(target).use { tar.copyTo(it) }
                        Os.chmod(target.absolutePath, 493) // 0755
                        return
                    }
                }
            }
        }
        error("The OpenCode release archive did not contain an opencode executable.")
    }

    private fun extractArchive(archive: File, destination: File, compressed: String, log: (String) -> Unit) {
        check(destination.mkdirs()) { "Could not create staging directory." }
        val stagePath = destination.toPath().toAbsolutePath().normalize()
        val symlinks = mutableListOf<Pair<Path, String>>()
        val hardlinks = mutableListOf<Pair<Path, Path>>()
        val directoryModes = mutableListOf<Pair<Path, Int>>()
        val raw = archive.inputStream().buffered()
        val decompressed: InputStream = when (compressed) {
            "xz" -> XZInputStream(raw)
            "gz" -> GZIPInputStream(raw)
            else -> raw
        }
        TarArchiveInputStream(decompressed).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val rawName = entry.name.removePrefix("./").trimEnd('/')
                if (rawName.isEmpty() || rawName == ".") {
                    require(entry.isDirectory) { "Unsafe archive root entry: ${entry.name}" }
                    continue
                }
                val relative = safeRelativePath(entry.name)
                val out = stagePath.resolve(relative).normalize()
                require(out.startsWith(stagePath) && out != stagePath) { "Unsafe archive path: ${entry.name}" }
                ensureNoSymlinkParents(stagePath, out.parent)
                when {
                    entry.isDirectory -> {
                        Files.createDirectories(out)
                        directoryModes += out to entry.mode
                    }
                    entry.isSymbolicLink -> symlinks += out to entry.linkName
                    entry.isLink -> {
                        val targetRel = safeRelativePath(entry.linkName)
                        val target = stagePath.resolve(targetRel).normalize()
                        require(target.startsWith(stagePath)) { "Unsafe hard-link target: ${entry.linkName}" }
                        hardlinks += out to target
                    }
                    entry.isFile -> {
                        Files.createDirectories(out.parent)
                        ensureNoSymlinkParents(stagePath, out.parent)
                        Files.newOutputStream(out).use { tar.copyTo(it) }
                        Os.chmod(out.toString(), entry.mode and 4095)
                    }
                    else -> Unit // Device nodes and FIFOs are not needed for this unprivileged rootfs.
                }
            }
        }
        // Probe the exact staging filesystem once. This avoids repeatedly invoking
        // a syscall that the current Android filesystem may reject for every tar link.
        var nativeHardLinksSupported = hardlinks.isNotEmpty() && probeHardLinkSupport(stagePath, log)
        var copiedHardLinks = 0
        var createdHardLinks = 0
        var rootCreatedHardLinks = 0
        val rootHardLinksEnabled = RootAccessManager.isRootHardLinksEnabled(context)
        if (rootHardLinksEnabled) {
            log("Root-assisted hard-link fallback is enabled for this installation.")
        }
        for ((link, target) in hardlinks) {
            ensureNoSymlinkParents(stagePath, link.parent)
            ensureNoSymlinkParents(stagePath, target.parent)
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                "Missing or unsafe hard-link target: $target"
            }
            Files.createDirectories(link.parent)

            // Debian archives can repeat paths. Replace only an existing non-directory
            // entry in the private staging tree before creating the hard-link equivalent.
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS)) {
                    "Cannot replace directory with Debian hard link: $link"
                }
                Files.delete(link)
            }

            try {
                if (nativeHardLinksSupported) {
                    try {
                        Os.link(target.toString(), link.toString())
                        createdHardLinks++
                        continue
                    } catch (e: ErrnoException) {
                        // A failed link syscall should not leave a partial destination
                        // that would prevent the regular-file fallback from succeeding.
                        Files.deleteIfExists(link)
                        nativeHardLinksSupported = false
                        log(
                            "Native hard-link creation failed (errno ${e.errno}: ${Os.strerror(e.errno)}); " +
                                "switching remaining entries to regular-file copies."
                        )
                    }
                }

                if (rootHardLinksEnabled) {
                    val rootResult = RootAccessManager.createHardLink(stagePath.toFile(), target, link)
                    if (rootResult.success) {
                        rootCreatedHardLinks++
                        log("Created Debian hard link with optional root authorization.")
                        continue
                    }
                    log("Root-assisted hard link unavailable: ${rootResult.message} Falling back to a regular-file copy.")
                }
                materializeHardLinkCopy(target, link)
                copiedHardLinks++
            } catch (e: Exception) {
                throw java.io.IOException(
                    "Could not create or copy Debian hard link '$link' from '$target' " +
                        "(${e.javaClass.simpleName}: ${e.message})",
                    e
                )
            }
        }
        if (hardlinks.isNotEmpty()) {
            log("Rootfs hard links: $createdHardLinks native, $rootCreatedHardLinks root-assisted, $copiedHardLinks copied.")
        }
        for ((link, target) in symlinks) {
            ensureNoSymlinkParents(stagePath, link.parent)
            Files.createDirectories(link.parent)

            // Some rootfs tarballs contain repeated path records. Replace an existing
            // non-directory entry rather than letting createSymbolicLink fail with
            // FileAlreadyExistsException. The staging tree is private to this install.
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS)) {
                    "Cannot replace directory with Debian symlink: $link"
                }
                Files.delete(link)
            }

            try {
                // Android's libcore NIO provider has device/filesystem-specific
                // symlink behavior; use the platform syscall directly and keep the
                // tar link target relative exactly as stored in the archive.
                Os.symlink(target, link.toString())
            } catch (e: Exception) {
                throw java.io.IOException(
                    "Could not create Debian symlink '$link' -> '$target' " +
                        "(${e.javaClass.simpleName}: ${e.message})",
                    e
                )
            }
        }
        directoryModes.sortedByDescending { it.first.nameCount }.forEach { (path, mode) ->
            if (!Files.isSymbolicLink(path)) runCatching { Os.chmod(path.toString(), mode and 4095) }
        }
    }

    private fun probeHardLinkSupport(stagePath: Path, log: (String) -> Unit): Boolean {
        var source: Path? = null
        var destination: Path? = null
        return try {
            source = Files.createTempFile(stagePath, ".hardlink-probe-", ".source")
            destination = source.resolveSibling(source.fileName.toString() + ".link")
            Files.write(source, byteArrayOf(0x4f, 0x43, 0x4f, 0x44, 0x45))
            Os.link(source.toString(), destination.toString())

            val sourceStat = Os.stat(source.toString())
            val destinationStat = Os.stat(destination.toString())
            check(sourceStat.st_dev == destinationStat.st_dev &&
                sourceStat.st_ino == destinationStat.st_ino) {
                "Native hard-link probe did not resolve to the same inode."
            }
            log("Native hard links are supported by the rootfs staging filesystem.")
            true
        } catch (e: ErrnoException) {
            log(
                "Native hard-link probe unavailable (errno ${e.errno}: ${Os.strerror(e.errno)}); " +
                    "using regular-file copies for Debian hard links."
            )
            false
        } catch (e: Exception) {
            log(
                "Native hard-link probe failed (${e.javaClass.simpleName}: ${e.message}); " +
                    "using regular-file copies for Debian hard links."
            )
            false
        } finally {
            runCatching { destination?.let(Files::deleteIfExists) }
            runCatching { source?.let(Files::deleteIfExists) }
        }
    }

    private fun materializeHardLinkCopy(target: Path, link: Path) {
        Files.copy(target, link)
        val targetMode = Os.stat(target.toString()).st_mode and 4095
        Os.chmod(link.toString(), targetMode)
        check(Files.size(link) == Files.size(target)) {
            "Copied Debian hard-link entry has an unexpected file size: $link"
        }
    }

    private fun safeRelativePath(name: String): Path {
        val normalized = name.removePrefix("./").trimEnd('/')
        val path = Paths.get(normalized).normalize()
        require(!path.isAbsolute && path.nameCount > 0 && path.toString() != ".." && !path.startsWith("..")) {
            "Unsafe archive path: $name"
        }
        return path
    }

    private fun ensureNoSymlinkParents(root: Path, parent: Path?) {
        if (parent == null) return
        val normalized = parent.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Archive path escaped staging directory." }
        var current = root
        for (part in root.relativize(normalized)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Archive entry traverses a symlink: $current" }
        }
    }

    private fun postExtractFixes(base: File, log: (String) -> Unit) {
        listOf("tmp", "dev", "proc", "sys").forEach { name ->
            val path = File(base, name).toPath()
            if (Files.isSymbolicLink(path)) Files.delete(path)
            Files.createDirectories(path)
            if (name == "tmp") Os.chmod(path.toString(), 1023) // 01777
        }
        val connectivity = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        }.getOrNull()
        val activeNetwork = runCatching { connectivity?.activeNetwork }.getOrNull()
        val dns = runCatching {
            connectivity?.getLinkProperties(activeNetwork)?.dnsServers
                ?.map { it.hostAddress.substringBefore('%') }
                ?.filter { address ->
                    runCatching {
                        val parsed = java.net.InetAddress.getByName(address)
                        !parsed.isAnyLocalAddress && !parsed.isLoopbackAddress &&
                            !parsed.isLinkLocalAddress && !parsed.isMulticastAddress
                    }.getOrDefault(false)
                }
                .orEmpty()
        }.getOrDefault(emptyList())
        val selectedDns = dns.distinct().ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val etc = File(base, "etc").toPath()
        if (Files.isSymbolicLink(etc)) Files.delete(etc)
        Files.createDirectories(etc)
        val resolv = etc.resolve("resolv.conf")
        Files.deleteIfExists(resolv)
        Files.newOutputStream(resolv).use {
            it.write((selectedDns.joinToString("\n") { address -> "nameserver $address" } + "\n").toByteArray())
        }
        log("Rootfs DNS servers: ${selectedDns.joinToString(", ")}")

        // Android may resolve through its per-network resolver while direct UDP/53
        // queries from sandboxed child processes are blocked. Add a temporary hosts
        // fallback for Debian mirror endpoints so APT can bootstrap in that case.
        val mirrorHosts = listOf("deb.debian.org", "security.debian.org", "cdn-fastly.deb.debian.org")
        val resolvedEntries = mirrorHosts.flatMap { host ->
            val addresses = runCatching {
                if (activeNetwork != null) activeNetwork.getAllByName(host).toList()
                else java.net.InetAddress.getAllByName(host).toList()
            }.getOrDefault(emptyList())
            addresses.map { it.hostAddress.substringBefore('%') to host }
        }.distinct()
        val hosts = etc.resolve("hosts")
        if (Files.isSymbolicLink(hosts)) Files.delete(hosts)
        val previousHosts = if (Files.exists(hosts)) String(Files.readAllBytes(hosts), Charsets.UTF_8)
            else "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        val markerStart = "# OPENCODE-MOBILE-DEBIAN-DNS-START"
        val markerEnd = "# OPENCODE-MOBILE-DEBIAN-DNS-END"
        val markerPattern = "(?s)\\n?" + java.util.regex.Pattern.quote(markerStart) +
            ".*?" + java.util.regex.Pattern.quote(markerEnd) + "\\n?"
        val cleanHosts = previousHosts.replace(Regex(markerPattern), "\n")
        val block = buildString {
            append(markerStart).append('\n')
            resolvedEntries.forEach { (address, host) -> append(address).append(' ').append(host).append('\n') }
            append(markerEnd).append('\n')
        }
        Files.write(hosts, (cleanHosts.trimEnd() + "\n" + block).toByteArray(Charsets.UTF_8))
        log("Android resolver supplied ${resolvedEntries.size} Debian mirror address records for APT fallback.")
        if (resolvedEntries.isEmpty()) log("Android resolver could not resolve Debian mirror hosts; APT will rely on resolv.conf.")
    }

    private fun writeOpenCodeDefaults() {
        val dir = File(rootfs, "root/.config/opencode").apply { mkdirs() }
        val config = File(dir, "opencode.json")
        if (!config.exists()) config.writeText(DEFAULT_CONFIG)
        val agents = File(dir, "AGENTS.md")
        if (!agents.exists()) agents.writeText(DEFAULT_AGENTS)
    }

    /**
     * A force-stop or process kill can bypass the extraction catch block and leave
     * an abandoned staging directory behind. Remove only our exact staging-name
     * pattern, and never follow a symlink while identifying or deleting candidates.
     */
    private fun cleanupAbandonedRootfsStagingDirectories(log: (String) -> Unit) {
        val filesRoot = context.filesDir.toPath().toAbsolutePath().normalize()
        val candidates = context.filesDir.listFiles().orEmpty().filter { candidate ->
            candidate.name.matches(Regex("rootfs-staging-\\d+")) &&
                Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(candidate.toPath())
        }
        for (candidate in candidates) {
            val candidatePath = candidate.toPath().toAbsolutePath().normalize()
            if (candidatePath.parent != filesRoot) continue
            runCatching {
                removeTreeNoFollow(candidatePath)
                log("Removed abandoned Debian extraction staging directory: ${candidate.name}")
            }.onFailure {
                log("Could not remove abandoned Debian staging directory ${candidate.name}: ${it.message}")
                throw it
            }
        }
    }

    private fun removeTreeNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
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

    companion object {
        const val OPENCODE_VERSION = "2.3.2"
        const val ROOTFS_URL = "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20261117_05:24/rootfs.tar.xz"
        const val ROOTFS_SHA256 = "125416b51b1272db7abd932bc7cc5f286fd29a190cb5a1b18460500f5448d5fc"
        const val OPENCODE_URL = "https://github.com/anomalyco/opencode/releases/download/v2.3.2/opencode-linux-arm64.tar.gz"
        const val OPENCODE_SHA256 = "9d10185acdb72336e086e699ca363a6065f410fd3c1caed0dc8046e95ac06ccb"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/anomalyco/opencode/releases?per_page=100"
        const val OPENCODE_ASSET_NAME = "opencode-linux-arm64.tar.gz"

        private fun parseVersion(value: String): List<Int>? {
            val parts = value.substringBefore('-').split('.')
            if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
            return parts.map(String::toIntOrNull).takeIf { it.all { number -> number != null } }?.map { it!! }
        }

        private fun compareVersions(left: List<Int>, right: List<Int>): Int {
            for (index in 0 until maxOf(left.size, right.size)) {
                val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
                if (comparison != 0) return comparison
            }
            return 0
        }

        private val DEFAULT_CONFIG = """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "permissions": [
                { "action": "*", "resource": "*", "effect": "ask" },
                { "action": "read", "resource": "*", "effect": "allow" },
                { "action": "glob", "resource": "*", "effect": "allow" },
                { "action": "grep", "resource": "*", "effect": "allow" },
                { "action": "edit", "resource": "*", "effect": "ask" },
                { "action": "shell", "resource": "*", "effect": "ask" },
                { "action": "external_directory", "resource": "*", "effect": "ask" }
              ]
            }
        """.trimIndent()

        private val DEFAULT_AGENTS = """
            Before running any command or editing any file, say in one or two sentences what it will do, why, and what could be lost or changed.
            Be explicit for anything outside /root/projects and for anything that deletes, moves, or overwrites files. Do not proceed with destructive commands without asking.
            Treat this as guidance only: users should trust the actual command and path shown in an OpenCode permission prompt over the model's explanation.
        """.trimIndent()
    }
}
