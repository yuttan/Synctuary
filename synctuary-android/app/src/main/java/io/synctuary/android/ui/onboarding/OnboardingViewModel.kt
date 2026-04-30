package io.synctuary.android.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.synctuary.android.data.PairedDeviceSummary
import io.synctuary.android.data.PairingRepository
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = SecretStore.create(application)
    private val repo = PairingRepository(secretStore)

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun isPaired(): Boolean = secretStore.isPaired()

    // ── Screen 1: Server URL ────────────────────────────────────────

    fun setServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url, serverUrlError = null) }
    }

    fun validateAndProceed(): Boolean {
        val url = _uiState.value.serverUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(serverUrlError = "URL is required") }
            return false
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            _uiState.update { it.copy(serverUrlError = "URL must start with https:// or http://") }
            return false
        }
        _uiState.update { it.copy(serverUrl = url, serverUrlError = null) }
        return true
    }

    // ── Screen 2: Mnemonic ──────────────────────────────────────────

    fun setWord(index: Int, word: String) {
        if (index !in 0..23) return
        val words = _uiState.value.words.toMutableList()
        words[index] = word.lowercase().trim()
        _uiState.update { it.copy(words = words, mnemonicError = null) }
    }

    fun pasteMnemonic(text: String) {
        val parsed = text.trim().lowercase().split("\\s+".toRegex())
        val words = MutableList(24) { "" }
        for (i in 0 until minOf(parsed.size, 24)) {
            words[i] = parsed[i]
        }
        _uiState.update { it.copy(words = words, mnemonicError = null) }
    }

    fun isMnemonicComplete(): Boolean =
        _uiState.value.words.all { it.isNotBlank() }

    // ── Screen 3: Pairing ───────────────────────────────────────────

    fun startPairing() {
        val state = _uiState.value
        val mnemonic = state.words.joinToString(" ")

        _uiState.update {
            it.copy(
                pairingSteps = initialSteps(),
                pairingError = null,
                pairingDone = false,
            )
        }

        viewModelScope.launch {
            advanceStep(0, StepStatus.DONE)
            advanceStep(1, StepStatus.DONE)
            advanceStep(2, StepStatus.ACTIVE)

            try {
                val summary = repo.pair(state.serverUrl, mnemonic)
                advanceStep(2, StepStatus.DONE)
                advanceStep(3, StepStatus.DONE)
                advanceStep(4, StepStatus.DONE)
                _uiState.update {
                    it.copy(pairingDone = true, pairingSummary = summary)
                }
            } catch (e: Exception) {
                val activeIdx = _uiState.value.pairingSteps
                    .indexOfFirst { s -> s.status == StepStatus.ACTIVE }
                    .takeIf { i -> i >= 0 } ?: 2
                advanceStep(activeIdx, StepStatus.ERROR)
                _uiState.update {
                    it.copy(pairingError = e.message ?: "Unknown error")
                }
            }
        }
    }

    fun retryPairing() {
        startPairing()
    }

    private fun advanceStep(index: Int, status: StepStatus) {
        _uiState.update { state ->
            val steps = state.pairingSteps.toMutableList()
            if (index in steps.indices) {
                steps[index] = steps[index].copy(status = status)
            }
            state.copy(pairingSteps = steps)
        }
    }

    private fun initialSteps(): List<PairingStep> = listOf(
        PairingStep("master_key derivation (BIP-39 + HKDF)", StepStatus.ACTIVE),
        PairingStep("Device keypair generation (Ed25519)", StepStatus.PENDING),
        PairingStep("Fetch nonce from server", StepStatus.PENDING),
        PairingStep("Sign and send challenge", StepStatus.PENDING),
        PairingStep("Receive and save device_token", StepStatus.PENDING),
    )
}

data class OnboardingUiState(
    val serverUrl: String = "https://",
    val serverUrlError: String? = null,
    val words: List<String> = List(24) { "" },
    val mnemonicError: String? = null,
    val pairingSteps: List<PairingStep> = emptyList(),
    val pairingError: String? = null,
    val pairingDone: Boolean = false,
    val pairingSummary: PairedDeviceSummary? = null,
)

enum class StepStatus { PENDING, ACTIVE, DONE, ERROR }

data class PairingStep(
    val label: String,
    val status: StepStatus,
)
