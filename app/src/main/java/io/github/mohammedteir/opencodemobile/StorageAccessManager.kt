package io.github.mohammedteir.opencodemobile

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.nio.file.Files

/**
 * Optional bridge from Android's primary shared storage into the Debian rootfs.
 * Direct mounting is supported on Android 11+ with user-granted all-files access.
 */
object StorageAccessManager {
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasAccess(context: Context): Boolean {
        if (!isSupported()) return false
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun sharedStorageDirectory(context: Context): File? {
        if (!hasAccess(context)) return null
        return runCatching { Environment.getExternalStorageDirectory() }
            .getOrNull()
            ?.takeIf { it.isDirectory && it.canRead() }
    }

    /** Create safe, app-owned bind destinations and refuse symlink collisions. */
    fun prepareMountTargets(rootfs: File): List<String> {
        check(rootfs.isDirectory) { "Debian root filesystem is not installed." }
        ensureDirectory(rootfs, listOf("sdcard"))
        ensureDirectory(rootfs, listOf("storage", "emulated", "0"))
        return listOf("/sdcard", "/storage/emulated/0")
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
