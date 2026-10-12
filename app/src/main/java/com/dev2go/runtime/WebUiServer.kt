package com.dev2go.runtime

import android.content.Context
import android.util.Log
import com.dev2go.agent.AgentProcess
import java.io.ByteArrayOutputStream

/**
 * Serves OpenChamber's mobile app to the WebView.
 *
 * Three jobs, and none of them is "run OpenChamber's server":
 *
 * 1. **Static assets.** The app is 300-odd files of built JS in the APK's assets. Android
 *    cannot serve those to a WebView on its own, so they are streamed from here.
 * 2. **Pairing.** The mobile app asks for `/api/client-auth/connection/candidates` and
 *    `/api/client-auth/pairing/redeem` before it will talk to anything. Both are answered
 *    here, handing back the on-device opencode URL and its password.
 * 3. **Phone files.** The UI is a web app with a browser filesystem model, so the only way
 *    it can read a file is over HTTP. See [PhoneStorage].
 *
 * It deliberately does **not** proxy opencode. opencode serves its own API on its own port,
 * with CORS open, and the app's calls go straight there — which is why the long-lived SSE
 * event stream is untouched. Proxying it would mean reimplementing streaming here for no
 * gain. The one thing a proxy would have solved is the auth header, and that is patched
 * into the bundle at build time instead.
 */
class WebUiServer(
    context: Context,
    private val storage: PhoneStorage,
    private val agent: AgentProcess,
    port: Int = PORT,
) {

    companion object {
        /** Distinct from the model runtime (8080) and the Ollama facade (11434). */
        const val PORT = 18080

        private const val ASSET_ROOT = "openchamber"
        private const val TAG = "Dev2go/WebUi"
    }

    private val assets = context.assets
    private val server = LoopbackHttpServer(port, ::route)

    val baseUrl: String get() = "http://127.0.0.1:$PORT"

    val isRunning: Boolean get() = server.isRunning

    fun start() = server.start()

    fun stop() = server.stop()

    private fun route(request: LoopbackHttpServer.Request): LoopbackHttpServer.Response {
        val path = request.path
        return try {
            when {
                path == "/health" -> json("""{"ok":true}""")

                // --- pairing -------------------------------------------------------
                path == "/api/client-auth/connection/candidates" ->
                    json("""[{"url":${q(agent.baseUrl)},"label":"dev2go on-device"}]""")

                path == "/api/client-auth/pairing/redeem" ->
                    redeem(request)

                // --- phone files ---------------------------------------------------
                path.startsWith("/d2go/roots") -> roots()
                path.startsWith("/d2go/list") -> list(request.query["path"].orEmpty())
                path.startsWith("/d2go/read") -> read(request.query["path"].orEmpty())
                path.startsWith("/d2go/write") ->
                    if (request.method == "POST") write(request.query["path"].orEmpty(), request.body) else methodNotAllowed()

                path == "/" || path == "/index.html" || path == "/mobile.html" -> document()

                path.startsWith("/assets/") -> asset(path.removePrefix("/assets/"))

                else -> notFound()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "${request.method} $path failed: ${e.message}")
            json("""{"error":${q(e.message ?: "failed")}}""", 500)
        }
    }

    // ---- pairing ----

    /**
     * Answers the pairing handshake.
     *
     * The app posts a label and expects `{ ok: true, clientToken }`; the token becomes the
     * credential it sends to opencode. Here the token *is* opencode's server password,
     * because this app generated it and passed it to the child process at launch — there is
     * no OpenChamber server to mint one.
     *
     * The patched bundle turns it into a Basic header, which is the only form opencode
     * accepts.
     */
    private fun redeem(request: LoopbackHttpServer.Request): LoopbackHttpServer.Response {
        if (agent.password.isEmpty()) {
            return json("""{"ok":false,"error":"agent is not running"}""", 400)
        }
        Log.i(TAG, "paired client: ${request.body.take(120)}")
        return json("""{"ok":true,"clientToken":${q(agent.password)},"server":{"label":"dev2go on-device"}}""")
    }

    // ---- phone files ----

    private fun roots(): LoopbackHttpServer.Response {
        val items = storage.roots().joinToString(",") { root ->
            """{"label":${q(root.label)},"location":${q(root.location)},"access":${q(root.access)}}"""
        }
        return json("""{"roots":[$items]}""")
    }

    private fun list(path: String): LoopbackHttpServer.Response {
        val entries = storage.list(path.ifBlank { "/" })
            // A null result is the traversal check failing. Report it as absent rather than
            // as empty, so "denied" and "empty directory" stay distinguishable.
            ?: return json("""{"error":"not found"}""", 404)
        val items = entries.joinToString(",") { e ->
            """{"name":${q(e.name)},"path":${q(e.path)},"directory":${e.directory},"size":${e.sizeBytes}}"""
        }
        return json("""{"path":${q(path)},"entries":[$items]}""")
    }

    private fun read(path: String): LoopbackHttpServer.Response {
        val stat = storage.stat(path) ?: return json("""{"error":"not found"}""", 404)
        val text = storage.readText(path)
            ?: return json("""{"error":"${if (stat.second > 2 * 1024 * 1024) "too large to preview" else "unreadable"}"}""", 413)
        return json("""{"name":${q(stat.first)},"size":${stat.second},"content":${q(text)}}""")
    }

    private fun write(path: String, body: String): LoopbackHttpServer.Response =
        if (storage.write(path, body)) json("""{"ok":true}""")
        else json("""{"error":"write failed"}""", 400)

    // ---- static ----

    /**
     * Serves `mobile.html` with a bootstrap script injected ahead of the app's own.
     *
     * The injected script pre-seeds the app's saved-connection list in localStorage so it
     * connects straight to the on-device opencode instead of asking the user to pair. That
     * sidesteps OpenChamber's pairing machinery entirely, which is a state machine built
     * around connecting to an *OpenChamber server* — it has a serverId, relay candidates
     * and LAN discovery behind `connection/candidates`, not a simple list.
     *
     * Injecting into the document beats calling `evaluateJavascript` after load: the app
     * reads localStorage during its first module evaluation, so anything run afterwards
     * arrives a tick too late.
     */
    private fun document(): LoopbackHttpServer.Response {
        val html = readAsset("$ASSET_ROOT/mobile.html")?.let { String(it, Charsets.UTF_8) }
            ?: return notFound()
        return LoopbackHttpServer.Response(
            200,
            html.replace("<head>", "<head>\n$BOOTSTRAP"),
            "text/html; charset=utf-8",
        )
    }

    /**
     * Pre-seeds the connection and reports page errors back to logcat.
     *
     * The error reporting is not decoration. Without a WebChromeClient the page's
     * `console.error` and uncaught exceptions go nowhere, so a white screen and a missing
     * provider look identical from outside the app — which is exactly the situation that
     * produced the last round of guesswork.
     */
    private val bootstrap: String
        get() = """
<script>
(function () {
  // Pre-seed the saved connection. Shape recovered from the mobile bundle:
  //   localStorage["openchamber.mobile.connections.v1"] = [{ id, label, candidates, clientToken }]
  //   candidates: [{ kind: "direct", url }]
  try {
    var KEY = "openchamber.mobile.connections.v1";
    var seed = [{
      id: "dev2go",
      label: "dev2go on-device",
      candidates: [{ kind: "direct", url: ${q(agent.baseUrl)} }],
      clientToken: ${q(agent.password)}
    }];
    var existing = [];
    try { existing = JSON.parse(localStorage.getItem(KEY) || "[]"); } catch (e) {}
    if (!Array.isArray(existing)) existing = [];
    var kept = existing.filter(function (c) { return c && c.id !== "dev2go"; });
    localStorage.setItem(KEY, JSON.stringify(seed.concat(kept)));

    // Some builds read this too.
    localStorage.setItem("openchamber.mobile.server", ${q(agent.baseUrl)});
    localStorage.setItem("openchamber.mobile.token", ${q(agent.password)});
  } catch (e) {
    console.error("[dev2go] connection seed failed", e);
  }

  window.__dev2goErrors = [];
  window.addEventListener("error", function (e) {
    var line = "[dev2go] error: " + (e.message || "unknown") +
      (e.filename ? " @ " + e.filename + ":" + e.lineno : "");
    window.__dev2goErrors.push(line);
    console.error(line);
  });
  window.addEventListener("unhandledrejection", function (e) {
    var line = "[dev2go] unhandled rejection: " + (e.reason && e.reason.message ? e.reason.message : e.reason);
    window.__dev2goErrors.push(line);
    console.error(line);
  });
  console.log("[dev2go] bootstrap ok, agent at ${q(agent.baseUrl)}");
})();
</script>
""".trimIndent()

    private val BOOTSTRAP: String get() = bootstrap

    /**
     * Streams one asset.
     *
     * Path segments are validated before the name is built: the URL is attacker-controlled
     * from the WebView's point of view, and `../../databases/foo` must not resolve. Only
     * `assets/` is reachable, which is also why the agent binary in the APK's assets is not
     * fetchable through here — it is not under the OpenChamber directory.
     */
    private fun asset(relative: String): LoopbackHttpServer.Response {
        val clean = relative.trim('/')
        if (clean.isEmpty() || clean.contains("..") || clean.contains('\\')) return notFound()

        val bytes = readAsset("$ASSET_ROOT/assets/$clean") ?: return notFound()
        return LoopbackHttpServer.Response(200, String(bytes, Charsets.UTF_8), mimeFor(clean))
    }

    private fun readAsset(name: String): ByteArray? = runCatching {
        assets.open(name).use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
    }.getOrNull()

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        "map" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun json(body: String, status: Int = 200) =
        LoopbackHttpServer.Response(status, body, "application/json; charset=utf-8")

    private fun notFound() = json("""{"error":"not found"}""", 404)
    private fun methodNotAllowed() = json("""{"error":"method not allowed"}""", 405)

    private fun q(value: String): String = Json.quote(value)
}