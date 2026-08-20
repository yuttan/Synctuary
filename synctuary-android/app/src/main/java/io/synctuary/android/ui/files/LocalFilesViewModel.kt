package io.synctuary.android.ui.files

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalFileEntry(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val mimeType: String?,
    val uri: Uri,
)

/** A folder the user granted access to, shown at the browser's root. */
data class LocalRoot(
    val name: String,
    val uri: Uri,
)

/**
 * Human-readable label for a granted tree.
 *
 * [docName] is what DocumentFile reports, which is usually right but is
 * null for some providers. The fallback parses the tree URI's document
 * id (`primary:Pictures/Camera`), stripping the storage-volume prefix
 * and keeping the last path segment.
 */
internal fun localRootDisplayName(docName: String?, lastPathSegment: String?): String {
    if (!docName.isNullOrBlank()) return docName
    val seg = lastPathSegment ?: return "Folder"
    val afterVolume = seg.substringAfter(':', seg)
    val leaf = afterVolume.trimEnd('/').substringAfterLast('/')
    return when {
        leaf.isNotBlank() -> leaf
        // A whole-volume grant has nothing after the colon.
        seg.startsWith("primary:") -> "Internal storage"
        else -> seg.substringBefore(':').ifBlank { "Folder" }
    }
}

data class LocalFilesUiState(
    val currentPath: String = "",
    val entries: List<LocalFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val folderConfigured: Boolean = false,
    // Configured roots, listed when the user is above any of them.
    val roots: List<LocalRoot> = emptyList(),
    // True while showing the root picker rather than a directory listing.
    val atRootList: Boolean = true,
    // True when MANAGE_EXTERNAL_STORAGE is held: the whole shared storage
    // is browsable directly and SAF grants become unnecessary.
    val hasAllFilesAccess: Boolean = false,
)

class LocalFilesViewModel @JvmOverloads constructor(
    application: Application,
    private val prefsName: String = "synctuary-settings",
    private val prefKey: String = "download_folder_uri",
    private val rootsKey: String = "local_folder_roots",
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LocalFilesUiState())
    val uiState: StateFlow<LocalFilesUiState> = _uiState.asStateFlow()

    // Navigation stack for SAF-granted trees. Index 0 is the entered root,
    // last element is the current directory. Empty while at the root list.
    private val navStack = mutableListOf<DocumentFile>()

    // Parallel stack used when all-files access is held and we walk shared
    // storage with plain File APIs. Exactly one of the two stacks is
    // non-empty at any time; navigateInto/Up dispatch on that.
    private val fileNavStack = mutableListOf<File>()

    init {
        loadDirectory()
    }

    /**
     * Show the root list: every folder the user has granted access to.
     *
     * Android's scoped storage has no "browse the whole filesystem"
     * affordance an app may use — each tree must be granted through SAF
     * and its permission persisted. So instead of one hardcoded folder,
     * we keep a set of granted trees and present them as roots (mirroring
     * how server shares appear as root drives on the Files tab).
     */
    fun loadDirectory() {
        navStack.clear()
        fileNavStack.clear()
        val allFiles = hasAllFilesAccess()
        val roots = if (allFiles) readFileRoots() else readRoots()
        _uiState.update {
            it.copy(
                roots = roots,
                atRootList = true,
                folderConfigured = roots.isNotEmpty(),
                hasAllFilesAccess = allFiles,
                entries = emptyList(),
                currentPath = "",
                loading = false,
                error = null,
            )
        }
    }

    /**
     * True when the user granted MANAGE_EXTERNAL_STORAGE. With it we can
     * walk shared storage with plain File APIs, which is the only way to
     * reach Download/ and the storage roots — Android 11+ refuses to
     * hand those out through ACTION_OPEN_DOCUMENT_TREE.
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /**
     * Storage volumes exposed when all-files access is held. Shared
     * storage is listed first; secondary volumes (SD card, USB) follow.
     */
    private fun readFileRoots(): List<LocalRoot> {
        val roots = mutableListOf<LocalRoot>()
        val shared = Environment.getExternalStorageDirectory()
        if (shared != null && shared.isDirectory) {
            roots.add(LocalRoot(name = "Internal storage", uri = Uri.fromFile(shared)))
        }
        // Secondary volumes live beside the primary one; getExternalFilesDirs
        // is the supported way to discover them without extra permissions.
        val app = getApplication<Application>()
        app.getExternalFilesDirs(null)
            .filterNotNull()
            .drop(1) // index 0 is the primary volume, already added above
            .forEach { appDir ->
                // .../<volume>/Android/data/<pkg>/files -> <volume>
                val volume = generateSequence(appDir) { it.parentFile }
                    .firstOrNull { it.name.equals("Android", ignoreCase = true) }
                    ?.parentFile
                if (volume != null && volume.isDirectory) {
                    roots.add(LocalRoot(name = volume.name, uri = Uri.fromFile(volume)))
                }
            }
        return roots
    }

    /** Enter one of the configured roots. */
    fun openRoot(root: LocalRoot) {
        if (root.uri.scheme == "file") {
            val dir = root.uri.path?.let { File(it) }
            if (dir == null || !dir.isDirectory) {
                _uiState.update { it.copy(error = "Folder is no longer accessible: ${root.name}") }
                return
            }
            navStack.clear()
            fileNavStack.clear()
            fileNavStack.add(dir)
            listCurrentFileDirectory()
            return
        }

        val app = getApplication<Application>()
        val doc = DocumentFile.fromTreeUri(app, root.uri)
        if (doc == null || !doc.exists()) {
            _uiState.update { it.copy(error = "Folder is no longer accessible: ${root.name}") }
            return
        }
        fileNavStack.clear()
        navStack.clear()
        navStack.add(doc)
        listCurrentDirectory()
    }

    /**
     * Lists the current File-based directory. Used only under all-files
     * access; the SAF path goes through [listCurrentDirectory].
     */
    private fun listCurrentFileDirectory() {
        val dir = fileNavStack.lastOrNull() ?: return
        _uiState.update {
            it.copy(loading = true, error = null, folderConfigured = true, atRootList = false)
        }
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    (dir.listFiles() ?: emptyArray()).map { f ->
                        LocalFileEntry(
                            name = f.name,
                            size = if (f.isDirectory) 0L else f.length(),
                            lastModified = f.lastModified(),
                            isDirectory = f.isDirectory,
                            mimeType = if (f.isDirectory) null else guessMimeType(f.name),
                            uri = fileProviderUri(f),
                        )
                    }.sortedWith(
                        compareByDescending<LocalFileEntry> { it.isDirectory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    )
                }
                _uiState.update {
                    it.copy(
                        currentPath = buildFileDisplayPath(),
                        entries = entries,
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: "Failed to list files")
                }
            }
        }
    }

    /**
     * Content URI for a raw file. Upload/open/share all consume a URI, and
     * handing another app a `file://` URI throws FileUriExposedException,
     * so everything goes through the app's FileProvider.
     */
    private fun fileProviderUri(f: File): Uri {
        val app = getApplication<Application>()
        return try {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
        } catch (_: IllegalArgumentException) {
            // Outside the provider's configured roots — fall back to the
            // raw path so at least in-app listing keeps working.
            Uri.fromFile(f)
        }
    }

    private fun guessMimeType(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun buildFileDisplayPath(): String {
        if (fileNavStack.size <= 1) return ""
        return fileNavStack.drop(1).joinToString("/") { it.name }
    }

    /**
     * Persist a newly granted tree. The caller MUST already have taken
     * the persistable permission (see LocalFilesScreen) — without it the
     * grant is dropped on reboot and the root silently breaks.
     */
    fun addRoot(uri: Uri) {
        val prefs = prefs()
        val current = prefs.getStringSet(rootsKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(uri.toString())
        prefs.edit().putStringSet(rootsKey, current).apply()
        loadDirectory()
    }

    /**
     * Forget a root. Releases the persistable permission and drops it
     * from the set; nothing on disk is touched.
     */
    fun removeRoot(root: LocalRoot) {
        val app = getApplication<Application>()
        val prefs = prefs()
        val current = prefs.getStringSet(rootsKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(root.uri.toString())
        prefs.edit().putStringSet(rootsKey, current).apply()

        // The download folder is stored separately; clear it too when it
        // is the folder being removed, so Settings does not keep pointing
        // at a tree we no longer hold permission for.
        if (prefs.getString(prefKey, null) == root.uri.toString()) {
            prefs.edit().remove(prefKey).apply()
        }

        try {
            app.contentResolver.releasePersistableUriPermission(
                root.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Already released or never held — nothing to undo.
        }
        loadDirectory()
    }

    /**
     * Navigate into a subdirectory by name.
     */
    fun navigateInto(name: String) {
        fileNavStack.lastOrNull()?.let { dir ->
            val child = File(dir, name)
            if (child.isDirectory) {
                fileNavStack.add(child)
                listCurrentFileDirectory()
            }
            return
        }
        val current = navStack.lastOrNull() ?: return
        val child = current.findFile(name)
        if (child != null && child.isDirectory) {
            navStack.add(child)
            listCurrentDirectory()
        }
    }

    /**
     * Navigate up one level. From a root's top level this returns to the
     * root list. Returns false only when already at the root list.
     */
    fun navigateUp(): Boolean {
        if (fileNavStack.isNotEmpty()) {
            if (fileNavStack.size == 1) {
                loadDirectory()
                return true
            }
            fileNavStack.removeAt(fileNavStack.lastIndex)
            listCurrentFileDirectory()
            return true
        }
        if (navStack.isEmpty()) return false
        if (navStack.size == 1) {
            loadDirectory()
            return true
        }
        navStack.removeAt(navStack.lastIndex)
        listCurrentDirectory()
        return true
    }

    private fun prefs() =
        getApplication<Application>().getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /**
     * Reads the granted roots, migrating the legacy single
     * `download_folder_uri` setting in as the first root so existing
     * installs keep the folder they already picked.
     */
    private fun readRoots(): List<LocalRoot> {
        val app = getApplication<Application>()
        val prefs = prefs()
        val stored = prefs.getStringSet(rootsKey, emptySet())?.toMutableSet() ?: mutableSetOf()

        val legacy = prefs.getString(prefKey, null)
        if (!legacy.isNullOrBlank() && stored.add(legacy)) {
            prefs.edit().putStringSet(rootsKey, stored).apply()
        }

        return stored.mapNotNull { s ->
            val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return@mapNotNull null
            val doc = DocumentFile.fromTreeUri(app, uri)
            if (doc == null || !doc.exists()) return@mapNotNull null
            LocalRoot(name = localRootDisplayName(doc.name, uri.lastPathSegment), uri = uri)
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Open a file using ACTION_VIEW with FLAG_GRANT_READ_URI_PERMISSION.
     */
    fun openFile(entry: LocalFileEntry) {
        val app = getApplication<Application>()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.uri, entry.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "No app found to open this file") }
        }
    }

    /**
     * Share a file using ACTION_SEND with FLAG_GRANT_READ_URI_PERMISSION.
     */
    fun shareFile(entry: LocalFileEntry) {
        val app = getApplication<Application>()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = entry.mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, entry.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            app.startActivity(Intent.createChooser(intent, "Share ${entry.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Failed to share file") }
        }
    }

    /**
     * Delete a file or directory via DocumentFile.
     */
    fun deleteFile(entry: LocalFileEntry) {
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    val doc = DocumentFile.fromSingleUri(getApplication(), entry.uri)
                    doc?.delete() ?: false
                }
                if (deleted) {
                    listCurrentDirectory()
                } else {
                    _uiState.update { it.copy(error = "Failed to delete ${entry.name}") }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Delete failed: ${e.message}")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Enumerate a folder for upload, preserving its structure. Lives here
     * rather than in FileBrowserViewModel because only this ViewModel
     * knows whether the browser is walking a SAF tree or raw files (under
     * all-files access) — and a FileProvider content URI cannot be walked
     * back into a directory listing.
     */
    suspend fun collectFolderForUpload(entry: LocalFileEntry): List<UploadItem> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<UploadItem>()

            fileNavStack.lastOrNull()?.let { parent ->
                val dir = File(parent, entry.name)
                if (!dir.isDirectory) return@withContext emptyList()

                fun walkFile(d: File, prefix: String) {
                    for (f in d.listFiles() ?: emptyArray()) {
                        val rel = "$prefix/${f.name}"
                        if (f.isDirectory) walkFile(f, rel) else out.add(UploadItem(fileProviderUri(f), rel))
                    }
                }
                walkFile(dir, entry.name)
                return@withContext out
            }

            val parentDoc = navStack.lastOrNull() ?: return@withContext emptyList()
            val dirDoc = parentDoc.findFile(entry.name)
            if (dirDoc == null || !dirDoc.isDirectory) return@withContext emptyList()

            fun walkDoc(d: DocumentFile, prefix: String) {
                for (child in d.listFiles()) {
                    val name = child.name ?: continue
                    val rel = "$prefix/$name"
                    if (child.isDirectory) walkDoc(child, rel) else out.add(UploadItem(child.uri, rel))
                }
            }
            walkDoc(dirDoc, entry.name)
            out
        }

    /**
     * List the contents of the directory at the top of the navigation stack.
     */
    private fun listCurrentDirectory() {
        val currentDoc = navStack.lastOrNull() ?: return

        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                folderConfigured = true,
                atRootList = false,
            )
        }

        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val files = currentDoc.listFiles()
                    files.mapNotNull { doc ->
                        val name = doc.name ?: return@mapNotNull null
                        LocalFileEntry(
                            name = name,
                            size = doc.length(),
                            lastModified = doc.lastModified(),
                            isDirectory = doc.isDirectory,
                            mimeType = doc.type,
                            uri = doc.uri,
                        )
                    }.sortedWith(
                        compareByDescending<LocalFileEntry> { it.isDirectory }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    )
                }

                _uiState.update {
                    it.copy(
                        currentPath = buildDisplayPath(),
                        entries = entries,
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = e.message ?: "Failed to list files",
                    )
                }
            }
        }
    }

    /**
     * Build a relative display path from the navigation stack.
     * Root shows as "", one level deep shows as "SubFolder", etc.
     */
    private fun buildDisplayPath(): String {
        if (navStack.size <= 1) return ""
        return navStack.drop(1).mapNotNull { it.name }.joinToString("/")
    }
}
