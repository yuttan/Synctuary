package io.synctuary.android.ui.archive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.synctuary.android.data.FileRepository
import io.synctuary.android.data.api.dto.ArchiveEntryDto
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArchiveBrowserUiState(
    val loading: Boolean = true,
    val error: String? = null,
    // Current directory WITHIN the archive ("" = archive root).
    val currentDir: String = "",
    val items: List<ArchiveItem> = emptyList(),
)

/**
 * Browses one archive's contents. Fetches the flat entry list once
 * (§6.9), then reconstructs a directory tree client-side via
 * [ArchiveTreeBuilder]; folder navigation is pure in-memory retraversal.
 */
class ArchiveBrowserViewModel @JvmOverloads constructor(
    application: Application,
    private val repo: FileRepository = FileRepository(SecretStore.create(application)),
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ArchiveBrowserUiState())
    val state: StateFlow<ArchiveBrowserUiState> = _state.asStateFlow()

    private var entries: List<ArchiveEntryDto> = emptyList()
    private var archivePath: String = ""
    private var shareId: String? = null
    private var started = false

    /** Idempotent: the first call loads the archive; later calls no-op. */
    fun start(archivePath: String, shareId: String?) {
        if (started) return
        started = true
        this.archivePath = archivePath
        this.shareId = shareId
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                entries = repo.listArchive(archivePath, shareId)
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        currentDir = "",
                        items = ArchiveTreeBuilder.childrenOf(entries, ""),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load archive") }
            }
        }
    }

    fun navigateInto(dir: ArchiveItem) = navigateToDir(dir.path)

    private fun navigateToDir(path: String) {
        _state.update {
            it.copy(currentDir = path, items = ArchiveTreeBuilder.childrenOf(entries, path))
        }
    }

    /** Navigate to a breadcrumb-selected directory ("" = archive root). */
    fun navigateToBreadcrumb(path: String) = navigateToDir(path)

    /** Returns true if it navigated up a level, false if at the archive root. */
    fun navigateUp(): Boolean {
        val cur = _state.value.currentDir
        if (cur.isEmpty()) return false
        val parent = if ('/' in cur) cur.substringBeforeLast('/') else ""
        navigateToDir(parent)
        return true
    }

    /** All image entries of the archive, natural-sorted — the pager list. */
    fun imageEntryPaths(): List<String> = ArchiveTreeBuilder.imageEntryPaths(entries)
}
