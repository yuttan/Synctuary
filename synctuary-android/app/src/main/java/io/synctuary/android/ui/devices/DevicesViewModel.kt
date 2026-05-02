package io.synctuary.android.ui.devices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.synctuary.android.crypto.B64Url
import io.synctuary.android.data.DevicesRepository
import io.synctuary.android.data.api.dto.DeviceDto
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DevicesViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = SecretStore.create(application)
    private val repo = DevicesRepository(secretStore)

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        loadDevices()
    }

    fun loadDevices() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val devices = repo.listAll()
                val selfId = secretStore.loadPairedDevice()?.let { B64Url.encode(it.deviceId) }
                _uiState.update {
                    it.copy(
                        devices = devices,
                        selfDeviceId = selfId,
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = e.message ?: "Failed to load devices")
                }
            }
        }
    }

    fun revokeDevice(device: DeviceDto) {
        viewModelScope.launch {
            try {
                repo.revoke(device.device_id)
                loadDevices()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Revoke failed: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class DevicesUiState(
    val devices: List<DeviceDto> = emptyList(),
    val selfDeviceId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)
