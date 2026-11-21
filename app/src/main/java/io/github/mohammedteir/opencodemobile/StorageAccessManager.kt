package io.github.mohammedteir.opencodemobile

import android.os.Build
import android.os.Environment
import java.io.File
import java.nio.file.Files

object StorageAccessManager {
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @Suppress("DEPRECATION")
    fun sharedStorageDirectory(): File? {
        if (!isSupported()) return null
        val hasAllFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        if (!hasAllFilesAccess) return null
        return runCatching { Environment.getExternalStorageDirectory() }
            .getOrNull()
            ?.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
    }

    fun prepareMountTargets(rootfs: File): List<String> {
        check(rootfs.isDirectory) { "Debian root filesystem is not installed." }
        ensureDirectory(rootfs, listOf("sdcard"))
        ensureDirectory(rootfs, listOf("storage", "emulated", "0"))
        return listOf("/sdcard", "/storage/emulated/0")
    }

    fun prepareDefaultWorkspace(rootfs: File): File {
        val root = File(rootfs, "root")
        check(!Files.isSymbolicLink(root.toPath())) { "Debian /root must not be a symbolic link." }
        val projects = File(root, "projects")
        check(!Files.isSymbolicLink(projects.toPath())) { "Debian projects directory must not be a symbolic link." }
        check(projects.isDirectory || projects.mkdirs()) { "Could not create Debian /root/projects." }
        return projects
    }

    fun guestPathForDirectory(directory: File, hostRoot: File, guestRoot: String): String? {
        val root = runCatching { hostRoot.canonicalFile }.getOrNull() ?: return null
        val selected = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (!selected.isDirectory || !selected.canRead() || !selected.canWrite()) return null
        if (selected.path != root.path && !selected.path.startsWith(root.path + File.separator)) return null
        val relative = selected.relativeTo(root).path.replace(File.separatorChar, '/').trim('/')
        return if (relative.isEmpty()) guestRoot else "$guestRoot/$relative"
    }

    fun isGuestWorkspaceAvailable(rootfs: File, guestPath: String): Boolean {
        val normalized = normalizeGuestPath(guestPath) ?: return false
        return when {
            normalized == "/root" || normalized.startsWith("/root/") -> {
                val root = File(rootfs, "root")
                isSafeDirectory(root, normalized.removePrefix("/root").trimStart('/'))
            }
            normalized == "/sdcard" || normalized.startsWith("/sdcard/") ||
                normalized == "/storage/emulated/0" || normalized.startsWith("/storage/emulated/0/") -> {
                val storageRoot = sharedStorageDirectory()?.canonicalFile ?: return false
                val relative = when {
                    normalized == "/sdcard" || normalized == "/storage/emulated/0" -> ""
                    normalized.startsWith("/sdcard/") -> normalized.removePrefix("/sdcard/")
                    else -> normalized.removePrefix("/storage/emulated/0/")
                }
                isSafeDirectory(storageRoot, relative)
            }
            else -> false
        }
    }

    private fun normalizeGuestPath(path: String): String? {
        if (!path.startsWith("/") || '\u0000' in path) return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return null
        return "/" + parts.joinToString("/")
    }

    private fun isSafeDirectory(root: File, relative: String): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val target = runCatching {
            if (relative.isBlank()) canonicalRoot else File(canonicalRoot, relative).canonicalFile
        }.getOrNull() ?: return false
        return target.isDirectory && target.canRead() && target.canWrite() &&
            (target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator))
    }

    private fun ensureDirectory(rootfs: File, components: List<String>) {
        var current = rootfs
        for (component in components) {
            current = File(current, component)
            check(!Files.isSymbolicLink(current.toPath())) {
                "Refusing shared-storage bind target that is a symbolic link: $current"
            }
            if (current.exists()) {
                check(current.isDirectory) { "Shared-storage bind target is not a directory: $current" }
            } else {
                check(current.mkdir() || current.isDirectory) {
                    "Could not create shared-storage bind target: $current"
                }
            }
        }
    }
}
