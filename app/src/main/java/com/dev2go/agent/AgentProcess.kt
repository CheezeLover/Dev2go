package com.dev2go.agent

import android.content.Context
import android.util.Log
import com.dev2go.core.ServerAuth
import java.io.File
import java.util.concurrent.TimeUnit

/** Lifecycle of the embedded opencode server. */
sealed interface AgentState {
    data object Absent : AgentState
    data object Starting : AgentState
    data class Running(val baseUrl: String, val port: Int) : AgentState
    data class Failed(val reason: String) : AgentState
}

/**
 * Runs the opencode binary that ships in the APK.
 *
 * The binary is a Bun cross-compile of the real opencode (arm64, interpreter
 * /system/bin/linker64, linking only libc/libm/libdl), so this is not an emulator or a
 * wrapper — it is the agent, on the phone.
 *
 * Two things worth knowing about how it is launched:
 *
 * - **A password is mandatory.** `opencode serve` generates one if `OPENCODE_PASSWORD` is
 *   unset, and rejects unauthenticated requests with 401. The app generates the password and
 *   passes it in the child's environment, so it never needs storing and never crosses the
 *   network. Binding to loopback is not itself protection: any app on the device can reach
 *   that port.
 * - **`serve` is not `serve --service`.** Service mode detaches, chdirs to the home
 *   directory and manages its own lifecycle, which is the wrong shape inside an app that
 *   already owns the process. Foreground mode keeps it a child this class owns.
 */
class AgentProcess(context: Context, private val port: Int = DEFAULT_PORT) {

    companion object {
        const val DEFAULT_PORT = 14096

        /** Bun's android runtime targets API 28; below this the binary will not exec. */
        const val MIN_SDK = 28

        private const val TAG = "Dev2go/Agent"

        /** Name of the binary inside the APK's assets, if one was bundled at build time. */
        private const val ASSET_NAME = "opencode-arm64"
    }

    private val appContext = context.applicationContext
    private val binDir = File(appContext.filesDir, "agent")

    @Volatile private var process: Process? = null
    @Volatile var password: String = ""
        private set

    @Volatile var state: AgentState = AgentState.Absent
        private set

    val isRunning: Boolean get() = process?.isAlive == true

    val baseUrl: String get() = "http://127.0.0.1:$port"

    /**
     * Copies the bundled binary out of assets into app storage, once.
     *
     * It has to be a real file on disk: Android will not exec straight out of the APK, and
     * `lib/<abi>/` is not an option either because the release build sets
     * `useLegacyPackaging = false`, so native libs are mapped from the APK rather than
     * extracted and an executable placed there never becomes a runnable file.
     *
     * Returns true when a binary is available afterwards.
     */
    fun installBundledIfPresent(): Boolean {
        val target = File(binDir, binaryName())
        if (target.isFile && target.canExecute()) return true

        return runCatching {
            appContext.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, /* ownerOnly = */ true)
            target.isFile && target.canExecute()
        }.getOrDefault(false)
    }

    /** True when the agent binary is present and looks runnable on this ABI. */
    fun isInstalled(): Boolean = binary()?.canExecute() == true

    /**
     * The agent binary, or null with a reason.
     *
     * Prefers the copy in app storage — extracted from assets on first run, and the one a
     * user can replace. Falls back to the extracted native lib dir, which is where a
     * binary placed under `lib/<abi>/` ends up.
     */
    fun binary(): File? =
        File(binDir, binaryName())?.takeIf { it.isFile && it.canExecute() }
            ?: appContext.applicationInfo.nativeLibraryDir?.let { File(it, binaryName()) }
                ?.takeIf { it.isFile && it.canExecute() }

    private fun binaryName(): String = when {
        android.os.Build.SUPPORTED_ABIS?.firstOrNull()?.startsWith("arm64") == true -> "opencode-arm64"
        else -> "opencode-${android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"}"
    }

    /**
     * Starts the server and blocks until it answers or [timeoutMs] elapses.
     *
     * Loading a 192 MB binary off phone storage is slow enough that returning early would
     * hand the UI a server that is not listening yet.
     */
    fun start(timeoutMs: Long = 90_000): AgentState {
        if (isRunning) return state

        val binary = binary()
        if (binary == null) {
            return fail("No opencode binary for this device. Expected ${binaryName()}.")
        }

        state = AgentState.Starting
        password = ServerAuth.generatePassword()

        return try {
            val builder = ProcessBuilder(
                binary.absolutePath,
                "serve",
                "--hostname", "127.0.0.1",
                "--port", port.toString(),
            )
            // A private config and data directory: the agent keeps sessions and a database,
            // and this keeps them inside the app rather than in shared storage.
            val configDir = File(appContext.filesDir, "opencode").apply { mkdirs() }
            builder.environment()["OPENCODE_PASSWORD"] = password
            builder.environment()["OPENCODE_CONFIG_DIR"] = configDir.absolutePath
            // A phone has no git, no /usr/bin and no shell worth inheriting, and the
            // banner plus TUI assets cost time the agent does not need to be useful.
            builder.environment()["TERM"] = "dumb"
            builder.redirectErrorStream(true)

            val process = builder.start()
            this.process = process

            // Drain the child's output. Leaving it unread fills the pipe buffer and blocks
            // the server the first time it logs enough to fill 64 KB.
            Thread({ pumpLogs(process) }, "opencode-logs").apply { isDaemon = true }.start()

            if (awaitReady(timeoutMs)) {
                AgentState.Running(baseUrl, port).also { state = it }
            } else {
                fail("opencode did not start listening on port $port")
            }
        } catch (e: Throwable) {
            fail(e.message ?: e::class.java.simpleName)
        }
    }

    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                // It ignores SIGTERM when a session is mid-flight; escalating is not a
                // nicety, it is what stops the port staying bound after the app is gone.
                p.destroyForcibly()
            }
        }
        process = null
        state = AgentState.Absent
    }

    private fun fail(reason: String): AgentState {
        Log.w(TAG, "agent failed: $reason")
        state = AgentState.Failed(reason)
        return state
    }

    /** Polls `/api/session` with auth, which is the cheapest proof the server is up. */
    private fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val auth = ServerAuth.header(password)
        val client = java.net.HttpURLConnection::class.java
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return false
            try {
                val connection = (java.net.URL("$baseUrl/api/session").openConnection() as java.net.HttpURLConnection)
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", auth)
                connection.connectTimeout = 2_000
                connection.readTimeout = 5_000
                val code = connection.responseCode
                connection.disconnect()
                if (code == 200) return true
            } catch (_: Throwable) {
                // Not listening yet. Expected for most of the wait.
            }
            Thread.sleep(300)
        }
        return false
    }

    private fun pumpLogs(process: Process) {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { Log.i(TAG, it) }
        }
    }
}