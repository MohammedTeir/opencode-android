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

    fun guestPathForDirectory(directory: File): String? {
        val storageRoot = sharedStorageDirectory()?.canonicalFile ?: return null
        val selected = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (!selected.isDirectory || !selected.canRead()) return null
        val rootPath = storageRoot.path
        val selectedPath = selected.path
        if (selectedPath != rootPath && !selectedPath.startsWith(rootPath + File.separator)) return null
        val relative = selected.relativeTo(storageRoot).path.replace(File.separatorChar, '/').trim('/')
        return if (relative.isEmpty()) "/sdcard" else "/sdcard/$relative"
    }

    fun isGuestWorkspaceAvailable(rootfs: File, guestPath: String): Boolean {
        if (guestPath == "/root") return File(rootfs, "root").isDirectory
        if (sharedStorageDirectory() == null) return false
        val normalized = guestPath.trimEnd('/').ifEmpty { "/" }
        val relative = when {
            normalized == "/sdcard" -> ""
            normalized.startsWith("/sdcard/") -> normalized.removePrefix("/sdcard/")
            normalized == "/storage/emulated/0" -> ""
            normalized.startsWith("/storage/emulated/0/") -> normalized.removePrefix("/storage/emulated/0/")
            else -> return false
        }
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || '\u0000' in it }) return false
        val base = File(rootfs, "sdcard").canonicalFile
        val target = runCatching { File(base, parts.joinToString(File.separator)).canonicalFile }.getOrNull() ?: return false
        return target.isDirectory && (target.path == base.path || target.path.startsWith(base.path + File.separator))
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
