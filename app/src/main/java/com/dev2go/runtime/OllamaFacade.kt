package com.dev2go.runtime

import com.dev2go.core.OllamaModelRef
import com.dev2go.models.Http
import java.time.Instant

/**
 * An Ollama-compatible facade over the embedded llama.cpp server.
 *
 * llama.cpp v0.6.0 ships **no** Ollama routes — `grep -rn -i ollama tools/server/` returns
 * nothing — so `/api/tags`, `/api/show` and `/api/version` are produced here, and
 * `/api/chat` and `/api/generate` are translated onto llama.cpp's own OpenAI-compatible
 * handlers rather than reimplementing generation.
 *
 * Doing the translation in Kotlin rather than in C++ is deliberate: the inference path —
 * chat templating, grammar-guided tool-call parsing — stays upstream's, where it already
 * works. This layer only renames requests and responses.
 */
class OllamaFacade(
    private val upstream: String = "http://127.0.0.1:${LlmServer.PORT}",
    private val port: Int = PORT,
    private val version: String = "0.0.0-dev2go",
) {

    companion object {
        /** Ollama's own default port, so a client configured for Ollama finds it unchanged. */
        const val PORT = 11434
    }

    private val server = LoopbackHttpServer(port, ::route)

    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** The provider URL opencode registers to reach this. */
    fun providerBaseUrl(): String = baseUrl + "/v1"

    val isRunning: Boolean get() = server.isRunning

    @Volatile var loadedModel: OllamaModelRef? = null

    fun start() = server.start()

    fun stop() = server.stop()

    private fun route(request: LoopbackHttpServer.Request): LoopbackHttpServer.Response =
        when {
            request.path == "/api/version" ->
                ok("""{"version":${Json.quote(version)}}""")

            request.path == "/api/tags" ->
                ok(tagsJson())

            request.path == "/api/show" ->
                ok(showJson(request.query["model"]))

            request.path == "/api/chat" && request.method == "POST" ->
                handleChat(request.body)

            request.path == "/api/generate" && request.method == "POST" ->
                handleGenerate(request.body)

            else ->
                LoopbackHttpServer.Response(404, """{"error":"not found"}""")
        }

    private fun ok(body: String) = LoopbackHttpServer.Response(200, body)

    // ---- Ollama-native metadata ----

    /**
     * `GET /api/tags` in Ollama's shape.
     *
     * Field names and nesting match a real Ollama rather than being close enough: clients
     * read `details.capabilities` to decide whether a model can call tools, and an empty
     * list there silently disables the agent rather than reporting an error.
     */
    private fun tagsJson(): String {
        val ref = loadedModel ?: return """{"models":[]}"""
        val entry = """
          {"name":${Json.quote(ref.toString())},"model":${Json.quote(ref.toString())},
           "modified_at":"1970-01-01T00:00:00Z",
           "size":0,"digest":"dev2go-local",
           "details":{"parent_model":"","format":"gguf","family":"","families":[],
                      "parameter_size":"","quantization_level":"","context_length":0},
           "capabilities":["completion","tools"]}
        """.trimIndent()
        return """{"models":[$entry]}"""
    }

    private fun showJson(model: String?): String {
        val ref = loadedModel ?: return """{"error":"no model loaded"}"""
        return """
        {"license":"","modelfile":"# dev2go on-device model",
         "parameters":"","template":"",
         "details":{"parent_model":"","format":"gguf","family":"","families":[],
                    "parameter_size":"","quantization_level":"","context_length":0},
         "model_info":{"general.architecture":"gguf"},
         "capabilities":["completion","tools"],
         "model":${Json.quote(ref.toString())},
         "details_queried":true,
         "requested_model":${Json.quote(model ?: ref.toString())}}
        """.trimIndent()
    }

    // ---- generation, translated onto llama.cpp ----

    /**
     * `POST /api/chat` translated onto llama.cpp's `/v1/chat/completions`.
     *
     * Ollama uses `messages[].content` with a `stream` flag and wraps the reply in
     * `message`; OpenAI-compatible uses `choices[].message`. Streaming is handled as whole
     * messages rather than chunks: a phone generating a few tokens a second gains nothing
     * from chunk boundaries, and a half-streamed tool call is worse than a slightly
     * delayed whole one.
     */
    private fun handleChat(body: String): LoopbackHttpServer.Response {
        val request = Json.obj(Json.parse(body))
            ?: return badRequest("bad request")
        val messages = Json.array(request["messages"]).orEmpty()
        val stream = Json.bool(request["stream"]) ?: false

        val oaiMessages = messages.mapNotNull { element ->
            val m = Json.obj(element) ?: return@mapNotNull null
            val role = Json.string(m["role"]) ?: "user"
            val content = when (val c = m["content"]) {
                is String -> c
                // Ollama allows structured content parts; flatten to text rather than
                // dropping the message, which would look like a lost turn.
                is List<*> -> c.filterIsInstance<Map<String, Any?>>()
                    .mapNotNull { Json.string(it["text"]) }
                    .joinToString("\n")
                else -> ""
            }
            """{"role":${Json.quote(role)},"content":${Json.quote(content)}}"""
        }

        val payload = StringBuilder()
        payload.append("""{"messages":${Json.buildArray(oaiMessages)}""")
        payload.append(""","stream":$stream""")
        Json.string(request["model"])?.let { payload.append(""","model":${Json.quote(it)}""") }
        Json.obj(request["options"])?.let { options ->
            Json.number(options["temperature"])?.let { payload.append(""","temperature":$it""") }
            Json.number(options["top_p"])?.let { payload.append(""","top_p":$it""") }
            Json.number(options["num_predict"])?.let { payload.append(""","max_tokens":$it""") }
            Json.number(options["seed"])?.let { payload.append(""","seed":$it""") }
        }
        payload.append("}")

        val response = Http.postJson("$upstream/v1/chat/completions", payload.toString())
            ?: return unavailable()
        return ok(toOllamaChat(response))
    }

    /** `POST /api/generate` translated onto `/v1/completions`. */
    private fun handleGenerate(body: String): LoopbackHttpServer.Response {
        val request = Json.obj(Json.parse(body))
            ?: return badRequest("bad request")
        val prompt = Json.string(request["prompt"]) ?: ""
        val stream = Json.bool(request["stream"]) ?: false

        val payload = StringBuilder()
        payload.append("""{"prompt":${Json.quote(prompt)}""")
        payload.append(""","stream":$stream""")
        Json.obj(request["options"])?.let { options ->
            Json.number(options["temperature"])?.let { payload.append(""","temperature":$it""") }
            Json.number(options["num_predict"])?.let { payload.append(""","max_tokens":$it""") }
        }
        payload.append("}")

        val response = Http.postJson("$upstream/v1/completions", payload.toString())
            ?: return unavailable()
        return ok(toOllamaGenerate(response))
    }

    private fun badRequest(message: String) =
        LoopbackHttpServer.Response(400, """{"error":${Json.quote(message)}}""")

    private fun unavailable() =
        LoopbackHttpServer.Response(502, """{"error":"llama.cpp is not reachable"}""")

    private fun toOllamaChat(oaiResponse: String): String {
        val root = Json.obj(Json.parse(oaiResponse))
            ?: return """{"error":"unreadable upstream response"}"""
        val first = Json.obj(Json.array(root["choices"])?.firstOrNull())
        val message = Json.obj(first?.get("message"))
        val text = Json.string(message?.get("content")) ?: ""
        // Ollama carries chain-of-thought in a top-level `thinking` field; OpenAI puts it
        // inside the message. Mapping it means a reasoning model renders as reasoning rather
        // than as an empty bubble.
        val reasoning = Json.string(message?.get("reasoning_content"))
        val finish = Json.string(first?.get("finish_reason")) ?: "stop"
        val usage = Json.obj(root["usage"])
        val name = Json.string(root["model"]) ?: loadedModel?.toString().orEmpty()

        return buildString {
            append("""{"model":${Json.quote(name)},""")
            append(""""created_at":${Json.quote(Instant.now().toString())},""")
            append(""""message":{"role":"assistant","content":${Json.quote(text)}}""")
            reasoning?.let { append(""","thinking":${Json.quote(it)}""") }
            append(""","done":true,"done_reason":${Json.quote(if (finish == "length") "length" else "stop")}""")
            Json.number(usage?.get("prompt_tokens"))?.let { append(""","prompt_eval_count":$it""") }
            Json.number(usage?.get("completion_tokens"))?.let { append(""","eval_count":$it""") }
            append("}")
        }
    }

    private fun toOllamaGenerate(oaiResponse: String): String {
        val root = Json.obj(Json.parse(oaiResponse))
            ?: return """{"error":"unreadable upstream response"}"""
        val first = Json.obj(Json.array(root["choices"])?.firstOrNull())
        val text = Json.string(first?.get("text")) ?: ""
        val usage = Json.obj(root["usage"])
        val name = Json.string(root["model"]) ?: loadedModel?.toString().orEmpty()

        return buildString {
            append("""{"model":${Json.quote(name)},""")
            append(""""created_at":${Json.quote(Instant.now().toString())},""")
            append(""""response":${Json.quote(text)},""")
            append(""""done":true""")
            Json.number(usage?.get("prompt_tokens"))?.let { append(""","prompt_eval_count":$it""") }
            Json.number(usage?.get("completion_tokens"))?.let { append(""","eval_count":$it""") }
            append("}")
        }
    }
}