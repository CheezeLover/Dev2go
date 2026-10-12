package com.dev2go.core

/**
 * Wire types for opencode's v2 HTTP API.
 *
 * Every shape here was taken from a running `opencode serve`, not from the OpenAPI document:
 * the spec at packages/protocol/openapi.json resolves the event union to a bare
 * `Event.SeqEncoded: string`, so it cannot tell you what an event actually contains. Where
 * a field was observed but its full type is unknown, this is a String rather than a guess.
 */

/** The API wraps every response in `{ "data": ... }`. */
data class Envelope<T>(val data: T)

/** Milliseconds since the epoch, as opencode emits them. */
typealias EpochMillis = Long

data class Session(
    val id: String,
    val projectID: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val time: SessionTime? = null,
    val location: Location? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"
}

data class SessionTime(
    val created: EpochMillis? = null,
    val updated: EpochMillis? = null,
)

data class Location(val directory: String? = null)

/**
 * A model addressed the way opencode addresses them: provider plus model id.
 *
 * This is not an [OllamaModelRef]. An on-device model is `providerID = "ondevice"` and
 * `modelID` is the Ollama-style `name:tag`; the provider id is what opencode resolves
 * against its `providers` config.
 */
data class ModelRef(val providerID: String, val modelID: String) {
    override fun toString(): String = "$providerID/$modelID"
}

/**
 * A message in a session.
 *
 * `type` is one of `user`, `assistant` or `idle`. A user message carries `text`; an
 * assistant message carries `content` parts. An `idle` message with a non-null `error` is
 * how a failed turn surfaces — its `type` is not "error", which is worth knowing before
 * writing UI that filters on it.
 */
data class Message(
    val id: String,
    val type: String,
    val text: String? = null,
    val content: List<ContentPart>? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val time: MessageTime? = null,
    val finish: String? = null,
    val outcome: String? = null,
    val error: ApiError? = null,
) {
    val isUser: Boolean get() = type == "user"
    val isAssistant: Boolean get() = type == "assistant"

    /** A turn that ended in an error rather than a finish reason. */
    val failed: Boolean get() = error != null || finish == "error" || outcome == "failed"
}

data class MessageTime(
    val created: EpochMillis? = null,
    val completed: EpochMillis? = null,
)

/**
 * One piece of an assistant message.
 *
 * The `type` values seen on the wire are `text`, `reasoning` and `tool`. Left open as a
 * String because the API adds parts over time and an unknown part should render as
 * something rather than crash the message list.
 */
data class ContentPart(
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    val state: JsonValue? = null,
)

data class ApiError(
    val type: String? = null,
    val message: String? = null,
    val status: Int? = null,
) {
    val display: String get() = message ?: type ?: "Unknown error"
}

/**
 * A server-sent event from `GET /api/event`.
 *
 * The stream sends `data: {json}` lines and `: heartbeat` comments; heartbeats must be
 * dropped rather than parsed, or every idle moment throws. Event types observed:
 * `server.connected`, `session.updated`, `session.inbox.enqueued`, `session.inbox.delivered`,
 * `session.execution.started`, `session.execution.failed`, `session.step.started`,
 * `session.step.failed`, `session.instructions.updated`.
 */
data class ServerEvent(
    val id: String? = null,
    val type: String,
    val data: Map<String, JsonValue> = emptyMap(),
) {
    fun string(key: String): String? = (data[key] as? JsonPrimitiveValue)?.value

    fun obj(key: String): Map<String, JsonValue>? = (data[key] as? MapValue)?.map

    fun objString(key: String, field: String): String? = obj(key)?.get(field).asString()
}

sealed interface JsonValue
data class JsonPrimitiveValue(val value: String) : JsonValue
data class MapValue(val map: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = map[key]
}
data class ListValue(val list: List<JsonValue>) : JsonValue
object NullValue : JsonValue

fun JsonValue?.asString(): String? = when (this) {
    is JsonPrimitiveValue -> value
    else -> null
}