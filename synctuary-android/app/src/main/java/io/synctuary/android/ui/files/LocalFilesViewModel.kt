package io.synctuary.android.ui.files

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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

data class LocalFileEntry(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val mimeType: String?,
    val uri: Uri,
)

data class LocalFilesUiState(
    val currentPath: String = "",
    val entries: List<LocalFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val folderConfigured: Boolean = false,
)

class LocalFilesViewModel @JvmOverloads constructor(
    application: Application,
    private val prefsName: String = "synctuary-settings",
    private val prefKey: String = "download_folder_uri",
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LocalFilesUiState())
    val uiState: StateFlow<LocalFilesUiState> = _uiState.asStateFlow()

    // Navigation stack: each entry is a DocumentFile representing a directory.
    // Index 0 is the root (download folder), last element is the current dir.
    private val navStack = mutableListOf<DocumentFile>()

    init {
        loadDirectory()
    }

    /**
     * Reads the configured download folder URI and lists its contents.
     * If no URI is stored, sets folderConfigured = false.
     */
    fun loadDirectory() {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(prefKey, null)

        if (uriStr.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    folderConfigured = false,
                    loading = false,
                    entries = emptyList(),
                    error = null,
                )
            }
            return
        }

        val treeUri = Uri.parse(uriStr)
        val rootDoc = DocumentFile.fromTreeUri(app, treeUri)
        if (rootDoc == null || !rootDoc.exists()) {
            _uiState.update {
                it.copy(
                    folderConfigured = true,
                    loading = false,
                    error = "Download folder is not accessible",
                    entries = emptyList(),
                )
            }
            return
        }

        // Reset navigation stack to root
        navStack.clear()
        navStack.add(rootDoc)

        listCurrentDirectory()
    }

    /**
     * Navigate into a subdirectory by name.
     */
    fun navigateInto(name: String) {
        val current = navStack.lastOrNull() ?: return
        val child = current.findFile(name)
        if (child != null && child.isDirectory) {
            navStack.add(child)
            listCurrentDirectory()
        }
    }

    /**
     * Navigate up one level. Returns false if already at root.
     */
    fun navigateUp(): Boolean {
        if (navStack.size <= 1) return false
        navStack.removeAt(navStack.lastIndex)
        listCurrentDirectory()
        return true
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
     * List the contents of the directory at the top of the navigation stack.
     */
    private fun listCurrentDirectory() {
        val currentDoc = navStack.lastOrNull() ?: return

        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                folderConfigured = true,
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
