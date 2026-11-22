package io.github.mohammedteir.opencodemobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps Debian's resolver aligned with Android's active network.
 *
 * Some Android/VPN/Private-DNS configurations allow Android's per-network
 * resolver to resolve names but do not allow raw DNS packets from Linux
 * userspace to use the advertised upstream servers. The Android-backed NSS
 * bridge handles normal glibc lookups; this refreshed hosts-file fallback keeps
 * provider endpoints available to software that bypasses glibc NSS.
 */
object AndroidDnsBootstrap {
    private const val HOSTS_MARKER_START = "# OPENCODE-MOBILE-ANDROID-DNS-START"
    private const val HOSTS_MARKER_END = "# OPENCODE-MOBILE-ANDROID-DNS-END"
    private const val RESOLVER_HEADER = "# Managed by OpenCode Mobile from Android active-network LinkProperties"
    private val monitorLock = Any()
    private val refreshGeneration = AtomicLong(0)
    private val refreshExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "OpenCode-Android-DNS-refresh").apply { isDaemon = true }
    }
    @Volatile private var monitoredContext: Context? = null
    @Volatile private var monitoredRootfs: File? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    data class Snapshot(
        val networkAvailable: Boolean,
        val dnsServers: List<String>,
        val privateDnsActive: Boolean,
        val privateDnsServerName: String?
    )

    fun snapshot(context: Context): Snapshot {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network: Network? = runCatching { connectivity?.activeNetwork }.getOrNull()
        val properties = runCatching { connectivity?.getLinkProperties(network) }.getOrNull()
        val dnsServers = properties?.dnsServers.orEmpty()
            .mapNotNull { address -> address.hostAddress?.substringBefore('%') }
            .filter(DnsAddressValidator::isUsable)
            .distinct()
        return Snapshot(
            networkAvailable = network != null,
            dnsServers = dnsServers,
            privateDnsActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && properties?.isPrivateDnsActive == true,
            privateDnsServerName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) properties?.privateDnsServerName else null
        )
    }

    /**
     * Monitor Android's default network for the lifetime of the app process.
     * All callbacks enqueue a debounced refresh; the refresh always re-reads
     * activeNetwork rather than trusting the Network delivered by an old callback.
     */
    fun startNetworkMonitoring(context: Context, rootfs: File, log: (String) -> Unit = {}) {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivity == null) {
            log("Android DNS monitor unavailable: ConnectivityManager is missing.")
            return
        }
        synchronized(monitorLock) {
            monitoredContext = appContext
            monitoredRootfs = rootfs
            if (networkCallback == null) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = scheduleMonitoredRefresh()
                    override fun onLost(network: Network) = scheduleMonitoredRefresh()
                    override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                        scheduleMonitoredRefresh()
                }
                try {
                    connectivity.registerDefaultNetworkCallback(callback)
                    networkCallback = callback
                    log("Android active-network DNS monitoring started.")
                } catch (error: Exception) {
                    log("Android DNS monitor could not register: ${error.message}")
                }
            }
        }
        scheduleMonitoredRefresh()
    }

    private fun scheduleMonitoredRefresh() {
        val generation = refreshGeneration.incrementAndGet()
        val context = monitoredContext ?: return
        val rootfs = monitoredRootfs ?: return
        refreshExecutor.schedule({
            if (refreshGeneration.get() == generation) {
                refresh(context, rootfs) { message -> android.util.Log.i("OpenCodeDns", message) }
            }
        }, 350, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun refresh(context: Context, rootfs: File, log: (String) -> Unit = {}) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = runCatching { connectivity?.activeNetwork }.getOrNull()
        val properties = runCatching { if (network != null) connectivity?.getLinkProperties(network) else null }.getOrNull()
        val dnsServers = properties?.dnsServers.orEmpty()
            .mapNotNull { address -> address.hostAddress?.substringBefore('%') }
            .filter(DnsAddressValidator::isUsable)
            .distinct()
        val state = Snapshot(
            networkAvailable = network != null,
            dnsServers = dnsServers,
            privateDnsActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && properties?.isPrivateDnsActive == true,
            privateDnsServerName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) properties?.privateDnsServerName else null
        )

        val etc = File(rootfs, "etc").toPath()
        if (!Files.isDirectory(etc, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(etc)) {
            log("Android DNS refresh skipped: Debian /etc is unavailable.")
            return
        }

        // Do not apply a DNS snapshot after Android has already switched networks.
        if (connectivity?.activeNetwork != network) {
            log("Android DNS refresh skipped: active network changed while reading DNS configuration.")
            scheduleMonitoredRefresh()
            return
        }

        val resolv = etc.resolve("resolv.conf")
        runCatching {
            val existing = if (Files.exists(resolv, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(resolv)) {
                String(Files.readAllBytes(resolv), Charsets.UTF_8)
            } else ""
            // Replace every old nameserver (including manually configured public DNS),
            // while retaining search/domain/options and other administrator directives.
            val preservedLines = existing.lineSequence()
                .filterNot { Regex("^\s*nameserver\s+", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .filterNot { it.trim() == RESOLVER_HEADER }
                .toMutableList()
            if (preservedLines.none { Regex("^\s*options\s+", RegexOption.IGNORE_CASE).containsMatchIn(it) }) {
                preservedLines += "options timeout:2 attempts:2 rotate"
            }
            val resolverContent = buildString {
                append(RESOLVER_HEADER).append('\n')
                preservedLines.filter(String::isNotBlank).forEach { append(it).append('\n') }
                state.dnsServers.forEach { append("nameserver ").append(it).append('\n') }
            }
            if (connectivity?.activeNetwork != network) {
                log("Android DNS refresh skipped before write: active network changed.")
                scheduleMonitoredRefresh()
            } else {
                val committed = writeAtomically(resolv, resolverContent) { connectivity?.activeNetwork == network }
                if (!committed) {
                    log("Android DNS refresh skipped at commit: active network changed.")
                    scheduleMonitoredRefresh()
                }
            }
        }.onFailure {
            log("Android DNS refresh could not update resolv.conf: " + it.message)
        }

        val configFile = File(rootfs, "root/.config/opencode/opencode.json")
        val configText = runCatching { if (configFile.isFile) configFile.readText() else null }.getOrNull()
        val endpointHosts = OpenCodeEndpointHosts.collect(configText)
        val executor = Executors.newFixedThreadPool(minOf(4, endpointHosts.size.coerceAtLeast(1)))
        val lookupResults = try {
            executor.invokeAll(
                endpointHosts.map { host ->
                    Callable {
                        val entries = runCatching {
                            val addresses = if (network != null) network.getAllByName(host) else emptyArray()
                            addresses.filterNot { address ->
                                address.isAnyLocalAddress || address.isLoopbackAddress ||
                                    address.isLinkLocalAddress || address.isMulticastAddress
                            }.map { address -> address.hostAddress.substringBefore('%') to host }
                        }.getOrDefault(emptyList())
                        host to entries
                    }
                },
                8,
                TimeUnit.SECONDS
            ).mapNotNull { future -> runCatching { future.get() }.getOrNull() }
        } finally {
            executor.shutdownNow()
        }
        val resolvedEntries = lookupResults.flatMap { it.second }.distinct()
        val successfulHosts = lookupResults.filter { it.second.isNotEmpty() }.map { it.first }.toSet()
        val failedLookups = endpointHosts.size - successfulHosts.size

        val hosts = etc.resolve("hosts")
        runCatching {
            if (connectivity?.activeNetwork != network) {
                log("Android endpoint-host refresh skipped: active network changed.")
                scheduleMonitoredRefresh()
                return@runCatching
            }
            if (Files.isSymbolicLink(hosts)) Files.delete(hosts)
            val existing = if (Files.exists(hosts, LinkOption.NOFOLLOW_LINKS)) {
                String(Files.readAllBytes(hosts), Charsets.UTF_8)
            } else {
                "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
            }
            val markerPattern = "(?s)\\n?" + java.util.regex.Pattern.quote(HOSTS_MARKER_START) +
                ".*?" + java.util.regex.Pattern.quote(HOSTS_MARKER_END) + "\\n?"
            val clean = existing.replace(Regex(markerPattern), "\n")
            val managedBlock = buildString {
                append(HOSTS_MARKER_START).append('\n')
                resolvedEntries.forEach { (address, host) ->
                    append(address).append(' ').append(host).append('\n')
                }
                append(HOSTS_MARKER_END).append('\n')
            }
            val committed = writeAtomically(hosts, clean.trimEnd() + "\n" + managedBlock) {
                connectivity?.activeNetwork == network
            }
            if (!committed) {
                log("Android endpoint-host refresh skipped at commit: active network changed.")
                scheduleMonitoredRefresh()
            }
        }.onFailure {
            log("Android DNS refresh could not update Debian /etc/hosts: " + it.message)
        }

        log(
            "Android DNS refresh: " + state.dnsServers.size + " active-network DNS server(s); " +
                resolvedEntries.size + " Android-resolved OpenCode endpoint address record(s); " +
                failedLookups + " endpoint lookup(s) failed."
        )
        if (!state.networkAvailable) {
            log("Android has no active network; stale Debian nameserver entries were cleared.")
        }
        if (state.privateDnsActive) {
            log(
                "Android Private DNS is active" +
                    (state.privateDnsServerName?.let { " (" + it + ")" } ?: "") +
                    "; Linux userspace DNS packets do not automatically inherit Android's encrypted DNS transport."
            )
        }
    }

    private fun writeAtomically(
        path: java.nio.file.Path,
        content: String,
        shouldCommit: () -> Boolean = { true }
    ): Boolean {
        val temporary = path.resolveSibling(path.fileName.toString() + ".opencode-tmp")
        if (Files.isSymbolicLink(temporary)) Files.delete(temporary)
        Files.write(temporary, content.toByteArray(Charsets.UTF_8))
        if (!shouldCommit()) {
            Files.deleteIfExists(temporary)
            return false
        }
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            if (!shouldCommit()) {
                Files.deleteIfExists(temporary)
                return false
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    }

    /** Resolve a hostname using Android's active network for diagnostics. */
    fun resolveOnActiveNetwork(context: Context, hostname: String): List<String> {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val addresses = if (network != null) network.getAllByName(hostname) else InetAddress.getAllByName(hostname)
        return addresses.map { it.hostAddress.substringBefore('%') }.distinct()
    }
}
