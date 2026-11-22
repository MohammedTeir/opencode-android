package io.github.mohammedteir.opencodemobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bridges glibc's NSS hostname lookups in the Debian PRoot to Android's active-network resolver.
 * The guest module speaks a small line protocol over an abstract local Unix socket, so no
 * privileged DNS port, public resolver, root access, or VPN replacement is needed.
 */
object AndroidDnsResolver {
    private const val TAG = "OpenCodeDns"
    private const val SOCKET_NAME = "io.github.mohammedteir.opencodemobile.android-dns"
    private const val NSS_MODULE_ASSET = "libnss_android.so.2"
    private const val NSS_MODULE_PATH = "usr/lib/aarch64-linux-gnu/libnss_android.so.2"
    private val lock = Any()
    @Volatile private var server: LocalServerSocket? = null

    private val workers = ThreadPoolExecutor(
        4, 4, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "OpenCode-Android-DNS-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    /** Start or reuse the app-process resolver and install its glibc NSS shim in Debian. */
    fun prepare(context: Context, rootfs: File, log: (String) -> Unit = {}): Boolean {
        val appContext = context.applicationContext
        val serverReady = runCatching { ensureServer(appContext) }
            .map { true }
            .onFailure { log("Android-backed DNS bridge could not start: ${it.message}; Debian DNS fallback remains enabled.") }
            .getOrDefault(false)
        val moduleInstalled = runCatching { installGuestNssModule(appContext, rootfs) }
            .onSuccess { log("Android-backed DNS via Debian glibc NSS is enabled for arbitrary hostname lookups.") }
            .onFailure { log("Android-backed glibc resolver could not be installed: ${it.message}; Debian DNS fallback remains enabled.") }
            .isSuccess
        return serverReady && moduleInstalled
    }

    private fun ensureServer(context: Context): LocalServerSocket = synchronized(lock) {
        val existing = server
        if (existing != null) return existing

        val listener = LocalServerSocket(SOCKET_NAME)
        server = listener
        Thread({ acceptLoop(listener, context) }, "OpenCode-Android-DNS-accept").apply {
            isDaemon = true
            start()
        }
        listener
    }

    private fun acceptLoop(listener: LocalServerSocket, context: Context) {
        while (server === listener) {
            val client = try {
                listener.accept()
            } catch (error: Exception) {
                if (server === listener) Log.w(TAG, "DNS bridge accept failed", error)
                break
            }
            try {
                workers.execute { handleRequest(client, context) }
            } catch (error: Exception) {
                runCatching { client.close() }
                Log.w(TAG, "DNS bridge worker rejected a request", error)
            }
        }
        synchronized(lock) {
            if (server === listener) {
                server = null
            }
        }
        runCatching { listener.close() }
    }

    private fun handleRequest(client: LocalSocket, context: Context) {
        client.use { socket ->
            runCatching {
                if (socket.peerCredentials.uid != Process.myUid()) return@runCatching
                socket.soTimeout = 3_000
                val request = socket.inputStream.bufferedReader(Charsets.US_ASCII).readLine()
                    ?: return@runCatching
                val separator = request.indexOf(' ')
                if (separator <= 0) {
                    writeResponse(socket, "ERR\n")
                    return@runCatching
                }
                val queryType = request.substring(0, separator)
                val hostname = request.substring(separator + 1).trimEnd('.')
                if (queryType !in setOf("A", "AAAA") || !isValidHostname(hostname)) {
                    writeResponse(socket, "ERR\n")
                    return@runCatching
                }
                val addresses = resolveOnAndroidNetwork(context, hostname)
                    .filter { address ->
                        when (queryType) {
                            "A" -> address is Inet4Address
                            else -> address is Inet6Address
                        }
                    }
                    .filterNot { address ->
                        address.isAnyLocalAddress || address.isLoopbackAddress ||
                            address.isLinkLocalAddress || address.isMulticastAddress
                    }
                    .map { it.hostAddress.substringBefore('%') }
                    .distinct()
                if (addresses.isEmpty()) writeResponse(socket, "NOTFOUND\n")
                else writeResponse(socket, "OK ${addresses.joinToString(" ")}\n")
            }.onFailure { error ->
                Log.d(TAG, "Android-backed lookup failed: ${error.message}")
                runCatching { writeResponse(socket, "UNAVAIL\n") }
            }
        }
    }

    private fun writeResponse(socket: LocalSocket, response: String) {
        socket.outputStream.write(response.toByteArray(Charsets.US_ASCII))
        socket.outputStream.flush()
    }

    private fun resolveOnAndroidNetwork(context: Context, hostname: String): List<InetAddress> {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = runCatching { connectivity?.activeNetwork }.getOrNull()
        return if (network != null) network.getAllByName(hostname).toList() else InetAddress.getAllByName(hostname).toList()
    }

    private fun isValidHostname(hostname: String): Boolean {
        if (hostname.isBlank() || hostname.length > 253 || hostname.any { it <= ' ' || it == '/' || it == '\\' }) return false
        return hostname.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
                label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }

    private fun installGuestNssModule(context: Context, rootfs: File) {
        val module = File(rootfs, NSS_MODULE_PATH)
        val parent = module.parentFile ?: error("Invalid NSS module path")
        check(parent.isDirectory || parent.mkdirs()) { "Could not create Debian NSS module directory." }
        check(!Files.isSymbolicLink(parent.toPath())) { "Refusing to install the NSS module through a symlinked directory." }
        val temporary = File(parent, module.name + ".tmp")
        try {
            context.assets.open(NSS_MODULE_ASSET).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            Os.chmod(temporary.absolutePath, 493)
            Files.move(
                temporary.toPath(), module.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), module.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temporary.exists()) temporary.delete()
        }

        val nsswitch = File(rootfs, "etc/nsswitch.conf")
        check(!Files.isSymbolicLink(nsswitch.toPath())) { "Refusing to modify a symlinked Debian nsswitch.conf." }
        val original = if (nsswitch.isFile) nsswitch.readText() else ""
        val lines = original.lines().toMutableList()
        val hostsLine = lines.indexOfFirst { it.trimStart().startsWith("hosts:") }
        if (hostsLine < 0) {
            if (lines.lastOrNull().isNullOrBlank()) lines.removeAll { it.isEmpty() }
            lines += "hosts: files android dns"
        } else {
            val line = lines[hostsLine]
            val match = Regex("^(\\s*hosts:\\s*)(.*)$").matchEntire(line)
                ?: error("Could not parse Debian hosts NSS configuration.")
            val prefix = match.groupValues[1]
            val sources = match.groupValues[2].trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if ("android" !in sources) {
                val insertionIndex = (sources.indexOf("files") + 1).coerceAtLeast(0)
                val updated = sources.toMutableList().apply { add(insertionIndex, "android") }
                lines[hostsLine] = prefix + updated.joinToString(" ")
            }
        }
        val updatedConfig = lines.joinToString("\n").trimEnd() + "\n"
        if (updatedConfig != original) {
            val tempConfig = File(nsswitch.parentFile, nsswitch.name + ".opencode-tmp")
            tempConfig.writeText(updatedConfig)
            try {
                Files.move(
                    tempConfig.toPath(), nsswitch.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(tempConfig.toPath(), nsswitch.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
