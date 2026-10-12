package com.dev2go.runtime

import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** What the runtime is doing right now, for the UI to render. */
sealed interface ServerState {
    data object Idle : ServerState
    data class Starting(val model: String) : ServerState
    data class Running(val model: String, val baseUrl: String, val modelsJson: String) : ServerState
    data class Failed(val reason: String) : ServerState
}

/**
 * Owns the embedded LLM server's lifecycle.
 *
 * Deliberately a plain object with no Android dependencies beyond Context for filesDir, so
 * the start/probe sequence can be exercised without an emulator.
 */
class LlmServer(private val filesDir: File) {

    companion object {
        /**
         * 8080 is llama.cpp's own default. Bound to 127.0.0.1 only — this is a loopback
         * service for the agent in the same app, not something the network should reach.
         */
        const val PORT = 8080

        /**
         * Small by default. A phone running an agent needs room for system prompt, tool
         * definitions, file contents and history; 8k is the floor where a real session
         * starts truncating mid-conversation rather than failing loudly.
         */
        const val N_CTX = 8192

        /**
         * Physical cores, not logical. Phones schedule big.LITTLE, and giving the little
         * cores to inference costs more in migration than they return.
         */
        fun recommendedThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }

    private val _state = AtomicReference<ServerState>(ServerState.Idle)
    val state: ServerState get() = _state.get()

    val isRunning: Boolean get() = NativeServer.available && NativeServer.isRunning()

    /** Where the agent should point its provider at. */
    val baseUrl: String get() = "http://127.0.0.1:$PORT"

    /**
     * Starts the server on [modelFile] and reads back what it reports.
     *
     * Returns the running state on success, [ServerState.Failed] otherwise. The probe is a
     * real HTTP request rather than a trust in the native return value: the native side
     * only knows it reached `/health`, and the thing worth confirming is that a client can
     * actually enumerate the model.
     */
    fun start(modelFile: File, probe: (String) -> String): ServerState {
        if (!modelFile.isFile) {
            return ServerState.Failed("No model at ${modelFile.path}")
        }
        if (!NativeServer.available) {
            return ServerState.Failed(NativeServer.loadError ?: "Native runtime unavailable")
        }
        if (isRunning) {
            return _state.get()
        }

        _state.set(ServerState.Starting(modelFile.name))
        return try {
            val threads = recommendedThreads()
            val ok = NativeServer.start(
                modelFile.absolutePath,
                PORT,
                N_CTX,
                threads,
                /* n_batch = */ 256,
                /* n_ubatch = */ 256,
            )
            if (!ok) {
                ServerState.Failed("Server did not become ready within the timeout")
            } else {
                ServerState.Running(modelFile.name, baseUrl, probe(baseUrl))
            }
        } catch (e: Throwable) {
            ServerState.Failed(e.message ?: e::class.java.simpleName)
        }.also { _state.set(it) }
    }

    fun stop(): ServerState {
        if (NativeServer.available) {
            runCatching { NativeServer.stop() }
        }
        return ServerState.Idle.also { _state.set(it) }
    }

    /** Copies a user-picked GGUF into app storage so it survives the picker being revoked. */
    fun importModel(source: java.io.InputStream, fileName: String): File {
        val target = File(modelsDir(), fileName)
        source.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }

    fun modelsDir(): File = File(filesDir, "models").apply { mkdirs() }

    fun importedModels(): List<File> =
        modelsDir().listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
}