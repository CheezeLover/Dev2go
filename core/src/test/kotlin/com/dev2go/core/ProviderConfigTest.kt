package com.dev2go.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {

    private val model = OllamaModelRef("qwen3", "0.6b")

    @Test
    fun `points at the OpenAI-compatible surface, not the Ollama native one`() {
        // opencode's ollama provider is configured with a baseURL ending in /v1. Writing
        // /api here would be a 404 on every request, which looks like a dead model.
        assertEquals("http://127.0.0.1:8080/v1", ProviderConfig.baseUrl())
    }

    @Test
    fun `registers every model by its Ollama id`() {
        val provider = ProviderConfig.providerJson(listOf(model))
        val models = provider["models"] as kotlinx.serialization.json.JsonObject
        assertTrue("qwen3:0.6b" in models)
    }

    @Test
    fun `adding a model keeps the other providers the user configured`() {
        val existing = buildJsonObject {
            putJsonObject("ollama") {
                put("name", "Ollama on the LAN")
                putJsonObject("settings") { put("baseURL", "http://192.168.3.129:11434/v1") }
            }
        }
        val config = buildJsonObject {
            put("model", "qwen3:0.6b")
            put("providers", existing)
        }

        val updated = ProviderConfig.withProvider(config, listOf(model))
        val providers = updated["providers"] as kotlinx.serialization.json.JsonObject

        assertEquals(
            "Ollama on the LAN",
            (providers["ollama"]?.let { (it as kotlinx.serialization.json.JsonObject)["name"] } as JsonPrimitive).content,
        )
        assertTrue(ProviderConfig.PROVIDER_ID in providers)
        // The user's chosen model is not lost either.
        assertEquals("qwen3:0.6b", (updated["model"] as JsonPrimitive).content)
    }

    @Test
    fun `re-registering replaces the previous model list rather than accumulating`() {
        val once = ProviderConfig.withProvider(buildJsonObject {}, listOf(model))
        val twice = ProviderConfig.withProvider(once, listOf(OllamaModelRef("llama3", "latest")))
        assertEquals(listOf("llama3:latest"), ProviderConfig.registeredModels(twice))
    }

    @Test
    fun `a malformed stored config parses to empty instead of throwing`() {
        val config = ProviderConfig.parseConfig("{ this is not json")
        assertEquals(emptyList<String>(), ProviderConfig.registeredModels(config))
        // and is still usable as a base to register into
        val updated = ProviderConfig.withProvider(config, listOf(model))
        assertEquals(listOf("qwen3:0.6b"), ProviderConfig.registeredModels(updated))
    }
}