package com.dev2go.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal HTTP helper. Separate from the LLM client because it must never stream. */
object Http {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** GETs [url], returning null on any failure rather than throwing into a UI flow. */
    suspend fun text(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }

    /** POSTs JSON and returns the body, or null on any failure. Used by the Ollama facade. */
    fun postJson(url: String, json: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()
}

/** Progress of an in-flight download, as fractions rather than bytes so the UI stays simple. */
data class DownloadProgress(
    val reference: com.dev2go.core.OllamaModelRef,
    val bytesRead: Long,
    val totalBytes: Long,
    val done: Boolean = false,
) {
    /** Null before the response headers arrive, when the total is still unknown. */
    val fraction: Float?
        get() = if (totalBytes > 0) (bytesRead.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null
}

sealed interface DownloadResult {
    data class Done(val file: File) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

/**
 * Downloads GGUF blobs to app storage.
 *
 * Downloads to `<name>.<tag>.part` and renames on completion. A half-written file named
 * `qwen3:0.6b.gguf` would be picked up by the model list and then fail at load with a
 * truncated-GGUF error, which reads like a corrupt download rather than an interrupted one.
 */
class ModelDownloader(private val modelsDir: File) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Long: a multi-gigabyte blob over a slow connection is minutes of no data, and a
        // read timeout would abort a perfectly healthy download.
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    fun targetFile(reference: com.dev2go.core.OllamaModelRef): File =
        File(modelsDir, "${reference.name}-${reference.tag}.gguf")

    fun isDownloaded(reference: com.dev2go.core.OllamaModelRef): Boolean =
        targetFile(reference).let { it.isFile && it.length() > 0 }

    /**
     * Streams [url] to disk, reporting progress.
     *
     * Resumes with a Range request when a partial file is already present, so a download
     * interrupted by the app being backgrounded continues instead of starting over.
     */
    suspend fun download(
        reference: com.dev2go.core.OllamaModelRef,
        url: String,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val target = targetFile(reference)
        val partial = File(target.absolutePath + ".part")

        val existing = partial.takeIf { it.isFile }?.length() ?: 0L
        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use DownloadResult.Failed("HTTP ${response.code} from the registry")
                }
                val total = (response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: response.body?.contentLength()?.takeIf { it > 0 }
                    ?: 0L) + existing

                var read = existing
                // Append when resuming, truncate when starting. Opening once matters: a
                // second open without append would rewrite the first `existing` bytes.
                val out = java.io.FileOutputStream(partial, existing > 0)

                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        read += n
                        onProgress(DownloadProgress(reference, read, total))
                    }
                }
                out.flush()
                out.close()

                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    return@use DownloadResult.Failed("Could not finalise ${target.name}")
                }
                onProgress(DownloadProgress(reference, read, read, done = true))
                DownloadResult.Done(target)
            }
        }.getOrElse { DownloadResult.Failed(it.message ?: it::class.java.simpleName) }
    }

    /** Progress for models already on disk, so the picker can show real state. */
    fun installed(): List<File> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
}