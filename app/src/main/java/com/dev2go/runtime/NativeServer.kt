package com.dev2go.runtime

/**
 * JNI bridge to the embedded llama.cpp server.
 *
 * The native side is [dev2go_server.cpp], which embeds llama.cpp's own `llama_server` rather
 * than reimplementing inference — so the routes this exposes are llama-server's own:
 * `/v1/models`, `/v1/chat/completions`, `/v1/completions`, `/health`, `/props`.
 *
 * The library is loaded in a guarded initialiser rather than a plain `System.loadLibrary`
 * so a missing or ABI-mismatched `.so` surfaces as [available] = false with a reason the UI
 * can show. An unguarded load throws ExceptionInInitializerError from a static block, which
 * on Android takes the process down before any UI exists to explain it.
 */
object NativeServer {

    /** Why the native library could not be loaded, or null if it loaded. */
    val loadError: String? = try {
        System.loadLibrary("dev2go")
        null
    } catch (e: Throwable) {
        "libdev2go.so failed to load: ${e.message ?: e::class.java.simpleName}"
    }

    /** True when the native runtime is present and callable. */
    val available: Boolean get() = loadError == null

    /**
     * Loads [modelPath] and starts the server on 127.0.0.1:[port].
     *
     * Blocks until the server answers `/health` or [readyTimeoutMs] elapses — loading a
     * multi-gigabyte GGUF off phone storage is slow, and returning early would hand the app
     * a server that is not yet able to serve. Call it off the main thread.
     *
     * Returns false on failure; call [isRunning] to tell a failed start from a stop.
     */
    external fun start(modelPath: String, port: Int, nCtx: Int, nThreads: Int, nBatch: Int, nUbatch: Int): Boolean

    external fun stop()

    external fun isRunning(): Boolean

    /** llama.cpp's own build description, e.g. "AVX2 = 1 | AVX = 1 | ..." */
    external fun backendVersion(): String
}