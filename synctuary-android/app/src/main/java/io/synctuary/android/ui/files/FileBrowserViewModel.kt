package io.synctuary.android.ui.files

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.synctuary.android.data.FileRepository
import io.synctuary.android.data.TransferState
import io.synctuary.android.data.api.dto.FileEntry
import io.synctuary.android.data.api.dto.ShareEntry
import io.synctuary.android.data.secret.SecretStore
import io.synctuary.android.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortOption { NAME, DATE, SIZE }

/** Comparator for natural sort: "file1, file2, ..., file9, file10" instead of lexicographic. */
object NaturalOrderComparator : Comparator<String> {
    private val splitPattern = Regex("(\\d+)|(\\D+)")
    override fun compare(a: String, b: String): Int {
        val aParts = splitPattern.findAll(a.lowercase()).toList()
        val bParts = splitPattern.findAll(b.lowercase()).toList()
        for (i in 0 until minOf(aParts.size, bParts.size)) {
            val av = aParts[i].value
            val bv = bParts[i].value
            val an = av.toLongOrNull()
            val bn = bv.toLongOrNull()
            val cmp = if (an != null && bn != null) an.compareTo(bn) else av.compareTo(bv)
            if (cmp != 0) return cmp
        }
        return aParts.size.compareTo(bParts.size)
    }
}

class FileBrowserViewModel @JvmOverloads constructor(
    application: Application,
    private val repo: FileRepository = FileRepository(SecretStore.create(application)),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val _shares = MutableStateFlow<List<ShareEntry>>(emptyList())
    val shares: StateFlow<List<ShareEntry>> = _shares.asStateFlow()

    private val _currentShare = MutableStateFlow<ShareEntry?>(null)
    val currentShare: StateFlow<ShareEntry?> = _currentShare.asStateFlow()

    private val hasMultipleShares: Boolean
        get() = _shares.value.size > 1

    /** True when the user is at the virtual shares-root (drive picker). */
    val isAtSharesRoot: Boolean
        get() = hasMultipleShares && _currentShare.value == null

    init {
        loadSharesThenRoot()
    }

    private fun loadSharesThenRoot() {
        viewModelScope.launch {
            try {
                val list = repo.listShares()
                _shares.value = list
                if (list.size <= 1) {
                    _currentShare.value = list.firstOrNull()
                    loadDirectory("/")
                } else {
                    _currentShare.value = null
                    showSharesRoot()
                }
            } catch (_: Exception) {
                loadDirectory("/")
            }
        }
    }

    fun loadShares() {
        viewModelScope.launch {
            try {
                val list = repo.listShares()
                _shares.value = list
            } catch (_: Exception) { }
        }
    }

    fun resetConnection() {
        repo.resetApiCache()
        _currentShare.value = null
        loadSharesThenRoot()
    }

    fun selectShare(share: ShareEntry) {
        _currentShare.value = share
        loadDirectory("/")
    }

    private fun showSharesRoot() {
        val shares = _shares.value
        val virtualEntries = shares.map { s ->
            FileEntry(
                name = s.name,
                type = "share",
                size = null,
                modified_at = 0L,
                mime_type = s.host_path,
                sha256 = s.id,
            )
        }
        _uiState.update {
            it.copy(
                currentPath = "/",
                entries = virtualEntries,
                loading = false,
                error = null,
            )
        }
    }

    fun loadDirectory(path: String) {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val shareId = _currentShare.value?.id
                val entries = repo.listFiles(path, shareId)
                _uiState.update {
                    it.copy(
                        currentPath = path,
                        entries = entries,
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: "Failed to load")
                }
            }
        }
    }

    fun refresh() {
        if (isAtSharesRoot) {
            loadSharesThenRoot()
            return
        }
        val path = _uiState.value.currentPath
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val shareId = _currentShare.value?.id
                val entries = repo.listFiles(path, shareId)
                _uiState.update {
                    it.copy(
                        entries = entries,
                        refreshing = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(refreshing = false, error = e.message ?: "Failed to refresh")
                }
            }
        }
    }

    fun navigateInto(dirName: String) {
        val current = _uiState.value.currentPath
        val next = if (current == "/") "/$dirName" else "$current/$dirName"
        loadDirectory(next)
    }

    fun navigateUp(): Boolean {
        val current = _uiState.value.currentPath
        if (current == "/" && hasMultipleShares) {
            _currentShare.value = null
            showSharesRoot()
            return true
        }
        if (current == "/") return false
        val parent = current.substringBeforeLast('/').ifEmpty { "/" }
        loadDirectory(parent)
        return true
    }

    fun navigateToBreadcrumb(path: String) {
        loadDirectory(path)
    }

    fun deleteFile(entry: FileEntry) {
        val path = buildEntryPath(entry.name)
        val recursive = entry.type == "dir"
        viewModelScope.launch {
            try {
                repo.deleteFile(path, recursive, shareId = _currentShare.value?.id)
                loadDirectory(_uiState.value.currentPath)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Delete failed: ${e.message}") }
            }
        }
    }

    fun renameFile(entry: FileEntry, newName: String) {
        val from = buildEntryPath(entry.name)
        val to = buildEntryPath(newName)
        viewModelScope.launch {
            try {
                repo.moveFile(from, to, shareId = _currentShare.value?.id)
                loadDirectory(_uiState.value.currentPath)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Rename failed: ${e.message}") }
            }
        }
    }

    fun selectForAction(entry: FileEntry?) {
        _uiState.update { it.copy(selectedEntry = entry) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun toggleSearch() {
        _uiState.update {
            if (it.searchActive) it.copy(searchActive = false, searchQuery = "")
            else it.copy(searchActive = true)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSortOption(option: SortOption) {
        _uiState.update {
            if (it.sortBy == option) it.copy(sortAscending = !it.sortAscending)
            else it.copy(sortBy = option, sortAscending = true)
        }
    }

    fun moveFile(entry: FileEntry, destinationDir: String) {
        val from = buildEntryPath(entry.name)
        val to = if (destinationDir == "/") "/${entry.name}" else "$destinationDir/${entry.name}"
        viewModelScope.launch {
            try {
                repo.moveFile(from, to, shareId = _currentShare.value?.id)
                loadDirectory(_uiState.value.currentPath)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Move failed: ${e.message}") }
            }
        }
    }

    suspend fun listDirectory(path: String): List<FileEntry> {
        return repo.listFiles(path, _currentShare.value?.id)
    }

    /** Download to the user-configured SAF folder, falling back to
     *  app-private storage when no folder is set. */
    fun startDownload(entry: FileEntry) {
        val app = getApplication<Application>()
        val name = entry.name
        val remotePath = buildEntryPath(name)
        val prefs = app.getSharedPreferences("synctuary-settings", Context.MODE_PRIVATE)
        val folderUri = prefs.getString(SettingsViewModel.K_DOWNLOAD_FOLDER, null)

        val t0 = System.currentTimeMillis()
        _uiState.update { it.copy(downloadState = TransferState.Running(name, 0L, entry.size, startTimeMs = t0)) }
        viewModelScope.launch {
            try {
                val destLabel: String
                if (folderUri != null) {
                    // SAF tree URI — create a file inside the chosen folder.
                    val treeDoc = DocumentFile.fromTreeUri(app, Uri.parse(folderUri))
                    val mime = entry.mime_type ?: "application/octet-stream"
                    // Deduplicate: if "photo.jpg" exists, try "photo (1).jpg", etc.
                    val safeName = deduplicateSafName(treeDoc, name)
                    val destDoc = treeDoc?.createFile(mime, safeName)
                        ?: throw IllegalStateException("Cannot create file in download folder")
                    repo.downloadFileToUri(
                        remotePath, app.contentResolver, destDoc.uri,
                        onProgress = { received, total ->
                            _uiState.update { s ->
                                val prev = s.downloadState as? TransferState.Running
                                s.copy(downloadState = TransferState.Running(
                                    name, received, total,
                                    startTimeMs = prev?.startTimeMs ?: t0,
                                    startBytes = prev?.startBytes ?: 0L,
                                ))
                            }
                        },
                        shareId = _currentShare.value?.id,
                    )
                    destLabel = destDoc.uri.toString()
                } else {
                    // Fallback: app-private external files dir.
                    val destDir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
                    val destFile = deduplicateLocalFile(destDir, name)
                    repo.downloadFile(
                        remotePath, destFile,
                        onProgress = { received, total ->
                            _uiState.update { s ->
                                val prev = s.downloadState as? TransferState.Running
                                s.copy(downloadState = TransferState.Running(
                                    name, received, total,
                                    startTimeMs = prev?.startTimeMs ?: t0,
                                    startBytes = prev?.startBytes ?: 0L,
                                ))
                            }
                        },
                        shareId = _currentShare.value?.id,
                    )
                    destLabel = destFile.absolutePath
                }
                _uiState.update {
                    it.copy(downloadState = TransferState.Done(name, destLabel))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(downloadState = TransferState.Failed(name, e.message ?: "Download failed"))
                }
            }
        }
    }

    /** "Save As..." — download to a user-picked SAF URI (from CreateDocument). */
    fun saveAsDownload(entry: FileEntry, destUri: Uri) {
        val app = getApplication<Application>()
        val name = entry.name
        val remotePath = buildEntryPath(name)

        val t0 = System.currentTimeMillis()
        _uiState.update { it.copy(downloadState = TransferState.Running(name, 0L, entry.size, startTimeMs = t0)) }
        viewModelScope.launch {
            try {
                repo.downloadFileToUri(
                    remotePath, app.contentResolver, destUri,
                    onProgress = { received, total ->
                        _uiState.update { s ->
                            val prev = s.downloadState as? TransferState.Running
                            s.copy(downloadState = TransferState.Running(
                                name, received, total,
                                startTimeMs = prev?.startTimeMs ?: t0,
                                startBytes = prev?.startBytes ?: 0L,
                            ))
                        }
                    },
                    shareId = _currentShare.value?.id,
                )
                _uiState.update {
                    it.copy(downloadState = TransferState.Done(name, destUri.toString()))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(downloadState = TransferState.Failed(name, e.message ?: "Save As failed"))
                }
            }
        }
    }

    fun startUpload(uri: Uri) {
        if (_uiState.value.uploadState is TransferState.Running) return

        val app = getApplication<Application>()
        val fileName = resolveDisplayName(uri)
        val remotePath = buildRemoteUploadPath(fileName)

        val t0 = System.currentTimeMillis()
        _uiState.update { it.copy(uploadState = TransferState.Running(fileName, 0L, null, startTimeMs = t0)) }
        viewModelScope.launch {
            try {
                repo.uploadFile(
                    app.contentResolver, uri, remotePath,
                    onProgress = { uploaded, total ->
                        _uiState.update { s ->
                            val prev = s.uploadState as? TransferState.Running
                            s.copy(uploadState = TransferState.Running(
                                fileName, uploaded, total,
                                startTimeMs = prev?.startTimeMs ?: t0,
                                startBytes = prev?.startBytes ?: 0L,
                            ))
                        }
                    },
                    shareId = _currentShare.value?.id,
                )
                _uiState.update {
                    it.copy(uploadState = TransferState.Done(fileName, remotePath))
                }
                loadDirectory(_uiState.value.currentPath)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(uploadState = TransferState.Failed(fileName, e.message ?: "Upload failed"))
                }
            }
        }
    }

    fun dismissTransferFeedback() {
        _uiState.update {
            it.copy(downloadState = TransferState.Idle, uploadState = TransferState.Idle)
        }
    }

    private fun buildEntryPath(name: String): String {
        val current = _uiState.value.currentPath
        return if (current == "/") "/$name" else "$current/$name"
    }

    private fun buildRemoteUploadPath(fileName: String): String {
        val current = _uiState.value.currentPath
        return if (current == "/") "/$fileName" else "$current/$fileName"
    }

    private fun resolveDisplayName(uri: Uri): String {
        val cr = getApplication<Application>().contentResolver
        return cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "file"
    }

    /**
     * Find a non-conflicting name inside a SAF directory.
     * "photo.jpg" -> "photo (1).jpg" -> "photo (2).jpg" etc.
     */
    private fun deduplicateSafName(parent: DocumentFile?, name: String): String {
        if (parent == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var i = 1
        while (parent.findFile(candidate) != null) {
            candidate = "$base ($i)$ext"
            i++
        }
        return candidate
    }

    /**
     * Find a non-conflicting name inside a local directory.
     * "photo.jpg" -> "photo (1).jpg" -> "photo (2).jpg" etc.
     */
    private fun deduplicateLocalFile(dir: java.io.File, name: String): java.io.File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = java.io.File(dir, name)
        var i = 1
        while (candidate.exists()) {
            candidate = java.io.File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }
}

data class FileBrowserUiState(
    val currentPath: String = "/",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selectedEntry: FileEntry? = null,
    val downloadState: TransferState = TransferState.Idle,
    val uploadState: TransferState = TransferState.Idle,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val sortBy: SortOption = SortOption.NAME,
    val sortAscending: Boolean = true,
    val refreshing: Boolean = false,
) {
    val filteredEntries: List<FileEntry>
        get() {
            val base = if (searchQuery.isBlank()) entries
                else entries.filter { it.name.contains(searchQuery, ignoreCase = true) }
            val dirs = base.filter { it.type == "dir" }
            val files = base.filter { it.type != "dir" }
            val comparator: Comparator<FileEntry> = when (sortBy) {
                SortOption.NAME -> compareBy(NaturalOrderComparator) { it.name }
                SortOption.DATE -> compareBy { it.modified_at }
                SortOption.SIZE -> compareBy { it.size ?: 0L }
            }
            val sorted = if (sortAscending) comparator else comparator.reversed()
            return dirs.sortedWith(sorted) + files.sortedWith(sorted)
        }
}
