package com.dev2go.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dev2go.runtime.LlmServer
import com.dev2go.runtime.NativeServer
import com.dev2go.runtime.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class RuntimeUiState(
    val imported: List<File> = emptyList(),
    val selected: File? = null,
    val server: ServerState = ServerState.Idle,
    val busy: Boolean = false,
)

class RuntimeViewModel(app: Application) : AndroidViewModel(app) {

    private val llm = LlmServer(app.filesDir)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(RuntimeUiState())
    val state: StateFlow<RuntimeUiState> = _state.asStateFlow()

    val nativeAvailable: Boolean get() = NativeServer.available
    val nativeError: String? get() = NativeServer.loadError
    val threads: Int get() = LlmServer.recommendedThreads()
    val backend: String? get() = runCatching { NativeServer.backendVersion() }.getOrNull()

    val abis: String
        get() = android.os.Build.SUPPORTED_ABIS?.joinToString(", ") ?: "unknown"

    /** Requested by the screen; the Activity owns the picker because it needs a ResultLauncher. */
    var pickModel: () -> Unit = {}

    init {
        refreshModels()
    }

    fun refreshModels() {
        val files = llm.importedModels()
        _state.value = _state.value.copy(
            imported = files,
            selected = _state.value.selected ?: files.firstOrNull(),
        )
    }

    /** Copies the picked file into app storage, so it survives the picker being revoked. */
    fun onModelPicked(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val name = runCatching {
                uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "model.gguf"
            }.getOrDefault("model.gguf")

            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        llm.importModel(input, name)
                    }
                }.getOrNull()
            }
            _state.value = _state.value.copy(busy = false, selected = imported)
            refreshModels()
        }
    }

    fun select(file: File) {
        _state.value = _state.value.copy(selected = file)
    }

    fun start() {
        val model = _state.value.selected ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, server = ServerState.Starting(model.name))
            // Loading a multi-gigabyte GGUF and waiting for the server to answer is seconds
            // to tens of seconds. On the main thread this is an ANR, not a slow UI.
            val result = withContext(Dispatchers.IO) { llm.start(model, ::probeModels) }
            _state.value = _state.value.copy(busy = false, server = result)
        }
    }

    fun stop() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = llm.stop()
            _state.value = _state.value.copy(busy = false, server = result)
        }
    }

    /**
     * Reads back what the running server reports about itself.
     *
     * Deliberately a real request over loopback rather than a summary handed back by the
     * native layer: the native side only knows it reached /health, whereas this confirms a
     * client can enumerate the model, which is the claim the agent depends on.
     */
    private fun probeModels(baseUrl: String): String = try {
        val request = Request.Builder().url("$baseUrl/v1/models").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) "HTTP ${response.code}: ${body.take(300)}" else body
        }
    } catch (e: Throwable) {
        "probe failed: ${e.message ?: e::class.java.simpleName}"
    }

    override fun onCleared() {
        llm.stop()
        super.onCleared()
    }
}