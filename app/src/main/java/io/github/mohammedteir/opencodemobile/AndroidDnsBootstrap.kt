package io.github.mohammedteir.opencodemobile

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.regex.Pattern

/**
 * Refreshes the Debian resolver configuration using Android's per-network resolver.
 *
 * Android's resolver can succeed even when DNS packets sent directly by a PRoot
 * process cannot reach the configured DNS server. A managed /etc/hosts block
 * provides a fallback for common OpenCode provider API endpoints. Entries are
 * refreshed before each PRoot process launch; no provider IP is hardcoded.
 */
object AndroidDnsBootstrap {
    private const val MARKER_START = "# OPENCODE-MOBILE-ANDROID-DNS-START"
    private const val MARKER_END = "# OPENCODE-MOBILE-ANDROID-DNS-END"

    private val providerHosts = listOf(
        // Google Gemini
        "generativelanguage.googleapis.com",
        // OpenAI
        "api.openai.com",
        // Anthropic
        "api.anthropic.com",
        // Common OpenAI-compatible providers
        "openrouter.ai",
        "api.groq.com",
        "api.mistral.ai",
        "api.cohere.ai",
        "api.deepseek.com",
        "api.x.ai",
        // GitHub is used by common agent/tool workflows
        "api.github.com"
    )

    fun refresh(context: Context, rootfs: File, log: (String) -> Unit = {}) {
        val etc = File(rootfs, "etc").toPath()
        if (!Files.isDirectory(etc, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(etc)) {
            log("Android DNS refresh skipped: Debian /etc is unavailable.")
            return
        }

        val connectivity = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }.getOrNull()
        val network = runCatching { connectivity?.activeNetwork }.getOrNull()
        val linkProperties = runCatching { connectivity?.getLinkProperties(network) }.getOrNull()
        val dnsServers = linkProperties?.dnsServers.orEmpty()
            .map { it.hostAddress.substringBefore('%') }
            .filter(::isUsableDnsAddress)
            .distinct()

        if (dnsServers.isNotEmpty()) {
            val resolv = etc.resolve("resolv.conf")
            runCatching {
                if (Files.isSymbolicLink(resolv)) Files.delete(resolv)
                Files.write(
                    resolv,
                    (dnsServers.joinToString("\n") { "nameserver $it" } + "\n")
                        .toByteArray(Charsets.UTF_8)
                )
            }.onFailure {
                log("Android DNS refresh could not update resolv.conf: ${it.message}")
            }
        }

        val resolved = providerHosts.flatMap { host ->
            runCatching {
                val addresses = if (network != null) network.getAllByName(host)
                    else InetAddress.getAllByName(host)
                addresses.map { address -> address.hostAddress.substringBefore('%') to host }
            }.getOrElse {
                log("Android resolver could not resolve $host: ${it.message}")
                emptyList()
            }
        }.distinct()

        val hosts = etc.resolve("hosts")
        runCatching {
            if (Files.isSymbolicLink(hosts)) Files.delete(hosts)
            val existing = if (Files.exists(hosts, LinkOption.NOFOLLOW_LINKS)) {
                String(Files.readAllBytes(hosts), Charsets.UTF_8)
            } else {
                "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
            }
            val markerPattern = "(?s)\\n?" + Pattern.quote(MARKER_START) +
                ".*?" + Pattern.quote(MARKER_END) + "\\n?"
            val clean = existing.replace(Regex(markerPattern), "\n")
            val block = buildString {
                append(MARKER_START).append('\n')
                resolved.forEach { (address, host) ->
                    append(address).append(' ').append(host).append('\n')
                }
                append(MARKER_END).append('\n')
            }
            Files.write(hosts, (clean.trimEnd() + "\n" + block).toByteArray(Charsets.UTF_8))
        }.onFailure {
            log("Android DNS refresh could not update /etc/hosts: ${it.message}")
        }

        log(
            "Android DNS refresh: ${dnsServers.size} upstream DNS server(s), " +
                "${resolved.size} provider/API address record(s)."
        )
    }

    private fun isUsableDnsAddress(address: String): Boolean = runCatching {
        val parsed = InetAddress.getByName(address)
        !parsed.isAnyLocalAddress && !parsed.isLoopbackAddress &&
            !parsed.isLinkLocalAddress && !parsed.isMulticastAddress
    }.getOrDefault(false)
}
