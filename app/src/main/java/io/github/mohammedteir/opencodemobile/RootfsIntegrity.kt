package io.github.mohammedteir.opencodemobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Verifies Debian installation provenance and critical filesystem structure.
 *
 * The manifest is an installation record, not a hash of the mutable live rootfs:
 * apt upgrades and user-installed packages legitimately change Debian files.
 */
object RootfsIntegrity {
    enum class State { VERIFIED, LEGACY, MISSING, INCOMPLETE, INVALID }

    data class Inspection(
        val state: State,
        val detail: String,
        val missingPaths: List<String> = emptyList()
    )

    private const val MANIFEST_NAME = "rootfs_install.properties"
    private const val FORMAT_VERSION = "1"
    private val requiredPaths = listOf(
        "bin/bash",
        "usr/bin/env",
        "etc/os-release",
        "usr/bin/dpkg",
        "usr/bin/apt-get",
        "var/lib/dpkg/status"
    )

    fun writeInstallRecord(context: Context, extractedRootfs: File, installedRootfs: File) {
        val missing = requiredPaths.filterNot { File(extractedRootfs, it).exists() }
        check(missing.isEmpty()) {
            "Extracted Debian rootfs is missing required paths: ${missing.joinToString()}."
        }
        val properties = Properties().apply {
            setProperty("format", FORMAT_VERSION)
            setProperty("distribution", "debian")
            setProperty("release", "bookworm")
            setProperty("architecture", "arm64")
            setProperty("source_url", SetupManager.ROOTFS_URL)
            setProperty("source_sha256", SetupManager.ROOTFS_SHA256)
            setProperty("installed_at_epoch_ms", System.currentTimeMillis().toString())
            setProperty("rootfs_path", installedRootfs.canonicalPath)
            setProperty("complete", "true")
        }
        val destination = File(context.filesDir, MANIFEST_NAME)
        val temporary = File(context.filesDir, "$MANIFEST_NAME.tmp-${System.currentTimeMillis()}")
        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, "OpenCode Mobile Debian rootfs installation record")
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun inspect(context: Context, rootfs: File): Inspection {
        if (!rootfs.isDirectory) {
            return Inspection(State.MISSING, "Private Debian rootfs directory is not installed.")
        }

        val missing = requiredPaths.filterNot { File(rootfs, it).exists() }
        if (missing.isNotEmpty()) {
            return Inspection(
                State.INCOMPLETE,
                "Debian rootfs is missing required paths: ${missing.joinToString()}.",
                missing
            )
        }

        val manifest = File(context.filesDir, MANIFEST_NAME)
        if (!manifest.isFile) {
            return Inspection(
                State.LEGACY,
                "Core Debian paths are present, but this installation predates the integrity manifest. Provenance cannot be verified."
            )
        }

        return try {
            val properties = Properties().apply {
                manifest.inputStream().use(::load)
            }
            val expected = mapOf(
                "format" to FORMAT_VERSION,
                "distribution" to "debian",
                "release" to "bookworm",
                "architecture" to "arm64",
                "source_url" to SetupManager.ROOTFS_URL,
                "source_sha256" to SetupManager.ROOTFS_SHA256,
                "complete" to "true",
                "rootfs_path" to rootfs.canonicalPath
            )
            val mismatches = expected.filter { (key, value) -> properties.getProperty(key) != value }.keys
            val installedAt = properties.getProperty("installed_at_epoch_ms")?.toLongOrNull()
            if (mismatches.isNotEmpty() || installedAt == null || installedAt <= 0L) {
                Inspection(
                    State.INVALID,
                    "Debian installation record is invalid or does not match this app build (fields: ${(mismatches + if (installedAt == null || installedAt <= 0L) listOf("installed_at_epoch_ms") else emptyList()).joinToString()})."
                )
            } else {
                Inspection(
                    State.VERIFIED,
                    "Debian Bookworm ARM64 installation record matches the pinned archive SHA-256 and active rootfs path. Critical filesystem paths are present. This does not checksum every mutable file."
                )
            }
        } catch (t: Throwable) {
            Inspection(State.INVALID, "Could not read Debian installation record: ${t.message ?: t.javaClass.simpleName}.")
        }
    }
}
