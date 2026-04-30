package io.synctuary.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.synctuary.android.ui.theme.SynctuarySuccess

@Composable
fun PairingProgressScreen(
    viewModel: OnboardingViewModel,
    onPairingComplete: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startPairing()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            if (!state.pairingDone && state.pairingError == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 5.dp,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Pairing...",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else if (state.pairingDone) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = SynctuarySuccess,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Paired!",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SynctuarySuccess,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Pairing failed",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = state.serverUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(32.dp))

            // Step list
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                state.pairingSteps.forEach { step ->
                    StepRow(step)
                }
            }

            // Error message
            state.pairingError?.let { err ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.retryPairing() }) {
                    Text("Retry")
                }
            }

            // Success summary + continue
            if (state.pairingDone) {
                state.pairingSummary?.let { summary ->
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "Connected to ${summary.serverName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onPairingComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Continue")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepRow(step: PairingStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Step icon
        when (step.status) {
            StepStatus.DONE -> {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = SynctuarySuccess,
                    modifier = Modifier.size(24.dp),
                )
            }
            StepStatus.ACTIVE -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )
            }
            StepStatus.PENDING -> {
                Spacer(Modifier.size(24.dp))
            }
            StepStatus.ERROR -> {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Text(
            text = step.label,
            style = MaterialTheme.typography.bodyMedium,
            color = when (step.status) {
                StepStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                StepStatus.ACTIVE -> MaterialTheme.colorScheme.onSurface
                StepStatus.PENDING -> MaterialTheme.colorScheme.outline
                StepStatus.ERROR -> MaterialTheme.colorScheme.error
            },
        )
    }
}
