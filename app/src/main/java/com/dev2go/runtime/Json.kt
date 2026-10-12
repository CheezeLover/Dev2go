package com.dev2go.runtime

/**
 * A small JSON reader/writer for the facade.
 *
 * The facade builds responses as text rather than through a serialisation library because
 * it has to interleave two vocabularies — Ollama's field names and OpenAI-compatible ones —
 * and a tree model makes that harder to read than assembling the object does. Every value
 * that comes from a model is escaped by [quote], so generated text containing a quote or a
 * newline cannot break out into the JSON structure.
 */
internal object Json {

    fun parse(raw: String): Any? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw).toPlain()
    }.getOrNull()

    /** Unwraps the `{ "data": ... }` envelope opencode's own API uses. */
    fun unwrap(raw: String): Any? = (parse(raw) as? Map<*, *>)?.get("data") ?: parse(raw)

    private fun kotlinx.serialization.json.JsonElement.toPlain(): Any? = when (this) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> content
        is kotlinx.serialization.json.JsonObject -> entries.associate { (k, v) -> k to v.toPlain() }
        is kotlinx.serialization.json.JsonArray -> map { it.toPlain() }
    }

    fun obj(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    fun array(value: Any?): List<Any?>? = value as? List<Any?>

    fun string(value: Any?): String? = value as? String

    fun bool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

    /** Formats a number without locale interference, or null when it is not one. */
    fun number(value: Any?): String? = when (value) {
        is Number -> value.toString()
        is String -> value.toDoubleOrNull()?.toString()
        else -> null
    }

    /** JSON-escapes [value] and wraps it in quotes. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        value.forEach { c ->
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    fun buildArray(values: List<String>): String = values.joinToString(",", "[", "]")
}