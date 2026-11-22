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

    @Synchronized
    fun refresh(context: Context, rootfs: File, log: (String) -> Unit = {}) {
        val etc = File(rootfs, "etc").toPath()
        if (!Files.isDirectory(etc, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(etc)) {
            log("Android DNS refresh skipped: Debian /etc is unavailable.")
            return
        }

        val state = snapshot(context)
        val resolv = etc.resolve("resolv.conf")
        if (state.dnsServers.isEmpty()) {
            log(
                "Android DNS refresh: no usable DNS servers were advertised by the active network; " +
                    "existing Debian resolver configuration was preserved."
            )
        } else {
            val resolverContent = buildString {
                append("# Managed by OpenCode Mobile from Android active-network LinkProperties\n")
                state.dnsServers.forEach { append("nameserver ").append(it).append('\n') }
                append("options timeout:2 attempts:2 rotate\n")
            }
            runCatching {
                if (Files.isSymbolicLink(resolv)) Files.delete(resolv)
                writeAtomically(resolv, resolverContent)
            }.onFailure {
                log("Android DNS refresh could not update resolv.conf: " + it.message)
            }
        }

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = runCatching { connectivity?.activeNetwork }.getOrNull()
        val configFile = File(rootfs, "root/.config/opencode/opencode.json")
        val configText = runCatching { if (configFile.isFile) configFile.readText() else null }.getOrNull()
        val endpointHosts = OpenCodeEndpointHosts.collect(configText)
        val executor = Executors.newFixedThreadPool(minOf(4, endpointHosts.size.coerceAtLeast(1)))
        val lookupResults = try {
            executor.invokeAll(
                endpointHosts.map { host ->
                    Callable {
                        val entries = runCatching {
                            val addresses = if (network != null) network.getAllByName(host)
                                else InetAddress.getAllByName(host)
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
            writeAtomically(hosts, clean.trimEnd() + "\n" + managedBlock)
        }.onFailure {
            log("Android DNS refresh could not update Debian /etc/hosts: " + it.message)
        }

        log(
            "Android DNS refresh: " + state.dnsServers.size + " upstream DNS server(s); " +
                resolvedEntries.size + " Android-resolved OpenCode endpoint address record(s); " +
                failedLookups + " endpoint lookup(s) failed."
        )
        if (state.privateDnsActive) {
            log(
                "Android Private DNS is active" +
                    (state.privateDnsServerName?.let { " (" + it + ")" } ?: "") +
                    "; Linux userspace DNS packets do not automatically inherit Android's encrypted DNS transport."
            )
        }
    }

    private fun writeAtomically(path: java.nio.file.Path, content: String) {
        val temporary = path.resolveSibling(path.fileName.toString() + ".opencode-tmp")
        if (Files.isSymbolicLink(temporary)) Files.delete(temporary)
        Files.write(temporary, content.toByteArray(Charsets.UTF_8))
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Resolve a hostname using Android's active network for diagnostics. */
    fun resolveOnActiveNetwork(context: Context, hostname: String): List<String> {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val addresses = if (network != null) network.getAllByName(hostname) else InetAddress.getAllByName(hostname)
        return addresses.map { it.hostAddress.substringBefore('%') }.distinct()
    }
}
