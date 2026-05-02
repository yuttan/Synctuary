package io.synctuary.android.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.synctuary.android.crypto.B64Url
import io.synctuary.android.data.DevicesRepository
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = SecretStore.create(application)
    private val repo = DevicesRepository(secretStore)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun buildState(): SettingsUiState {
        val paired = secretStore.loadPairedDevice()
        return SettingsUiState(
            serverUrl = paired?.serverUrl ?: "",
            serverId = paired?.serverId?.let { B64Url.encode(it) } ?: "",
            tlsFingerprint = paired?.serverFingerprint?.let { formatFingerprint(it) } ?: "",
            deviceName = "",
            deviceId = paired?.deviceId?.let { B64Url.encode(it) } ?: "",
            platform = "Android ${android.os.Build.VERSION.RELEASE}",
            leftHandMode = prefs.getBoolean(K_LEFT_HAND, false),
            biometricProtection = prefs.getBoolean(K_BIO_PROTECT, true),
        )
    }

    fun loadDeviceInfo() {
        viewModelScope.launch {
            try {
                val devices = repo.listAll()
                val self = devices.find { it.current }
                if (self != null) {
                    _uiState.update {
                        it.copy(
                            deviceName = self.device_name,
                            pairedAt = self.created_at,
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun loadServerInfo() {
        viewModelScope.launch {
            try {
                val paired = secretStore.loadPairedDevice() ?: return@launch
                val api = io.synctuary.android.data.api.NetworkModule.create(
                    baseUrl = paired.serverUrl,
                    fingerprint = paired.serverFingerprint,
                    authInterceptor = io.synctuary.android.data.api.AuthInterceptor(secretStore),
                )
                val info = api.info()
                _uiState.update {
                    it.copy(protocolVersion = info.protocol_version)
                }
            } catch (_: Exception) { }
        }
    }

    fun setLeftHandMode(enabled: Boolean) {
        prefs.edit().putBoolean(K_LEFT_HAND, enabled).apply()
        _uiState.update { it.copy(leftHandMode = enabled) }
    }

    fun setBiometricProtection(enabled: Boolean) {
        prefs.edit().putBoolean(K_BIO_PROTECT, enabled).apply()
        _uiState.update { it.copy(biometricProtection = enabled) }
    }

    fun unpair(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val paired = secretStore.loadPairedDevice()
                if (paired != null) {
                    repo.revoke(B64Url.encode(paired.deviceId))
                }
            } catch (_: Exception) { }
            secretStore.wipe()
            onComplete()
        }
    }

    companion object {
        private const val PREFS_NAME = "synctuary-settings"
        private const val K_LEFT_HAND = "left_hand_mode"
        private const val K_BIO_PROTECT = "biometric_protection"
    }
}

private fun formatFingerprint(bytes: ByteArray): String {
    val hex = bytes.joinToString(":") { "%02x".format(it) }
    return "SHA256:${hex.take(23)}…"
}

data class SettingsUiState(
    val serverUrl: String = "",
    val serverId: String = "",
    val tlsFingerprint: String = "",
    val protocolVersion: String = "",
    val deviceName: String = "",
    val deviceId: String = "",
    val platform: String = "",
    val pairedAt: Long = 0L,
    val leftHandMode: Boolean = false,
    val biometricProtection: Boolean = true,
    val error: String? = null,
)
