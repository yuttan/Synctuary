package io.synctuary.android.ui.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.synctuary.android.data.FavoritesRepository
import io.synctuary.android.data.api.dto.FavoriteListDetailDto
import io.synctuary.android.data.api.dto.FavoriteListDto
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FavoritesViewModel @JvmOverloads constructor(
    application: Application,
    private val repo: FavoritesRepository = FavoritesRepository(SecretStore.create(application)),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    private var autoLockJob: Job? = null

    init {
        loadLists()
    }

    fun loadLists() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val includeHidden = _uiState.value.hiddenUnlocked
                val lists = repo.listAll(includeHidden)
                _uiState.update {
                    it.copy(lists = lists, loading = false, error = null)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: "Failed to load favorites")
                }
            }
        }
    }

    fun onHiddenUnlocked() {
        _uiState.update { it.copy(hiddenUnlocked = true) }
        loadLists()
        startAutoLockTimer()
    }

    fun lockHidden() {
        autoLockJob?.cancel()
        _uiState.update { it.copy(hiddenUnlocked = false) }
        loadLists()
    }

    fun onAppBackgrounded() {
        if (_uiState.value.hiddenUnlocked) {
            lockHidden()
        }
    }

    private fun startAutoLockTimer() {
        autoLockJob?.cancel()
        autoLockJob = viewModelScope.launch {
            delay(HIDDEN_UNLOCK_DURATION_MS)
            lockHidden()
        }
    }

    fun createList(name: String, hidden: Boolean = false) {
        viewModelScope.launch {
            try {
                repo.createList(name, hidden)
                loadLists()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Create failed: ${e.message}") }
            }
        }
    }

    fun deleteList(list: FavoriteListDto) {
        viewModelScope.launch {
            try {
                repo.deleteList(list.id)
                loadLists()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Delete failed: ${e.message}") }
            }
        }
    }

    fun renameList(list: FavoriteListDto, newName: String) {
        viewModelScope.launch {
            try {
                repo.updateList(list.id, name = newName)
                loadLists()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Rename failed: ${e.message}") }
            }
        }
    }

    fun toggleHidden(list: FavoriteListDto) {
        viewModelScope.launch {
            try {
                repo.updateList(list.id, hidden = !list.hidden)
                loadLists()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Update failed: ${e.message}") }
            }
        }
    }

    fun loadListDetail(id: String) {
        _uiState.update { it.copy(selectedListLoading = true) }
        viewModelScope.launch {
            try {
                val detail = repo.getList(id)
                _uiState.update {
                    it.copy(selectedList = detail, selectedListLoading = false)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(selectedListLoading = false, error = e.message)
                }
            }
        }
    }

    fun clearSelectedList() {
        _uiState.update { it.copy(selectedList = null) }
    }

    fun addItemToList(listId: String, path: String) {
        viewModelScope.launch {
            try {
                repo.addItem(listId, path)
                loadLists()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Add failed: ${e.message}") }
            }
        }
    }

    fun removeItemFromList(listId: String, path: String) {
        viewModelScope.launch {
            try {
                repo.removeItem(listId, path)
                loadListDetail(listId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Remove failed: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        const val HIDDEN_UNLOCK_DURATION_MS = 5L * 60 * 1000
    }
}

data class FavoritesUiState(
    val lists: List<FavoriteListDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hiddenUnlocked: Boolean = false,
    val selectedList: FavoriteListDetailDto? = null,
    val selectedListLoading: Boolean = false,
)
