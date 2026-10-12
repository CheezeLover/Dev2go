package com.dev2go.core

/**
 * A model addressed the way Ollama addresses them: `name:tag`, where the tag is optional
 * and defaults to `latest`.
 *
 * This is the identity the catalog shows the user and the identity registered as a
 * provider, so it deliberately keeps the Ollama spelling rather than the GGUF filename.
 * A downloaded `Qwen3-0.6B-Q8_0.gguf` should present as `qwen3:0.6b`, because that is what
 * the user recognises from the library.
 */
data class OllamaModelRef(val name: String, val tag: String) {

    init {
        require(name.isNotBlank()) { "model name must not be blank" }
        require(':' !in name) { "model name must not contain ':' — that separates the tag" }
        require(tag.isNotBlank()) { "model tag must not be blank" }
        require(':' !in tag) { "model tag must not contain ':' — the name already owns the separator" }
    }

    override fun toString(): String = "$name:$tag"

    /** The identifier llama.cpp reports, which is the GGUF's path on disk. */
    fun localPath(fileName: String): String = fileName

    companion object {
        const val DEFAULT_TAG = "latest"

        /**
         * Parses `name`, `name:tag` or `registry/name:tag`.
         *
         * A bare `name` gets the `latest` tag, matching Ollama. Registry-qualified names
         * keep their registry so a `namespace/name:tag` from the library does not collapse
         * onto a same-named model from elsewhere.
         *
         * Returns null rather than throwing for input that is not a usable reference: this
         * parses values that arrive from a downloaded manifest, and one malformed entry
         * should not take down a catalog listing.
         */
        fun parse(value: String): OllamaModelRef? {
            val trimmed = value.trim().removePrefix("ollama/")
            if (trimmed.isEmpty()) return null

            // Split on the FIRST ':'. Ollama names may contain '/' for a registry prefix
            // but never ':', so the first colon is unambiguously the tag separator.
            // (Splitting on the last would read "qwen3:a:b" as name "qwen3:a", which the
            // constructor rejects — parse would then return null for an input that only
            // looks malformed because of where the split happened.)
            val separator = trimmed.indexOf(':')
            val name = if (separator < 0) trimmed else trimmed.substring(0, separator)
            val tag = if (separator < 0) DEFAULT_TAG else trimmed.substring(separator + 1)

            if (name.isBlank() || tag.isBlank()) return null
            return runCatching { OllamaModelRef(name, tag) }.getOrNull()
        }
    }
}