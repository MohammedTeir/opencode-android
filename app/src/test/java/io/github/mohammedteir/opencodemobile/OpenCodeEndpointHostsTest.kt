package io.github.mohammedteir.opencodemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeEndpointHostsTest {
    @Test
    fun includesBuiltInProviderAndGitHubEndpoints() {
        val hosts = OpenCodeEndpointHosts.collect()
        assertTrue(hosts.contains("generativelanguage.googleapis.com"))
        assertTrue(hosts.contains("api.openai.com"))
        assertTrue(hosts.contains("api.anthropic.com"))
        assertTrue(hosts.contains("openrouter.ai"))
        assertTrue(hosts.contains("api.github.com"))
    }

    @Test
    fun discoversCustomProviderBaseUrls() {
        val config = """
            {
              "provider": {
                "custom-one": {
                  "options": { "baseURL": "https://llm.example.net/v1" }
                },
                "custom-two": {
                  "options": { "baseUrl": "https://gateway.example.org/api/" }
                }
              }
            }
        """.trimIndent()

        val hosts = OpenCodeEndpointHosts.collect(config)
        assertTrue(hosts.contains("llm.example.net"))
        assertTrue(hosts.contains("gateway.example.org"))
    }

    @Test
    fun normalizesAndDeduplicatesConfiguredHosts() {
        val config = """
            {
              "provider": {
                "one": { "options": { "baseURL": "https://API.Example.com/v1" } },
                "two": { "options": { "base_url": "https://api.example.com/other" } }
              }
            }
        """.trimIndent()

        assertEquals(1, OpenCodeEndpointHosts.collect(config).count { it == "api.example.com" })
    }

    @Test
    fun ignoresNonHttpAndMalformedBaseUrls() {
        val config = """
            {
              "provider": {
                "one": { "options": { "baseURL": "file:///tmp/provider" } },
                "two": { "options": { "baseURL": "not a URL" } }
              }
            }
        """.trimIndent()

        val hosts = OpenCodeEndpointHosts.collect(config)
        assertFalse(hosts.contains("tmp"))
        assertFalse(hosts.contains("not a URL"))
    }
}
