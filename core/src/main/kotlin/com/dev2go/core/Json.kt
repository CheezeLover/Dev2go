package com.dev2go.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lenient on purpose: the API adds fields, and an unknown key must not fail a parse. */
val LenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Unwraps the `{ "data": ... }` envelope every opencode response uses. */
fun String.unwrapData(): JsonElement = LenientJson.parseToJsonElement(this).unwrapData()

/** Unwraps the envelope from an already-parsed tree. */
fun JsonElement.unwrapData(): JsonElement =
    (this as? JsonObject)?.get("data") ?: this

fun JsonObject.stringOrNull(key: String): String? = this[key]?.asStringOrNull()

fun JsonElement.asStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.takeIf { it != "null" }
}

fun JsonElement.asLongOrNull(): Long? = asStringOrNull()?.toLongOrNull()

fun JsonElement.asIntOrNull(): Int? = asStringOrNull()?.toIntOrNull()

fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

/** Converts the kotlinx tree into the small sealed [JsonValue] the UI works with. */
fun JsonElement.toJsonValue(): JsonValue = when (this) {
    is JsonNull -> NullValue
    is JsonPrimitive -> if (isString) JsonPrimitiveValue(content) else JsonPrimitiveValue(content)
    is JsonObject -> MapValue(entries.associate { (k, v) -> k to v.toJsonValue() })
    is JsonArray -> ListValue(map { it.toJsonValue() })
}

fun JsonObject.toSession(): Session = Session(
    id = string("id") ?: "",
    projectID = string("projectID"),
    title = string("title"),
    agent = string("agent"),
    model = toModelRef(),
    time = (obj("time"))?.let {
        SessionTime(it.long("created"), it.long("updated"))
    },
    location = obj("location")?.let { Location(it.string("directory")) },
)

fun JsonObject.toMessage(): Message = Message(
    id = string("id") ?: "",
    type = string("type") ?: "unknown",
    text = string("text"),
    content = array("content")?.map { part ->
        val o = part.asObjectOrNull() ?: return@map ContentPart("unknown")
        ContentPart(
            type = o.string("type") ?: "unknown",
            text = o.string("text"),
            tool = o.string("tool"),
            state = o["state"]?.toJsonValue(),
        )
    },
    agent = string("agent"),
    model = toModelRef(),
    time = obj("time")?.let { MessageTime(it.long("created"), it.long("completed")) },
    finish = string("finish"),
    outcome = string("outcome"),
    error = obj("error")?.let { ApiError(it.string("type"), it.string("message"), it.int("status")) },
)

fun JsonObject.toEvent(): ServerEvent {
    val raw = obj("data")
    val data = raw?.entries?.associate { (k, v) -> k to v.toJsonValue() } ?: emptyMap()
    return ServerEvent(
        id = string("id"),
        type = string("type") ?: "unknown",
        data = data,
    )
}

fun JsonObject.toModelRef(): ModelRef? {
    val id = string("providerID") ?: return null
    val modelId = string("modelID") ?: string("id") ?: return null
    return ModelRef(id, modelId)
}

private fun JsonObject.string(key: String): String? = this[key]?.asStringOrNull()
private fun JsonObject.long(key: String): Long? = this[key]?.asLongOrNull()
private fun JsonObject.int(key: String): Int? = this[key]?.asIntOrNull()
private fun JsonObject.obj(key: String): JsonObject? = this[key]?.asObjectOrNull()
private fun JsonObject.array(key: String): JsonArray? = this[key]?.asArrayOrNull()