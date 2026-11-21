package io.github.mohammedteir.opencodemobile

import android.content.Context
import java.io.File

/**
 * Read-only inventory of the independently managed runtime components.
 *
 * This class deliberately does not delete, overwrite, download, or repair anything.
 * It gives diagnostics a trustworthy component-level view before repair actions are added.
 */
class RuntimeManager(context: Context) {
    enum class State { READY, DEGRADED, MISSING, INCOMPLETE }

    data class Component(
        val id: String,
        val label: String,
        val state: State,
        val detail: String,
        val repairHint: String
    )

    data class Snapshot(val components: List<Component>) {
        val isHealthy: Boolean
            get() = components.all { it.state == State.READY }

        fun report(): String = buildString {
            appendLine("=== Runtime component inventory ===")
            components.forEach { component ->
                appendLine("[${component.state}] ${component.label}")
                appendLine("  ${component.detail}")
                if (component.state != State.READY) {
                    appendLine("  Next: ${component.repairHint}")
                }
            }
            append("Overall: ")
            append(if (isHealthy) "all checked components are ready" else "one or more components need attention")
        }
    }

    private val appFiles = context.filesDir
    private val nativeLibraries = File(context.applicationInfo.nativeLibraryDir)
    private val rootfs = File(appFiles, "rootfs")

    fun inspect(): Snapshot {
        val components = mutableListOf<Component>()

        val proot = File(nativeLibraries, "libproot.so")
        val loader = File(nativeLibraries, "libproot-loader.so")
        val missingNative = listOfNotNull(
            "libproot.so".takeUnless { proot.isFile && proot.canRead() },
            "libproot-loader.so".takeUnless { loader.isFile && loader.canRead() }
        )
        components += if (missingNative.isEmpty()) {
            Component(
                "proot",
                "Bundled PRoot",
                State.READY,
                "PRoot executable and loader are present in the installed APK.",
                "Reinstall the application APK; these files are packaged with the app."
            )
        } else {
            Component(
                "proot",
                "Bundled PRoot",
                State.MISSING,
                "Missing or unreadable native runtime file(s): ${missingNative.joinToString()}.",
                "Reinstall the application APK. Do not download replacement native binaries from an untrusted source."
            )
        }

        val requiredRootfsFiles = listOf(
            "bin/bash",
            "usr/bin/env",
            "etc/os-release",
            "etc/apt"
        )
        val missingRootfs = requiredRootfsFiles.filterNot { File(rootfs, it).exists() }
        val rootfsState = when {
            !rootfs.isDirectory -> State.MISSING
            missingRootfs.isNotEmpty() -> State.INCOMPLETE
            else -> State.READY
        }
        components += Component(
            "debian",
            "Debian ARM64 root filesystem",
            rootfsState,
            when {
                !rootfs.isDirectory -> "Private Debian rootfs directory is not installed."
                missingRootfs.isNotEmpty() -> "Required rootfs paths are missing: ${missingRootfs.joinToString()}."
                else -> "Core Debian filesystem paths are present. This is a structural check, not a full filesystem checksum."
            },
            "Use the Debian repair/reinstall workflow after reviewing its impact. Never remove the existing rootfs automatically because it may contain user data."
        )

        val openCode = File(rootfs, "usr/local/bin/opencode")
        val versionFile = File(appFiles, "setup_ready")
        val recordedVersion = versionFile.takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            .orEmpty()
        val validVersion = VERSION_PATTERN.matches(recordedVersion)
        val openCodeState = when {
            !openCode.isFile || !openCode.canExecute() -> State.MISSING
            !validVersion -> State.DEGRADED
            else -> State.READY
        }
        components += Component(
            "opencode",
            "OpenCode CLI",
            openCodeState,
            when {
                !openCode.isFile -> "OpenCode executable is missing."
                !openCode.canExecute() -> "OpenCode file exists but is not executable."
                !validVersion -> "OpenCode binary exists, but installed-version metadata is missing or invalid."
                else -> "Executable is present; installed version metadata: $recordedVersion."
            },
            "Reinstall or update only the OpenCode component, preserving the Debian rootfs, configuration, and projects."
        )

        val requiredTools = linkedMapOf(
            "git" to "usr/bin/git",
            "ripgrep" to "usr/bin/rg",
            "curl" to "usr/bin/curl",
            "CA certificates" to "etc/ssl/certs/ca-certificates.crt",
            "procps" to "usr/bin/ps",
            "OpenSSH client" to "usr/bin/ssh",
            "unzip" to "usr/bin/unzip"
        )
        val missingTools = requiredTools.filterValues { !File(rootfs, it).isFile }.keys
        val toolsState = when {
            !rootfs.isDirectory -> State.INCOMPLETE
            missingTools.isNotEmpty() -> State.DEGRADED
            else -> State.READY
        }
        components += Component(
            "linux-tools",
            "Required Debian tools",
            toolsState,
            if (missingTools.isEmpty()) {
                "Git, ripgrep, curl, CA certificates, procps, OpenSSH client, and unzip are present."
            } else {
                "Missing tool(s): ${missingTools.joinToString()}. This check verifies expected files, not package database consistency."
            },
            "Run a package-only repair using the active Android network; do not reinstall Debian just to restore missing packages."
        )

        val configDirectory = File(rootfs, "root/.config/opencode")
        val missingConfig = listOf("opencode.json", "AGENTS.md")
            .filterNot { File(configDirectory, it).isFile }
        components += Component(
            "opencode-config",
            "OpenCode default configuration",
            if (missingConfig.isEmpty()) State.READY else State.DEGRADED,
            if (missingConfig.isEmpty()) {
                "Default configuration and agent guidance files are present."
            } else {
                "Default file(s) missing: ${missingConfig.joinToString()}. Existing user files are never overwritten by this check."
            },
            "Restore only missing defaults after checking that no user configuration would be replaced."
        )

        return Snapshot(components)
    }

    companion object {
        private val VERSION_PATTERN = Regex("\\d+\\.\\d+(?:\\.\\d+)?")
    }
}
