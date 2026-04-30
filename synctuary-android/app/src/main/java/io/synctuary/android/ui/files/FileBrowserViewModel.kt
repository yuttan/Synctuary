package io.synctuary.android.ui.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.synctuary.android.data.FileRepository
import io.synctuary.android.data.api.dto.FileEntry
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FileBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = SecretStore.create(application)
    private val repo = FileRepository(secretStore)

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    init {
        loadDirectory("/")
    }

    fun loadDirectory(path: String) {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = repo.listFiles(path)
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

    fun navigateInto(dirName: String) {
        val current = _uiState.value.currentPath
        val next = if (current == "/") "/$dirName" else "$current/$dirName"
        loadDirectory(next)
    }

    fun navigateUp(): Boolean {
        val current = _uiState.value.currentPath
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
                repo.deleteFile(path, recursive)
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
                repo.moveFile(from, to)
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

    private fun buildEntryPath(name: String): String {
        val current = _uiState.value.currentPath
        return if (current == "/") "/$name" else "$current/$name"
    }
}

data class FileBrowserUiState(
    val currentPath: String = "/",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val selectedEntry: FileEntry? = null,
)
