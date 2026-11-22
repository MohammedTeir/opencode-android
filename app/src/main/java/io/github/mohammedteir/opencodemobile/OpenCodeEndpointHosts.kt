package io.github.mohammedteir.opencodemobile

import java.net.URI
import java.util.Locale

/**
 * Hostnames OpenCode may contact even when Debian's direct DNS transport is
 * unavailable. Custom provider base URLs are added from the user's config.
 */
internal object OpenCodeEndpointHosts {
    private val builtInHosts = listOf(
        "generativelanguage.googleapis.com",
        "aiplatform.googleapis.com",
        "api.openai.com",
        "api.anthropic.com",
        "openrouter.ai",
        "api.groq.com",
        "api.mistral.ai",
        "api.cohere.ai",
        "api.deepseek.com",
        "api.x.ai",
        "api.github.com",
        "github.com"
    )

    private val baseUrlPattern = Regex(
        """(?i)["'](?:baseURL|baseUrl|base_url)["']\\s*:\\s*["'](https?://[^"'\\s]+)["']"""
    )

    fun collect(configText: String? = null): List<String> {
        val configuredHosts = baseUrlPattern.findAll(configText.orEmpty())
            .mapNotNull { match -> hostnameFromUrl(match.groupValues[1]) }
            .toList()
        return (builtInHosts + configuredHosts).distinct()
    }

    internal fun hostnameFromUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host.length > 253 || host.isBlank()) return null
        if (host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        if (host.split('.').any { it.isBlank() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
        host
    }.getOrNull()
}
