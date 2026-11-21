package io.github.mohammedteir.opencodemobile

import android.content.Context
import java.io.File

object ProotRunner {
    fun start(context: Context, command: List<String>, extraEnv: Map<String, String> = emptyMap()): Process {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val root = File(context.filesDir, "rootfs")
        val tmp = File(context.filesDir, "tmp").apply { mkdirs() }
        val args = mutableListOf(
            File(libDir, "libproot.so").absolutePath,
            "--link2symlink", "-0", "--kill-on-exit",
            "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys"
        )

        // Add the same optional shared-storage view to every PRoot invocation.
        // Android's special access is checked each time, so revocation is respected.
        if (StorageAccessManager.hasAccess(context)) {
            val hostStorage = StorageAccessManager.sharedStorageDirectory(context)
            if (hostStorage != null) {
                val targets = runCatching { StorageAccessManager.prepareMountTargets(root) }
                    .getOrDefault(emptyList())
                for (guestPath in targets) {
                    args += listOf("-b", "${hostStorage.absolutePath}:$guestPath")
                }
            }
        }

        args += listOf("-w", "/root")
        args += command
        return ProcessBuilder(args).apply {
            environment().apply {
                put("PROOT_LOADER", File(libDir, "libproot-loader.so").absolutePath)
                put("PROOT_TMP_DIR", tmp.absolutePath)
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("TERM", "xterm-256color")
                put("LANG", "C.UTF-8")
                putAll(extraEnv)
            }
            redirectErrorStream(true)
        }.start()
    }
}
