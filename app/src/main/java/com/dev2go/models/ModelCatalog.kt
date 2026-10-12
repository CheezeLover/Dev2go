package com.dev2go.models

import com.dev2go.core.OllamaModelRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * A downloadable model: the Ollama library entry plus the GGUF blob behind one quant.
 */
data class CatalogEntry(
    val reference: OllamaModelRef,
    val displayName: String,
    val description: String,
    val parameterSize: String,
    val quantization: String,
    val sizeBytes: Long,
    val capabilities: List<String>,
) {
    val supportsTools: Boolean get() = capabilities.any { it.equals("tools", ignoreCase = true) }

    val sizeLabel: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> "%d MB".format(sizeBytes / 1_000_000)
            else -> "$sizeBytes B"
        }

    /**
     * Why this model is or is not a good fit on a phone, said plainly.
     *
     * Tool calling is the dividing line rather than size: an agent that cannot call a tool
     * cannot do the thing the agent is for, so a small tool-capable model beats a larger
     * one that cannot act.
     */
    val phoneNote: String
        get() = when {
            !supportsTools -> "no tool calling — usable for chat, not for the agent"
            sizeBytes > 9_000_000_000 -> "large for a phone; needs 12 GB RAM to stay resident"
            sizeBytes > 5_000_000_000 -> "big; expect slow first token on mid-range phones"
            else -> "fits comfortably in 8 GB"
        }
}

/**
 * The model catalog and its download source.
 *
 * Backed by the Ollama library at registry.ollama.ai, which is a plain OCI registry. A
 * manifest lists the model's layers and one of them is the GGUF, so a model can be fetched
 * over ordinary HTTP with no Ollama installed — which is the point, since there is no
 * official Ollama build for Android.
 */
class ModelCatalog(private val cacheDir: File) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val registry = "https://registry.ollama.ai"
    private val library = "https://registry.ollama.ai/v2/library"

    /** The library's own list, so the picker shows what people actually install. */
    suspend fun libraryModels(): List<String> {
        val body = Http.text("$library/_catalog") ?: return FALLBACK_LIBRARY
        return runCatching {
            val arr = json.parseToJsonElement(body).jsonArray
            arr.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }.ifEmpty { FALLBACK_LIBRARY }
        }.getOrElse { FALLBACK_LIBRARY }
    }

    /**
     * Every tag of [model] that resolves to a GGUF blob.
     *
     * Each tag's manifest is fetched and the layer that is a GGUF is picked. Ollama models
     * are already quantised, so the "quantization" in the entry name is the quant — there
     * is nothing to choose separately, which is why entries are per-tag rather than
     * model-plus-quant.
     */
    suspend fun entries(model: String): List<CatalogEntry> {
        val tags = Http.text("$library/$model/tags/list")
            ?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() }
            .orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNullSafe() }

        return tags.mapNotNull { tag ->
            val reference = OllamaModelRef.parse("$model:$tag") ?: return@mapNotNull null
            val manifest = manifestFor(model, tag) ?: return@mapNotNull null
            val layer = ggufLayer(manifest) ?: return@mapNotNull null
            val details = manifest.config()?.details() ?: emptyMap()
            CatalogEntry(
                reference = reference,
                displayName = "$model:${tag}",
                description = manifest.config()?.let { it.stringOrNull("description") } ?: "",
                parameterSize = details["parameter_size"].toString(),
                quantization = details["quantization_level"].toString().uppercase(),
                sizeBytes = layer.size,
                capabilities = details["capabilities"].toString().toCapabilities(),
            )
        }.sortedBy { it.sizeBytes }
    }

    /**
     * The GGUF blob URL for [reference], or null when the library has no such tag.
     */
    suspend fun downloadUrl(reference: OllamaModelRef): String? {
        val manifest = manifestFor(reference.name, reference.tag) ?: return null
        val layer = ggufLayer(manifest) ?: return null
        return "$registry/v2/library/${reference.name}/blobs/${layer.digest.removePrefix("sha256:")}"
    }

    private data class Layer(val digest: String, val size: Long, val mediaType: String)

    private suspend fun manifestFor(model: String, tag: String): Manifest? {
        val body = Http.text("$library/$model/manifests/$tag") ?: return null
        return runCatching { Manifest(json.parseToJsonElement(body).jsonObject, model, registry, json) }.getOrNull()
    }

    private fun ggufLayer(manifest: Manifest): Layer? =
        manifest.layers.firstOrNull { it.mediaType.contains("gguf", ignoreCase = true) }
            ?: manifest.layers.firstOrNull { it.mediaType.isNotBlank() }

    /**
     * A manifest plus the two things it needs to resolve the config blob it points at.
     *
     * They are passed in rather than read from the enclosing class: a nested (non-inner)
     * class cannot reach outer members unqualified, and threading them through keeps that
     * visible instead of surprising.
     */
    private class Manifest(
        val obj: JsonObject,
        private val model: String,
        private val registry: String,
        private val json: Json,
    ) {
        val layers: List<Layer>
            get() = (obj["layers"] as? JsonArray).orEmpty().mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                Layer(
                    digest = o["digest"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                    size = o["size"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L,
                    mediaType = o["mediaType"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                )
            }

        /**
         * Ollama keeps the interesting metadata — parameter count, quantisation, capability
         * flags — in a separate config blob that the manifest points at, so a second fetch is
         * needed before an entry can say anything useful. Suspend because it does I/O.
         */
        suspend fun config(): JsonObject? {
            val descriptor = obj["config"] as? JsonObject ?: return null
            val digest = descriptor["digest"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
            // registry, json and Http are members of the outer class. A nested class does
            // not capture them implicitly, and reaching for them unqualified does not
            // compile.
            val body = Http.text("$registry/v2/library/$model/blobs/${digest.removePrefix("sha256:")}")
                ?: return null
            return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    private fun JsonObject.details(): Map<String, String> {
        val details = this["details"] as? JsonObject ?: return emptyMap()
        return details.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNullSafe()?.let { k to it }
        }.toMap()
    }

    companion object {
        /**
         * Used when the library index cannot be fetched.
         *
         * A hardcoded list beats an empty picker: these are small, tool-capable models that
         * actually fit on a phone, and the registry round-trip may fail on a captive portal.
         */
        val FALLBACK_LIBRARY = listOf(
            "qwen3", "qwen2.5", "llama3.2", "smollm2", "gemma3", "phi4-mini", "granite3.1-dense",
        )
    }
}

private fun String.toCapabilities(): List<String> =
    removeSurrounding("[", "]").split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content