package com.dev2go.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the opencode provider block that points the agent at the on-device model server.
 *
 * This is the seam that makes a downloaded model become an available source. opencode
 * registers Ollama as an OpenAI-compatible provider — its `baseURL` ends in `/v1`, not
 * `/api` — so a model on the phone is registered by writing one entry into `providers`.
 *
 * The shape is not guessed: it is taken from a live opencode config, where a provider is
 * `{ name, settings: { baseURL }, models: { id: { name } } }`.
 */
object ProviderConfig {

    /** Provider id used in opencode's config for the on-device runtime. */
    const val PROVIDER_ID = "ondevice"

    const val DEFAULT_PORT = 8080

    fun baseUrl(port: Int = DEFAULT_PORT): String = "http://127.0.0.1:$port/v1"

    /**
     * Produces the `providers.<id>` value for [models].
     *
     * Every model is listed rather than leaving the list to be discovered, so a model shows
     * up in the picker with a readable name the moment it finishes downloading instead of
     * only after something has probed the server.
     */
    fun providerJson(
        models: List<OllamaModelRef>,
        port: Int = DEFAULT_PORT,
        displayName: String = "On-device (llama.cpp)",
    ): JsonObject = buildJsonObject {
        put("name", displayName)
        put(
            "settings",
            buildJsonObject {
                // opencode consumes the OpenAI-compatible surface, which is what
                // llama.cpp's server already provides.
                put("baseURL", baseUrl(port))
            },
        )
        put(
            "models",
            buildJsonObject {
                models.forEach { model ->
                    put(
                        model.toString(),
                        buildJsonObject { put("name", model.toString()) },
                    )
                }
            },
        )
    }

    /**
     * Returns a copy of [config] with the on-device provider replaced by [models].
     *
     * Merges rather than overwrites so other providers the user has configured — remote
     * models, the LAN Ollama box — survive having a local model added. Registration must
     * not cost you the rest of your configuration.
     */
    fun withProvider(
        config: JsonObject,
        models: List<OllamaModelRef>,
        port: Int = DEFAULT_PORT,
    ): JsonObject {
        val providers = (config["providers"] as? JsonObject) ?: JsonObject(emptyMap())
        return JsonObject(config.toMutableMap().apply {
            put("providers", JsonObject(providers.toMutableMap().apply {
                put(PROVIDER_ID, providerJson(models, port))
            }))
        })
    }

    /** Parses a stored opencode config, returning an empty object rather than throwing. */
    fun parseConfig(raw: String): JsonObject = runCatching {
        Json.parseToJsonElement(raw) as JsonObject
    }.getOrElse { JsonObject(emptyMap()) }

    /** Extracts the model ids currently registered under the on-device provider. */
    fun registeredModels(config: JsonObject): List<String> {
        val providers = config["providers"] as? JsonObject ?: return emptyList()
        val provider = providers[PROVIDER_ID] as? JsonObject ?: return emptyList()
        val models = provider["models"] as? JsonObject ?: return emptyList()
        // JsonObject keys are plain Strings, not JsonPrimitive — casting them to
        // JsonPrimitive silently yields nothing, which looks like "no models registered"
        // rather than like a bug.
        return models.keys.filter { it.isNotBlank() }
    }

    /** Ids only, for assertions and diffing. */
    fun modelIds(models: List<OllamaModelRef>): List<String> = models.map { it.toString() }
}