package com.dev2go.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dev2go.agent.AgentProcess
import com.dev2go.agent.AgentState
import com.dev2go.agent.OpencodeClient
import com.dev2go.core.OllamaModelRef
import com.dev2go.core.Message
import com.dev2go.core.ModelRef
import com.dev2go.core.ProviderConfig
import com.dev2go.core.Session
import com.dev2go.models.CatalogEntry
import com.dev2go.models.DownloadProgress
import com.dev2go.models.DownloadResult
import com.dev2go.models.ModelCatalog
import com.dev2go.models.ModelDownloader
import com.dev2go.runtime.LlmServer
import com.dev2go.runtime.NativeServer
import com.dev2go.runtime.OllamaFacade
import com.dev2go.runtime.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which pane is showing. */
enum class Tab { Chat, Models, Runtime }

data class UiState(
    val tab: Tab = Tab.Chat,
    val sessions: List<Session> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val composer: String = "",
    val sending: Boolean = false,
    val streamingNote: String? = null,

    val agentState: AgentState = AgentState.Absent,
    val llmState: ServerState = ServerState.Idle,
    val modelsJson: String = "",

    val library: List<String> = emptyList(),
    val catalog: List<CatalogEntry> = emptyList(),
    val catalogLoading: Boolean = false,
    val chosenModel: String? = null,
    val installed: List<File> = emptyList(),
    /** The GGUF the LLM server will load. Null until one is chosen or downloaded. */
    val activeModelFile: File? = null,
    val download: DownloadProgress? = null,
    val selectedDownload: CatalogEntry? = null,

    val selectedModel: ModelRef? = null,
    val availableModels: List<ModelRef> = emptyList(),
    val log: List<String> = emptyList(),
) {
    val busy: Boolean get() = sending || llmState is ServerState.Starting || agentState is AgentState.Starting
}

/**
 * Owns everything: the agent process, the model runtime, the Ollama facade and the session
 * state the UI renders.
 *
 * Deliberately one ViewModel rather than several. The three subsystems have a startup
 * order — models before the agent can use them, the agent before a session can exist — and
 * splitting that across ViewModels means encoding the order in navigation events rather
 * than in one place.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val agent = AgentProcess(app)
    private val llm = LlmServer(app.filesDir)
    private val facade = OllamaFacade()
    private val catalog = ModelCatalog(app.filesDir)
    private val downloader = ModelDownloader(File(app.filesDir, "models"))

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Non-null once the agent is up; every API call goes through it. */
    private var client: OpencodeClient? = null
    private var eventsJob: Job? = null

    val nativeAvailable: Boolean get() = NativeServer.available
    val nativeError: String? get() = NativeServer.loadError
    val abis: String
        get() = android.os.Build.SUPPORTED_ABIS?.joinToString(", ") ?: "unknown"
    val backend: String? get() = runCatching { NativeServer.backendVersion() }.getOrNull()
    val agentInstalled: Boolean get() = agent.isInstalled()

    init {
        // Pull the bundled agent out of assets the first time the app runs, so the user
        // does not have to push a 192 MB binary before the app is useful.
        val installed = agent.installBundledIfPresent()
        if (installed) note("Bundled opencode binary installed.")
        _state.value = _state.value.copy(
            agentState = agent.state,
            llmState = llm.state,
            installed = downloader.installed(),
            activeModelFile = downloader.installed().firstOrNull(),
        )
    }

    // ---- tabs ----

    fun select(tab: Tab) {
        _state.value = _state.value.copy(tab = tab)
        if (tab == Tab.Models && _state.value.library.isEmpty()) loadLibrary()
        if (tab == Tab.Runtime) refreshRuntime()
    }

    // ---- model runtime ----

    /** Starts the LLM server and the Ollama facade on top of it. */
    fun startLlm() {
        val model = _state.value.selectedModelFile() ?: run {
            note("Pick or download a GGUF first.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(llmState = ServerState.Starting(model.name), tab = Tab.Runtime)
            val result = withContext(Dispatchers.IO) { llm.start(model, ::probe) }
            _state.value = _state.value.copy(llmState = result)
            if (result is ServerState.Running) {
                facade.loadedModel = OllamaModelRef.parse(model.nameWithoutExtension)
                    ?: OllamaModelRef("ondevice", "latest")
                runCatching { facade.start() }
                note("Ollama facade on ${facade.providerBaseUrl()}")
                registerProvider()
            } else {
                note("LLM failed: ${(result as? ServerState.Failed)?.reason}")
            }
        }
    }

    fun stopLlm() {
        viewModelScope.launch(Dispatchers.IO) {
            facade.stop()
            val result = llm.stop()
            _state.value = _state.value.copy(llmState = result, modelsJson = "")
        }
    }

    /** Starts the opencode binary and begins listening to its event stream. */
    fun startAgent() {
        viewModelScope.launch {
            _state.value = _state.value.copy(agentState = AgentState.Starting, tab = Tab.Runtime)
            val result = withContext(Dispatchers.IO) { agent.start() }
            _state.value = _state.value.copy(agentState = result)
            when (result) {
                is AgentState.Running -> {
                    client = OpencodeClient(result.baseUrl, agent.password)
                    listenForEvents()
                    refreshSessions()
                    note("Agent listening on ${result.baseUrl}")
                }
                is AgentState.Failed -> note("Agent failed: ${result.reason}")
                else -> Unit
            }
        }
    }

    fun stopAgent() {
        eventsJob?.cancel()
        eventsJob = null
        client = null
        viewModelScope.launch(Dispatchers.IO) { agent.stop() }
        _state.value = _state.value.copy(
            agentState = AgentState.Absent,
            sessions = emptyList(),
            activeSessionId = null,
            messages = emptyList(),
        )
    }

    fun refreshRuntime() {
        _state.value = _state.value.copy(
            agentState = agent.state,
            llmState = llm.state,
            installed = downloader.installed(),
        )
        val c = client ?: return
        viewModelScope.launch {
            val models = runCatching { c.listModels() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(availableModels = models)
        }
    }

    /** Marks a just-downloaded GGUF as the one to load. */
    private fun adoptAsActive(file: File) {
        _state.value = _state.value.copy(activeModelFile = file)
    }

    // ---- sessions ----

    fun refreshSessions() {
        val c = client ?: return
        viewModelScope.launch {
            val sessions = runCatching { c.listSessions() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(
                sessions = sessions,
                activeSessionId = _state.value.activeSessionId
                    ?: sessions.firstOrNull()?.id,
            )
            _state.value.activeSessionId?.let { loadMessages(it) }
        }
    }

    fun newSession() {
        val c = client ?: return
        viewModelScope.launch {
            val session = runCatching { c.createSession("New session") }.getOrNull()
            if (session == null || session.id.isEmpty()) {
                note("Could not create a session.")
                return@launch
            }
            _state.value = _state.value.copy(
                sessions = listOf(session) + _state.value.sessions,
                activeSessionId = session.id,
                messages = emptyList(),
            )
        }
    }

    fun openSession(id: String) {
        _state.value = _state.value.copy(activeSessionId = id)
        loadMessages(id)
    }

    private fun loadMessages(sessionId: String) {
        val c = client ?: return
        viewModelScope.launch {
            val messages = runCatching { c.messages(sessionId) }.getOrDefault(emptyList())
            // Drop a stale list if the user switched sessions while this was in flight.
            if (_state.value.activeSessionId == sessionId) {
                _state.value = _state.value.copy(messages = messages)
            }
        }
    }

    fun onComposerChange(text: String) {
        _state.value = _state.value.copy(composer = text)
    }

    fun send() {
        val c = client ?: run { note("Start the agent first."); return }
        val sessionId = _state.value.activeSessionId ?: run { newSession(); return }
        val text = _state.value.composer.trim()
        if (text.isEmpty()) return

        _state.value = _state.value.copy(
            sending = true,
            composer = "",
            streamingNote = "working…",
            // Show the user's message immediately. Waiting for the round trip makes the
            // composer feel broken on a phone, where the turn can take many seconds.
            messages = _state.value.messages + Message(
                id = "local-${System.currentTimeMillis()}",
                type = "user",
                text = text,
            ),
        )

        viewModelScope.launch {
            val model = _state.value.selectedModel
            runCatching { c.prompt(sessionId, text, model) }
                .onFailure { note("Prompt failed: ${it.message}") }
            _state.value = _state.value.copy(sending = false, streamingNote = null)
            loadMessages(sessionId)
        }
    }

    fun stopTurn() {
        val c = client ?: return
        val sessionId = _state.value.activeSessionId ?: return
        viewModelScope.launch {
            runCatching { c.interrupt(sessionId) }
            _state.value = _state.value.copy(sending = false, streamingNote = null)
        }
    }

    fun selectModel(ref: ModelRef) {
        _state.value = _state.value.copy(selectedModel = ref)
    }

    // ---- events ----

    private fun listenForEvents() {
        val c = client ?: return
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            runCatching {
                c.events().collect { event ->
                    when {
                        event.type.startsWith("session.") -> {
                            val sessionId = event.string("sessionID")
                            // Re-read rather than patch: the event payloads carry partial
                            // session objects, and a list that disagrees with the server is
                            // worse than one that is briefly stale.
                            sessionId?.let { if (it == _state.value.activeSessionId) loadMessages(it) else refreshSessions() }
                        }
                        event.type.startsWith("message.") -> {
                            event.string("sessionID")?.let { if (it == _state.value.activeSessionId) loadMessages(it) }
                        }
                    }
                }
            }
        }
    }

    // ---- models ----

    fun loadLibrary() {
        viewModelScope.launch {
            _state.value = _state.value.copy(catalogLoading = true)
            val library = runCatching { catalog.libraryModels() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(library = library, catalogLoading = false)
            val first = _state.value.chosenModel ?: library.firstOrNull()
            if (first != null) {
                _state.value = _state.value.copy(chosenModel = first)
                loadTags(first)
            }
        }
    }

    fun chooseLibraryModel(name: String) {
        _state.value = _state.value.copy(chosenModel = name, catalog = emptyList())
        loadTags(name)
    }

    private fun loadTags(model: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(catalogLoading = true)
            val entries = runCatching { catalog.entries(model) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(catalog = entries, catalogLoading = false)
        }
    }

    fun download(entry: CatalogEntry) {
        viewModelScope.launch {
            val url = runCatching { catalog.downloadUrl(entry.reference) }.getOrNull()
            if (url == null) {
                note("No GGUF blob for ${entry.reference}.")
                return@launch
            }
            _state.value = _state.value.copy(selectedDownload = entry)
            val result = downloader.download(entry.reference, url) { progress ->
                _state.value = _state.value.copy(download = progress)
            }
            when (result) {
                is DownloadResult.Done -> {
                    note("Downloaded ${result.file.name}")
                    adoptAsActive(result.file)
                    _state.value = _state.value.copy(
                        download = null,
                        selectedDownload = null,
                        installed = downloader.installed(),
                    )
                    registerProvider()
                }
                is DownloadResult.Failed -> {
                    note("Download failed: ${result.reason}")
                    _state.value = _state.value.copy(download = null, selectedDownload = null)
                }
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val name = runCatching {
                uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: "model.gguf"
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val target = File(File(getApplication<Application>().filesDir, "models"), name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { input.copyTo(it) }
                        target
                    }
                }.getOrNull()
            }
            if (imported == null) {
                note("Could not import that file.")
                return@launch
            }
            note("Imported ${imported.name}")
            adoptAsActive(imported)
            _state.value = _state.value.copy(installed = downloader.installed())
            registerProvider()
        }
    }

    /** Copies a user-picked opencode binary into app storage and marks it runnable. */
    fun importAgent(uri: Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(getApplication<Application>().filesDir, "agent/opencode-arm64")
                    target.parentFile?.mkdirs()
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    // The picker hands over a file that is not executable, and a binary the
                    // app cannot exec would fail at ProcessBuilder with a bare
                    // "Permission denied" that reads like a SELinux problem rather than a
                    // missing chmod.
                    target.setExecutable(true, /* ownerOnly = */ true)
                    target
                }.getOrNull()
            }
            if (imported == null) {
                note("Could not import that file.")
                return@launch
            }
            note("Installed agent binary ${imported.length().humanBytes()}")
            _state.value = _state.value.copy(agentState = agent.state)
        }
    }

    /**
     * Writes the on-device provider into the agent's config so a downloaded model shows up
     * in the agent's model picker.
     *
     * Merged rather than overwritten: the user's own providers must survive this.
     */
    private fun registerProvider() {
        val configFile = File(getApplication<Application>().filesDir, "opencode/opencode.json")
        val models = _state.value.installed.mapNotNull { file ->
            OllamaModelRef.parse(file.nameWithoutExtension)
        }
        if (models.isEmpty()) return

        val existing = runCatching { ProviderConfig.parseConfig(configFile.readText()) }
            .getOrElse { kotlinx.serialization.json.JsonObject(emptyMap()) }
        val updated = ProviderConfig.withProvider(existing, models, OllamaFacade.PORT)
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(updated.toString())
            note("Registered ${models.size} model(s) with the agent")
        }.onFailure { note("Could not register provider: ${it.message}") }
    }

    // ---- helpers ----

    private fun probe(baseUrl: String): String = runCatching {
        val connection = java.net.URL("$baseUrl/v1/models").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        body.take(600)
    }.getOrElse { "probe failed: ${it.message}" }

    private fun note(message: String) {
        _state.value = _state.value.copy(log = (listOf(message) + _state.value.log).take(40))
    }

    private fun Long.humanBytes(): String = when {
        this >= 1_000_000_000 -> "%.2f GB".format(this / 1_000_000_000.0)
        this >= 1_000_000 -> "%d MB".format(this / 1_000_000)
        else -> "%d kB".format(this / 1_000)
    }

    private fun UiState.selectedModelFile(): File? = activeModelFile

    /** Chooses the GGUF the runtime will load. */
    fun selectInstalled(file: File) {
        _state.value = _state.value.copy(activeModelFile = file)
    }

    override fun onCleared() {
        eventsJob?.cancel()
        facade.stop()
        llm.stop()
        agent.stop()
        super.onCleared()
    }
}