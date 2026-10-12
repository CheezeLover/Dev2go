package com.dev2go.agent

import com.dev2go.core.ApiError
import com.dev2go.core.ContentPart
import com.dev2go.core.LenientJson
import com.dev2go.core.Message
import com.dev2go.core.ModelRef
import com.dev2go.core.ServerAuth
import com.dev2go.core.ServerEvent
import com.dev2go.core.Session
import com.dev2go.core.asArrayOrNull
import com.dev2go.core.asObjectOrNull
import com.dev2go.core.stringOrNull
import com.dev2go.core.toEvent
import com.dev2go.core.toMessage
import com.dev2go.core.toSession
import com.dev2go.core.unwrapData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for opencode's v2 HTTP API.
 *
 * Built against a running server rather than the OpenAPI document, because the document
 * does not resolve the event union (`Event.SeqEncoded` is a bare string) and says nothing
 * about the `{ "data": ... }` envelope every response carries.
 */
class OpencodeClient(
    private val baseUrl: String,
    password: String,
) {
    private val auth = ServerAuth.header(password)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // No read timeout: the event stream is a long-lived GET that is meant to stay open.
        // A read timeout here would close an idle stream every minute.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun url(path: String) = baseUrl + path

    private fun Request.Builder.auth() = header("Authorization", auth)

    private fun Request.Builder.json() = header("Content-Type", "application/json")

    private suspend fun get(path: String): String = request(Request.Builder().url(url(path)).get().auth().build())

    private suspend fun post(path: String, body: JsonObject): String =
        request(Request.Builder().url(url(path)).post(body.toString().toRequestBody()).json().auth().build())

    private suspend fun delete(path: String): String =
        request(Request.Builder().url(url(path)).delete().auth().build())

    private suspend fun request(request: Request): String =
        http.newCall(request).await().use { response ->
            response.body?.string().orEmpty().ifEmpty { "{}" }
        }

    // ---- sessions ----

    suspend fun listSessions(): List<Session> {
        val root = get("/api/session").unwrapData()
        return root.asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull()?.toSession()?.takeIf { s -> s.id.isNotEmpty() } }
            ?: emptyList()
    }

    suspend fun createSession(title: String? = null): Session {
        val body = buildJsonObject { title?.let { put("title", it) } }
        val root = post("/api/session", body).unwrapData()
        return root.asObjectOrNull()?.toSession() ?: Session(id = "")
    }

    suspend fun deleteSession(id: String) {
        runCatching { delete("/api/session/$id") }
    }

    // ---- messages ----

    suspend fun messages(sessionId: String, limit: Int = 100): List<Message> {
        val root = get("/api/session/$sessionId/message?limit=$limit").unwrapData()
        return root.asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull()?.toMessage() }
            ?: emptyList()
    }

    /**
     * Sends a prompt.
     *
     * `model` is passed here rather than only on the session because that is where the API
     * accepts it per turn, which is what makes switching models between turns work.
     */
    suspend fun prompt(sessionId: String, text: String, model: ModelRef? = null) {
        val body = buildJsonObject {
            put("text", text)
            model?.let {
                put(
                    "model",
                    buildJsonObject {
                        put("providerID", it.providerID)
                        put("modelID", it.modelID)
                    },
                )
            }
        }
        post("/api/session/$sessionId/prompt", body)
    }

    suspend fun interrupt(sessionId: String) {
        runCatching { post("/api/session/$sessionId/interrupt", buildJsonObject { }) }
    }

    suspend fun listModels(): List<ModelRef> {
        // /api/model returns `{ location, data: [...] }` — it is the one endpoint not
        // wrapped in a bare `data` envelope, so unwrapping first would throw the array away.
        val root = get("/api/model").let { LenientJson.parseToJsonElement(it) }.asObjectOrNull()
        val array = root?.get("data")?.asArrayOrNull()
            ?: LenientJson.parseToJsonElement(get("/api/model")).asArrayOrNull()
            ?: return emptyList()
        return array.mapNotNull { entry ->
            val o = entry.asObjectOrNull() ?: return@mapNotNull null
            val providerID = o.stringOrNull("providerID") ?: return@mapNotNull null
            val modelID = o.stringOrNull("modelID") ?: o.stringOrNull("id") ?: return@mapNotNull null
            ModelRef(providerID, modelID)
        }
    }

    // ---- events ----

    /**
     * The server-sent event stream.
     *
     * Two things the wire format forces: the stream sends `: heartbeat` comment lines that
     * must be dropped rather than parsed, and it sends several event types this app does not
     * model — filtering here keeps unknown types out of the UI instead of crashing it.
     */
    fun events(): Flow<ServerEvent> = callbackFlow {
        val request = Request.Builder().url(url("/api/event")).get().auth().build()
        val call = http.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val source = it.body?.source() ?: return@use
                    var buffer: String? = null
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith(":")) continue          // heartbeat
                        if (!line.startsWith("data:")) continue      // event: / id: / blank
                        val json = line.removePrefix("data:").trim()
                        if (json.isEmpty()) continue
                        buffer = json
                        val parsed = runCatching {
                            LenientJson.parseToJsonElement(json).asObjectOrNull()?.toEvent()
                        }.getOrNull() ?: continue
                        trySend(parsed)
                    }
                    // A finished stream means the server went away. Closing lets the
                    // collector decide whether to reconnect.
                    close()
                }
            }
        })

        awaitClose { call.cancel() }
    }
}

/** Bridges OkHttp's callback API to coroutines. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resumeWith(Result.success(response))
        }
    })
    cont.invokeOnCancellation { cancel() }
}