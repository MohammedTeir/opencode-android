package io.github.mohammedteir.opencodemobile

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.net.Network
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Keeps Debian's normal libc resolver pointed at DNS servers advertised by
 * Android's active network. This is deliberately provider-agnostic: Debian,
 * Node.js/Bun, Git, APT, MCP clients and custom API endpoints all use the same
 * resolver configuration.
 *
 * Android's Network.getAllByName is used only for diagnostics/bootstrap tasks
 * that explicitly need Android-side resolution. It is not used to maintain a
 * hardcoded provider /etc/hosts allowlist.
 */
object AndroidDnsBootstrap {
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
            .filter(::isUsableDnsAddress)
            .distinct()
        return Snapshot(
            networkAvailable = network != null,
            dnsServers = dnsServers,
            privateDnsActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && properties?.isPrivateDnsActive == true,
            privateDnsServerName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) properties?.privateDnsServerName else null
        )
    }

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
            return
        }

        runCatching {
            if (Files.isSymbolicLink(resolv)) Files.delete(resolv)
            val content = buildString {
                append("# Managed by OpenCode Mobile from Android active-network LinkProperties\n")
                state.dnsServers.forEach { append("nameserver ").append(it).append('\n') }
                append("options timeout:2 attempts:2 rotate\n")
            }
            Files.write(resolv, content.toByteArray(Charsets.UTF_8))
        }.onFailure {
            log("Android DNS refresh could not update resolv.conf: ${it.message}")
            return
        }

        log(
            "Android DNS configuration refreshed for all Debian processes: " +
                "${state.dnsServers.size} active-network DNS server(s); " +
                "Private DNS ${if (state.privateDnsActive) "enabled" else "not reported as active"}" +
                (state.privateDnsServerName?.let { " ($it)" } ?: "") + "."
        )
        if (state.privateDnsActive) {
            log(
                "Note: Android Private DNS may use encrypted DNS internally. Direct DNS packets from " +
                    "Linux userspace are not guaranteed to inherit Android's Private DNS transport."
            )
        }
    }

    /**
     * Diagnostic helper: resolve a hostname using Android's active network.
     * It is intentionally not used to populate /etc/hosts for a provider list.
     */
    fun resolveOnActiveNetwork(context: Context, hostname: String): List<String> {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val addresses = if (network != null) network.getAllByName(hostname) else InetAddress.getAllByName(hostname)
        return addresses.map { it.hostAddress.substringBefore('%') }.distinct()
    }

    private fun isUsableDnsAddress(address: String): Boolean = runCatching {
        val parsed = InetAddress.getByName(address)
        !parsed.isAnyLocalAddress && !parsed.isLoopbackAddress &&
            !parsed.isLinkLocalAddress && !parsed.isMulticastAddress
    }.getOrDefault(false)
}
