package io.synctuary.android.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.synctuary.android.R
import io.synctuary.android.data.secret.SecretStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onUnpaired: () -> Unit,
    onScanQr: () -> Unit = {},
    scannedUrl: String? = null,
    onScannedUrlConsumed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    var showUnpairDialog by remember { mutableStateOf(false) }
    var editingHomeUrl by remember { mutableStateOf(false) }
    var editingRemoteIndex by remember { mutableStateOf(-1) }
    var homeUrlDraft by remember { mutableStateOf("") }
    var remoteUrlDraft by remember { mutableStateOf("") }
    var remoteLabelDraft by remember { mutableStateOf("") }
    var showAddRemote by remember { mutableStateOf(false) }
    var newRemoteUrl by remember { mutableStateOf("") }
    var newRemoteLabel by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(scannedUrl) {
        if (scannedUrl != null) {
            newRemoteUrl = scannedUrl
            showAddRemote = true
            onScannedUrlConsumed()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setDownloadFolder(uri.toString())
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadDeviceInfo()
        viewModel.loadServerInfo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            // Section: Language
            item { SectionHeader(stringResource(R.string.settings_language)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    LanguageSelector()
                }
            }

            // Section 1: Connection
            item { SectionHeader(stringResource(R.string.settings_connection)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    // Active mode selector
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        // Home button
                        val isHome = state.activeMode == SecretStore.MODE_HOME
                        Button(
                            onClick = { viewModel.setActiveMode(SecretStore.MODE_HOME) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHome) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isHome) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_home))
                        }
                        // Remote buttons
                        for (entry in state.remoteUrls) {
                            Spacer(Modifier.height(6.dp))
                            val isActive = state.activeMode == SecretStore.remoteMode(entry.index)
                            Button(
                                onClick = { viewModel.setActiveMode(SecretStore.remoteMode(entry.index)) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(entry.label ?: "Remote ${entry.index + 1}")
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Home URL row
                    EditableUrlRow(
                        icon = Icons.Filled.Home,
                        label = stringResource(R.string.settings_home_url),
                        value = state.serverUrl,
                        editing = editingHomeUrl,
                        draft = homeUrlDraft,
                        onEditStart = {
                            homeUrlDraft = state.serverUrl
                            editingHomeUrl = true
                        },
                        onDraftChange = { homeUrlDraft = it },
                        onSave = {
                            viewModel.updateHomeUrl(homeUrlDraft.trim())
                            editingHomeUrl = false
                        },
                        onCancel = { editingHomeUrl = false },
                    )

                    // Remote URL rows
                    for (entry in state.remoteUrls) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val isEditing = editingRemoteIndex == entry.index
                        EditableUrlRow(
                            icon = Icons.Filled.Public,
                            label = entry.label ?: "Remote ${entry.index + 1}",
                            value = entry.url,
                            editing = isEditing,
                            draft = remoteUrlDraft,
                            onEditStart = {
                                remoteUrlDraft = entry.url
                                remoteLabelDraft = entry.label ?: ""
                                editingRemoteIndex = entry.index
                            },
                            onDraftChange = { remoteUrlDraft = it },
                            onSave = {
                                viewModel.updateRemoteUrl(entry.index, remoteUrlDraft.trim(), remoteLabelDraft.ifEmpty { null })
                                editingRemoteIndex = -1
                            },
                            onCancel = { editingRemoteIndex = -1 },
                            onDelete = {
                                viewModel.deleteRemoteUrl(entry.index)
                                editingRemoteIndex = -1
                            },
                            labelDraft = remoteLabelDraft,
                            onLabelChange = { remoteLabelDraft = it },
                        )
                    }

                    // Add remote URL
                    if (state.remoteUrls.size < SecretStore.MAX_REMOTE_URLS) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (showAddRemote) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                OutlinedTextField(
                                    value = newRemoteLabel,
                                    onValueChange = { newRemoteLabel = it },
                                    singleLine = true,
                                    label = { Text("Label (e.g. Tailscale, IPv6)") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = newRemoteUrl,
                                        onValueChange = { newRemoteUrl = it },
                                        singleLine = true,
                                        label = { Text("URL") },
                                        placeholder = { Text("https://192.168.1.10:8443") },
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    androidx.compose.material3.IconButton(
                                        onClick = { onScanQr() },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.QrCodeScanner,
                                            contentDescription = stringResource(R.string.onboarding_scan_qr),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                                ) {
                                    TextButton(onClick = { showAddRemote = false }) {
                                        Text(stringResource(android.R.string.cancel))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            viewModel.addRemoteUrl(newRemoteUrl.trim(), newRemoteLabel.trim().ifEmpty { null })
                                            showAddRemote = false
                                            newRemoteUrl = ""
                                            newRemoteLabel = ""
                                        },
                                        enabled = newRemoteUrl.trim().isNotEmpty(),
                                    ) {
                                        Text(stringResource(android.R.string.ok))
                                    }
                                }
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    newRemoteUrl = ""
                                    newRemoteLabel = ""
                                    showAddRemote = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.settings_add_remote))
                            }
                        }
                    }
                }
            }

            // Section 1b: Server info
            item { SectionHeader(stringResource(R.string.settings_server)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    InfoRow(Icons.Filled.Info, stringResource(R.string.settings_server_id), state.serverId)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow(Icons.Filled.Shield, stringResource(R.string.settings_tls_fingerprint), state.tlsFingerprint)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow(Icons.Filled.Sync, stringResource(R.string.settings_protocol_version), state.protocolVersion)
                }
            }

            // Section 2: This device
            item { SectionHeader(stringResource(R.string.settings_this_device)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    val pairedDate = if (state.pairedAt > 0L) {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .format(Date(state.pairedAt * 1000))
                    } else {
                        "N/A"
                    }
                    SettingsRow(
                        icon = Icons.Filled.PhoneAndroid,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        label = state.deviceName.ifEmpty { stringResource(R.string.settings_unknown) },
                        description = "${state.platform} · ${stringResource(R.string.devices_paired, pairedDate)}",
                    )
                }
            }

            // Section 3: Storage
            item { SectionHeader(stringResource(R.string.settings_storage)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    val folderLabel = state.downloadFolderUri?.let { uri ->
                        Uri.parse(uri).lastPathSegment?.replace("primary:", "")
                            ?: stringResource(R.string.settings_folder_selected)
                    } ?: stringResource(R.string.settings_folder_not_set)

                    SettingsRow(
                        icon = Icons.Filled.FolderOpen,
                        label = stringResource(R.string.settings_download_folder),
                        description = folderLabel,
                        trailing = {
                            TextButton(onClick = { folderPicker.launch(null) }) {
                                Text(
                                    if (state.downloadFolderUri != null) stringResource(R.string.settings_folder_change)
                                    else stringResource(R.string.settings_folder_choose),
                                )
                            }
                        },
                    )
                }
            }

            // Section 4: Options
            item { SectionHeader(stringResource(R.string.settings_options)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    SettingsRow(
                        icon = Icons.Filled.SwapHoriz,
                        label = stringResource(R.string.settings_left_hand),
                        description = stringResource(R.string.settings_left_hand_desc),
                        trailing = {
                            Switch(
                                checked = state.leftHandMode,
                                onCheckedChange = { viewModel.setLeftHandMode(it) },
                            )
                        },
                    )
                }
            }

            // Section 5: Privacy
            item { SectionHeader(stringResource(R.string.settings_privacy)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    SettingsRow(
                        icon = Icons.Filled.Lock,
                        label = stringResource(R.string.settings_hidden_protection),
                        description = stringResource(R.string.settings_hidden_protection_desc),
                        trailing = {
                            Switch(
                                checked = state.biometricProtection,
                                onCheckedChange = { viewModel.setBiometricProtection(it) },
                            )
                        },
                    )
                }
            }

            // Section 6: App version
            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    InfoRow(Icons.Filled.Info, stringResource(R.string.settings_app_version), io.synctuary.android.BuildConfig.VERSION_NAME)
                }
            }

            // Section 7: Photo backup
            item { SectionHeader(stringResource(R.string.settings_photo_backup)) }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.settings_auto_backup), style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = state.backupEnabled,
                                onCheckedChange = { viewModel.setBackupEnabled(it) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.settings_wifi_only), style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = state.backupWifiOnly,
                                onCheckedChange = { viewModel.setBackupWifiOnly(it) },
                                enabled = state.backupEnabled,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_upload_to, state.backupRemotePath),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Section 8: Danger zone
            item { SectionHeader(stringResource(R.string.settings_danger_zone)) }
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Button(
                            onClick = { showUnpairDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_unpair))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_unpair_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text(stringResource(R.string.settings_unpair_title)) },
            text = {
                Text(stringResource(R.string.settings_unpair_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnpairDialog = false
                    viewModel.unpair { onUnpaired() }
                }) {
                    Text(stringResource(R.string.settings_unpair), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LanguageSelector() {
    val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val isJapanese = currentLocale.startsWith("ja")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageChip(
                label = "English",
                selected = !isJapanese,
                onClick = {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                },
            )
            LanguageChip(
                label = "日本語",
                selected = isJapanese,
                onClick = {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ja"))
                },
            )
        }
    }
}

@Composable
private fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    label: String,
    description: String = "",
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        trailing()
    }
}

@Composable
private fun EditableUrlRow(
    icon: ImageVector,
    label: String,
    value: String,
    editing: Boolean,
    draft: String,
    onEditStart: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    labelDraft: String? = null,
    onLabelChange: ((String) -> Unit)? = null,
) {
    if (editing) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (labelDraft != null && onLabelChange != null) {
                OutlinedTextField(
                    value = labelDraft,
                    onValueChange = onLabelChange,
                    singleLine = true,
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                label = { Text("URL") },
                placeholder = { Text("https://192.168.1.10:8443") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onSave) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEditStart() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
