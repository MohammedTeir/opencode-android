package io.github.mohammedteir.opencodemobile

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
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
            val release = JSONObject(response.body?.string().orEmpty())
            check(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Latest release is not stable." }
            val tag = release.optString("tag_name")
            val version = parseVersion(tag.removePrefix("v"))
            check(version != null) { "Latest release has an invalid version tag." }
            val assets = release.optJSONArray("assets") ?: error("Latest release has no assets.")
            var asset: JSONObject? = null
            for (index in 0 until assets.length()) {
                val candidate = assets.optJSONObject(index) ?: continue
                if (candidate.optString("name") == OPENCODE_ASSET_NAME) {
                    asset = candidate
                    break
                }
            }
            val selected = asset ?: error("Latest release has no ARM64 OpenCode asset.")
            val url = selected.optString("browser_download_url")
            check(url.startsWith("https://github.com/")) { "Update asset is not hosted on GitHub." }
            val digest = selected.optString("digest").removePrefix("sha256:").lowercase()
            check(digest.matches(Regex("[0-9a-f]{64}"))) { "Latest release has no valid SHA-256 digest." }
            OpenCodeUpdate(version, url, digest)
        }
    }

    suspend fun installOpenCodeUpdate(update: OpenCodeUpdate, log: (String) -> Unit, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        check(hasRootfs() && hasOpenCodeBinary()) { "Install OpenCode before updating it." }
        val installed = installedVersion()?.let(::parseVersion) ?: error("Installed OpenCode version is unknown.")
        check(compareVersions(update.version, installed) > 0) { "OpenCode is already up to date." }
        val archive = File(context.cacheDir, "opencode-${update.displayVersion}.tar.gz")
        log("Downloading OpenCode V${update.version} ARM64 update…")
        download(update.url, archive, progress)
        try {
            log("Checking update SHA-256…")
            check(sha256(archive) == update.sha256) { "Update checksum mismatch; download removed." }
            val target = File(rootfs, "usr/local/bin/opencode")
            val staging = File(rootfs, ".opencode-update-${System.currentTimeMillis()}")
            val backup = File(rootfs, "usr/local/bin/opencode.previous")
            try {
                installOpenCodeTar(archive, staging)
                check(staging.canExecute()) { "Downloaded update did not contain an executable OpenCode binary." }
                Files.move(target.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                try {
                    Files.move(staging.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (t: Throwable) {
                    Files.move(backup.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    throw t
                }
                File(context.filesDir, "setup_ready").writeText("${update.version}\n")
                log("OpenCode V${update.displayVersion} installed. Previous binary kept at usr/local/bin/opencode.previous.")
            } finally {
                if (staging.exists()) staging.delete()
            }
        } finally {
            archive.delete()
        }
    }

    suspend fun installRootfs(log: (String) -> Unit, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        require(!hasRootfs()) { "A Debian rootfs already exists." }
        val archive = File(context.cacheDir, "debian-bookworm-arm64-rootfs.tar.xz")
        log("Downloading pinned Debian Bookworm ARM64 rootfs…")
        download(ROOTFS_URL, archive, progress)
        log("Checking SHA-256…")
        if (sha256(archive) != ROOTFS_SHA256) {
            archive.delete()
            error("Rootfs checksum mismatch; download removed.")
        }
        val stage = File(context.filesDir, "rootfs-staging-${System.currentTimeMillis()}")
        try {
            log("Extracting rootfs (this may take several minutes)…")
            extractArchive(archive, stage, compressed = "xz")
            postExtractFixes(stage, log)
            check(File(stage, "bin/bash").exists()) { "Archive did not contain a Debian root filesystem." }
            check(stage.renameTo(rootfs)) { "Could not activate the extracted rootfs." }
            log("Debian rootfs installed.")
        } catch (t: Throwable) {
            // The unique staging directory is never followed through symlinks during cleanup.
            removeTreeNoFollow(stage.toPath())
            throw t
        } finally {
            archive.delete()
        }
    }

    suspend fun installOpenCodeAndPackages(log: (String) -> Unit, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        check(hasRootfs()) { "Install the Debian rootfs first." }
        val archive = File(context.cacheDir, "opencode-v2.3.2-linux-arm64.tar.gz")
        log("Downloading official OpenCode V2.3.2 ARM64 release…")
        download(OPENCODE_URL, archive, progress)
        log("Checking OpenCode V2.3.2 SHA-256…")
        if (sha256(archive) != OPENCODE_SHA256) {
            archive.delete()
            error("OpenCode checksum mismatch; download removed.")
        }
        try {
            installOpenCodeTar(archive, File(rootfs, "usr/local/bin/opencode"))
        } finally {
            archive.delete()
        }
        postExtractFixes(rootfs, log)
        writeOpenCodeDefaults()
        log("Installing Git, ripgrep, curl, CA certificates and procps…")
        File(rootfs, "etc/apt/apt.conf.d/01-android-proot").apply {
            parentFile?.mkdirs()
            writeText("APT::Sandbox::User \"root\";\n")
        }
        val script = "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y --no-install-recommends git ripgrep curl ca-certificates procps openssh-client unzip"
        val p = ProotRunner.start(context, listOf("/bin/bash", "-lc", script))
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach(log) }
        check(p.waitFor() == 0) { "apt package installation failed." }
        File(context.filesDir, "setup_ready").writeText("$OPENCODE_VERSION\n")
        log("OpenCode V$OPENCODE_VERSION and required packages installed.")
    }

    private fun download(url: String, dest: File, progress: (Long, Long) -> Unit) {
        val partial = File(dest.parentFile, dest.name + ".part")
        partial.delete()
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
            val body = response.body ?: error("Empty response")
            val total = body.contentLength()
            var done = 0L
            FileOutputStream(partial, false).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        }
        check(partial.renameTo(dest)) { "Could not finalize downloaded file." }
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

    private fun extractArchive(archive: File, destination: File, compressed: String) {
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
        for ((link, target) in hardlinks) {
            ensureNoSymlinkParents(stagePath, link.parent)
            ensureNoSymlinkParents(stagePath, target.parent)
            require(Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                "Missing or unsafe hard-link target: $target"
            }
            Files.createDirectories(link.parent)
            Files.createLink(link, target)
        }
        for ((link, target) in symlinks) {
            ensureNoSymlinkParents(stagePath, link.parent)
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, Paths.get(target))
        }
        directoryModes.sortedByDescending { it.first.nameCount }.forEach { (path, mode) ->
            if (!Files.isSymbolicLink(path)) runCatching { Os.chmod(path.toString(), mode and 4095) }
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
        val dns = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.map { it.hostAddress }?.filterNotNull().orEmpty()
        }.getOrDefault(emptyList()).ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val etc = File(base, "etc").toPath()
        if (Files.isSymbolicLink(etc)) Files.delete(etc)
        Files.createDirectories(etc)
        val resolv = etc.resolve("resolv.conf")
        Files.deleteIfExists(resolv)
        Files.newOutputStream(resolv).use {
            it.write((dns.distinct().joinToString("\n") { address -> "nameserver $address" } + "\n").toByteArray())
        }
        log("Rootfs networking configured with Android DNS.")
    }

    private fun writeOpenCodeDefaults() {
        val dir = File(rootfs, "root/.config/opencode").apply { mkdirs() }
        val config = File(dir, "opencode.json")
        if (!config.exists()) config.writeText(DEFAULT_CONFIG)
        val agents = File(dir, "AGENTS.md")
        if (!agents.exists()) agents.writeText(DEFAULT_AGENTS)
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
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
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
