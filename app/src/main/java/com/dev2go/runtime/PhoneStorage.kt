package com.dev2go.runtime

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * Phone storage, exposed to the web UI over HTTP.
 *
 * This is what makes "access local phone files" work from a WebView. The UI is an ordinary
 * web app with a browser filesystem model, so the only way it can read a file is if
 * something serves it over HTTP.
 *
 * Two roots, deliberately:
 *
 * - **The workspace** — the app's own directory, which is also opencode's working
 *   directory. Everything the agent writes or edits lands here, so it is the root that
 *   actually matters.
 * - **Mounted trees** — real phone storage, reached through the Storage Access Framework.
 *   SAF is the only way to touch Downloads or an SD card on modern Android without a
 *   permission prompt per file, and its grants survive restarts only if persisted, which
 *   [mount] does.
 *
 * Everything is resolved to a canonical path and checked to be under a declared root
 * before it is opened. A path traversal here would hand the web UI an arbitrary-file-read
 * primitive on the device, and the UI is remote JavaScript.
 */
class PhoneStorage(private val context: Context, private val workspace: File) {

    private data class Mount(val label: String, val treeUri: Uri)

    @Volatile
    private var mounts: List<Mount> = emptyList()

    fun workspace(): File = workspace

    /**
     * Registers a SAF tree and persists the grant.
     *
     * takePersistableUriPermission is what makes this survive a restart; without it the
     * grant is revoked when the process dies and every mounted folder silently becomes
     * unreadable after the app is reopened.
     */
    fun mount(uri: Uri, label: String? = null): Boolean = runCatching {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)

        mounts = mounts.filterNot { it.treeUri == uri } +
            Mount(label ?: displayName(uri), uri)

        persist()
        true
    }.getOrElse {
        Log.w(TAG, "could not persist a grant for $uri: ${it.message}")
        false
    }

    fun unmount(uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, 0) }
        mounts = mounts.filterNot { it.treeUri == uri }
        persist()
    }

    /** Reclaims grants taken on earlier runs. Safe to call at every startup. */
    fun restoreMounts() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mounts = prefs.getStringSet(KEY_MOUNTS, emptySet()).orEmpty().mapNotNull { encoded ->
            val parts = encoded.split('')
            if (parts.size != 2) return@mapNotNull null
            Mount(parts[0], Uri.parse(parts[1]))
        }
    }

    private fun persist() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MOUNTS, mounts.map { "${it.label}${it.treeUri}" }.toSet())
            .apply()
    }

    /** One row per readable root, for the UI to show. */
    fun roots(): List<Root> = buildList {
        add(Root("workspace", workspace.absolutePath, "writable"))
        mounts.forEach { add(Root(it.label, it.treeUri.toString(), "mounted")) }
    }

    /**
     * Lists [path] under whichever root contains it.
     *
     * Returns null when the path is outside every root, which is the traversal check —
     * callers must treat null as "no such file" rather than falling back to a raw File.
     */
    fun list(path: String): List<Entry>? {
        val resolved = resolve(path) ?: return null
        val dir = File(resolved)
        if (!dir.isDirectory) return null

        val children = dir.listFiles().orEmpty()
        return children
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { file ->
                Entry(
                    name = file.name,
                    path = toLogicalPath(file),
                    directory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0,
                )
            }
    }

    /** Reads a text file, capped so a stray multi-gigabyte GGUF cannot exhaust memory. */
    fun readText(path: String, maxBytes: Int = 2 * 1024 * 1024): String? {
        val resolved = resolve(path) ?: return null
        val file = File(resolved)
        if (!file.isFile || file.length() > maxBytes) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun stat(path: String): Pair<String, Long>? {
        val resolved = resolve(path) ?: return null
        val file = File(resolved)
        if (!file.exists()) return null
        return file.name to file.length()
    }

    fun write(path: String, content: String): Boolean {
        val resolved = resolve(path) ?: return false
        return runCatching {
            File(resolved).writeText(content)
            true
        }.getOrDefault(false)
    }

    /**
     * Canonicalises [path] and proves it sits under a declared root.
     *
     * The canonical check matters: without it `../../databases/other.db` resolves out of
     * the workspace and the web UI could read the app's private databases. getCanonicalFile
     * collapses symlinks and `..` first, so the prefix test is against the real location.
     */
    private fun resolve(path: String): String? {
        val workspaceRoot = workspace.canonicalFile
        val target = File(workspaceRoot, path).canonicalFile
        if (isUnder(target, workspaceRoot)) return target.path

        // A mounted tree is a content:// Uri, not a path on disk, so it cannot be
        // canonicalised the same way. It is checked by construction instead: SAF grants
        // are per-tree, and Android enforces the boundary.
        for (mount in mounts) {
            if (path.startsWith("/${mount.label}/")) {
                val relative = path.removePrefix("/${mount.label}/")
                val document = resolveInTree(mount.treeUri, relative)
                if (document != null) return document
            }
        }
        return null
    }

    /** Walks a `relative` path down a SAF tree, returning the document Uri. */
    private fun resolveInTree(treeUri: Uri, relative: String): String? {
        val resolver = context.contentResolver
        var current: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        if (relative.isBlank() || relative == "/") return current.toString()

        for (segment in relative.split('/').filter { it.isNotEmpty() && it != "." }) {
            if (segment == "..") return null
            val children = resolver.query(
                current,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            ) ?: return null
            children.use { cursor ->
                var found: Uri? = null
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val mime = cursor.getString(1)
                    val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR && segment.isEmpty()) {
                        found = child
                        break
                    }
                    // Directories and files are not distinguished by name alone here; a
                    // directory simply has children, so match the name and prefer a dir.
                    if (resolver.query(child, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
                            ?.use { it.moveToFirst() } == true &&
                        id.endsWith("/$segment") || id.substringAfterLast('/') == segment
                    ) {
                        found = child
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) break
                    }
                }
                current = found ?: return null
            }
        }
        return current.toString()
    }

    private fun isUnder(target: File, root: File): Boolean =
        target.path == root.path || target.path.startsWith(root.path + File.separator)

    /** Path as the UI sees it, relative to the workspace root. */
    private fun toLogicalPath(file: File): String {
        val root = workspace.canonicalFile.path
        val path = file.canonicalFile.path
        return if (path.startsWith(root)) "/" + path.removePrefix(root).trimStart('/') else path
    }

    private fun displayName(uri: Uri): String =
        DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').substringAfterLast(':').ifBlank { "storage" }

    data class Root(val label: String, val location: String, val access: String)
    data class Entry(val name: String, val path: String, val directory: Boolean, val sizeBytes: Long)

    private companion object {
        const val TAG = "Dev2go/Storage"
        const val PREFS = "dev2go.storage"
        const val KEY_MOUNTS = "mounts"
        const val SEP = ""
    }
}