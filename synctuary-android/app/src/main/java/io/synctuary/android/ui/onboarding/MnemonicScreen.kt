package io.synctuary.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicScreen(
    viewModel: OnboardingViewModel,
    onBack: () -> Unit,
    onStartPairing: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current
    var editingIndex by remember { mutableIntStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val filledCount = state.words.count { it.isNotBlank() }
    val allFilled = filledCount == 24

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seed Phrase") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Enter the 24 words shown during server setup. BIP-39 format.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            // 3-column x 8-row mnemonic grid
            for (row in 0 until 8) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (col in 0 until 3) {
                        val idx = row * 3 + col
                        MnemonicCell(
                            index = idx,
                            word = state.words[idx],
                            isEditing = idx == editingIndex,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                editingIndex = idx
                                inputText = state.words[idx]
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Word input field
            OutlinedTextField(
                value = inputText,
                onValueChange = { raw ->
                    val cleaned = raw.lowercase().trim()
                    if (cleaned.contains(' ')) {
                        // Space typed — commit current word, advance
                        val word = cleaned.replace(" ", "")
                        if (word.isNotEmpty()) {
                            viewModel.setWord(editingIndex, word)
                            if (editingIndex < 23) {
                                editingIndex++
                            }
                        }
                        inputText = ""
                    } else {
                        inputText = cleaned
                        viewModel.setWord(editingIndex, cleaned)
                    }
                },
                label = { Text("Word ${editingIndex + 1}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = {
                        if (inputText.isNotBlank()) {
                            viewModel.setWord(editingIndex, inputText)
                        }
                        if (editingIndex < 23) {
                            editingIndex++
                            inputText = state.words[editingIndex + 1].let {
                                if (editingIndex + 1 <= 23) state.words[editingIndex] else ""
                            }
                        }
                        inputText = ""
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )

            Spacer(Modifier.height(8.dp))

            // Paste from clipboard
            TextButton(
                onClick = {
                    clipboard.getText()?.text?.let { text ->
                        viewModel.pasteMnemonic(text)
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(
                    Icons.Filled.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Paste from clipboard")
            }

            state.mnemonicError?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onStartPairing,
                enabled = allFilled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Start Pairing")
            }

            if (!allFilled) {
                Text(
                    text = "$filledCount / 24 words entered",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MnemonicCell(
    index: Int,
    word: String,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val borderColor = if (isEditing) primary else outline

    Box(
        modifier = modifier
            .height(40.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(20.dp),
            )
            Text(
                text = word.ifBlank { "..." },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = if (word.isBlank()) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}
