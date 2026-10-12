package com.dev2go.runtime

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal HTTP/1.1 server bound to loopback.
 *
 * Written rather than pulled in because the obvious candidates do not fit:
 * `com.sun.net.httpserver` is JDK-only and absent from Android, and NanoHTTPD 2.3.1 — the
 * last published release, from 2016 — does not have `setExecutor`, `respond` or
 * `MIMETYPE_JSON`, so extending it means working around its thread-per-request model on a
 * phone.
 *
 * Deliberately small: one request per connection, no keep-alive, no chunked encoding,
 * loopback only. That is enough for the five routes the Ollama facade serves and removes
 * every parsing edge case a general server would have to handle.
 */
class LoopbackHttpServer(
    private val port: Int,
    private val handler: (Request) -> Response,
) {

    data class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

    data class Response(val status: Int, val body: String, val contentType: String = "application/json; charset=utf-8")

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    @Throws(IOException::class)
    fun start() {
        if (running.getAndSet(true)) return
        val socket = ServerSocket()
        // Loopback only. Any app on the device can reach 127.0.0.1, so this is not a
        // security boundary — it is simply not something the network should reach.
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = socket

        acceptThread = Thread({ acceptLoop(socket) }, "dev2go-http-${port}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        val workers = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "dev2go-http-worker").apply { isDaemon = true }
        }
        try {
            while (running.get()) {
                val client = socket.accept()
                workers.execute { serveClient(client) }
            }
        } catch (_: IOException) {
            // Closed by stop(). Expected.
        } finally {
            workers.shutdownNow()
        }
    }

    private fun serveClient(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()

            val requestLine = input.readHttpLine() ?: return client.close()
            val parts = requestLine.split(' ')
            if (parts.size < 2) return client.close()
            val method = parts[0]
            val target = parts[1]

            var contentLength = 0
            while (true) {
                val line = input.readHttpLine() ?: return client.close()
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) input.readNBytes(contentLength).toString(Charsets.UTF_8) else ""

            val query = target.substringAfter('?', "").split('&')
                .filter { it.isNotEmpty() }
                .mapNotNull { pair ->
                    val kv = pair.split('=', limit = 2)
                    val key = java.net.URLDecoder.decode(kv[0], "UTF-8")
                    val value = if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
                    key to value
                }
                .toMap()

            val response = handler(Request(method, target.substringBefore('?'), query, body))
            write(client.getOutputStream(), response)
        } catch (_: Throwable) {
            // A malformed or abandoned request must not take the listener down.
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Reads one CRLF-terminated line.
     *
     * Hand-rolled rather than wrapped in a BufferedReader: the body has to be read as raw
     * bytes straight after the headers, and a reader would have swallowed up to a buffer
     * of them, leaving the body short by an unpredictable amount.
     */
    private fun InputStream.readHttpLine(): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = read()
            if (b == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().removeSuffix("\r")
            if (b != '\r'.code) buffer.append(b.toChar())
        }
    }

    private fun write(raw: java.io.OutputStream, response: Response) {
        val bytes = response.body.toByteArray()
        val out = BufferedOutputStream(raw)
        out.write(
            (
                "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
                    "Content-Type: ${response.contentType}\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    // The agent runs in the same process and does not care about CORS; a
                    // browser devtools session pointed at this port does.
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Error"
    }
}