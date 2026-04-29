package io.synctuary.android.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.synctuary.android.data.PairingRepository
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.launch

/**
 * Debug-only screen for exercising the Phase-2 pairing pipeline
 * end-to-end without the polished onboarding UI. Two text inputs
 * (server URL + 24-word mnemonic) + a single button — runs
 * [PairingRepository.pair] and dumps the result.
 *
 * Phase 2.2 will replace this with the multi-step Compose flow that
 * matches mockup screens 1-3. Keep this around for debugging /
 * regression checks.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PairingTestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val secretStore = remember { SecretStore.create(context) }
    val repo = remember { PairingRepository(secretStore) }

    var url by remember { mutableStateOf("https://192.168.1.10:8443") }
    var mnemonic by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String>("Ready.") }
    var loading by remember { mutableStateOf(false) }
    var alreadyPaired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        alreadyPaired = secretStore.isPaired()
        if (alreadyPaired) {
            secretStore.loadPairedDevice()?.let { p ->
                status = "Already paired with ${p.serverUrl} (deviceId=${p.deviceId.size}B)"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair (debug)") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        ContentColumn(padding = padding) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = mnemonic,
                onValueChange = { mnemonic = it.lowercase() },
                label = { Text("24-word mnemonic") },
                minLines = 4,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (loading) return@Button
                    loading = true
                    status = "Pairing…"
                    scope.launch {
                        runCatching { repo.pair(url, mnemonic) }
                            .fold(
                                onSuccess = { summary ->
                                    status = buildString {
                                        appendLine("✓ Paired with ${summary.serverName}")
                                        appendLine("  URL          : ${summary.serverUrl}")
                                        appendLine("  device_name  : ${summary.deviceName}")
                                        appendLine("  token_ttl    : ${summary.tokenTtlSeconds}s")
                                        appendLine("  fingerprint? : ${summary.fingerprintPresent}")
                                    }
                                    alreadyPaired = true
                                },
                                onFailure = { t ->
                                    status = "✗ ${t::class.simpleName}: ${t.message}"
                                },
                            )
                        loading = false
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (alreadyPaired) "Re-pair" else "Pair")
            }

            if (alreadyPaired) {
                Button(
                    onClick = {
                        secretStore.wipe()
                        alreadyPaired = false
                        status = "Wiped. Ready to pair fresh."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Wipe paired device (debug)") }
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Reusable scrollable padded Column, named so the parent can stay
 *  legible. */
@Composable
private fun ContentColumn(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}
