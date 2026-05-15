package io.synctuary.android.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FabPosition
import androidx.compose.ui.layout.ContentScale
import coil.ImageLoader
import coil.compose.AsyncImage
import io.synctuary.android.data.TransferState
import io.synctuary.android.data.api.dto.FileEntry
import io.synctuary.android.data.api.dto.ShareEntry
import io.synctuary.android.ui.preview.PreviewViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    previewViewModel: PreviewViewModel? = null,
    onPreview: (FileEntry) -> Unit = {},
    onAddToFavorites: ((entry: FileEntry, path: String) -> Unit)? = null,
    leftHandMode: Boolean = false,
) {
    val state by viewModel.uiState.collectAsState()
    val sharesList by viewModel.shares.collectAsState()
    val currentShare by viewModel.currentShare.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var moveEntry by remember { mutableStateOf<FileEntry?>(null) }

    BackHandler(enabled = state.currentPath != "/" || viewModel.isAtSharesRoot || currentShare != null) {
        viewModel.navigateUp()
    }

    LaunchedEffect(currentShare) {
        previewViewModel?.currentShareId = currentShare?.id
    }

    var detailsEntry by remember { mutableStateOf<FileEntry?>(null) }

    // The entry currently requesting "Save As..." — set when the user
    // taps the action, consumed when the SAF picker returns a URI.
    var saveAsEntry by remember { mutableStateOf<FileEntry?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.startUpload(it) }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            saveAsEntry?.let { entry ->
                viewModel.saveAsDownload(entry, uri)
            }
        }
        saveAsEntry = null
    }

    LaunchedEffect(state.downloadState) {
        when (val ds = state.downloadState) {
            is TransferState.Done -> {
                snackbarHostState.showSnackbar("Downloaded: ${ds.fileName}")
                viewModel.dismissTransferFeedback()
            }
            is TransferState.Failed -> {
                snackbarHostState.showSnackbar("Download failed: ${ds.message}")
                viewModel.dismissTransferFeedback()
            }
            else -> {}
        }
    }
    LaunchedEffect(state.uploadState) {
        when (val us = state.uploadState) {
            is TransferState.Done -> {
                snackbarHostState.showSnackbar("Uploaded: ${us.fileName}")
                viewModel.dismissTransferFeedback()
            }
            is TransferState.Failed -> {
                snackbarHostState.showSnackbar("Upload failed: ${us.message}")
                viewModel.dismissTransferFeedback()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            if (state.searchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search files...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.toggleSearch() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("Synctuary") },
                    actions = {
                        Box {
                            var sortMenuExpanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                SortOption.entries.forEach { option ->
                                    val label = when (option) {
                                        SortOption.NAME -> "Name"
                                        SortOption.DATE -> "Date"
                                        SortOption.SIZE -> "Size"
                                    }
                                    val arrow = if (state.sortBy == option) {
                                        if (state.sortAscending) " ▲" else " ▼"
                                    } else ""
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "$label$arrow",
                                                color = if (state.sortBy == option)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                            )
                                        },
                                        onClick = {
                                            viewModel.setSortOption(option)
                                            sortMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.toggleSearch() }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        },
        floatingActionButton = {
            if (!viewModel.isAtSharesRoot) {
                FloatingActionButton(
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Upload")
                }
            }
        },
        floatingActionButtonPosition = if (leftHandMode) FabPosition.Start else FabPosition.End,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!viewModel.isAtSharesRoot) {
                BreadcrumbBar(
                    path = state.currentPath,
                    shareName = currentShare?.name,
                    onNavigate = { viewModel.navigateToBreadcrumb(it) },
                    onSharesRoot = if (sharesList.size > 1) {
                        { viewModel.navigateUp(); Unit }
                    } else null,
                )
            }

            TransferBanner(downloadState = state.downloadState, uploadState = state.uploadState)

            when {
                state.loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                state.filteredEntries.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                state.searchActive -> "No matches"
                                viewModel.isAtSharesRoot -> "No shares configured"
                                else -> "Empty folder"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                else -> {
                    FileList(
                        entries = state.filteredEntries,
                        currentPath = state.currentPath,
                        previewViewModel = previewViewModel,
                        onTap = { entry ->
                            when (entry.type) {
                                "share" -> {
                                    val share = sharesList.find { it.id == entry.sha256 }
                                    if (share != null) viewModel.selectShare(share)
                                }
                                "dir" -> viewModel.navigateInto(entry.name)
                                else -> onPreview(entry)
                            }
                        },
                        onLongPress = { entry ->
                            if (entry.type != "share") {
                                viewModel.selectForAction(entry)
                            }
                        },
                    )
                }
            }
        }

        state.selectedEntry?.let { entry ->
            FileActionSheet(
                entry = entry,
                currentPath = state.currentPath,
                onDismiss = { viewModel.selectForAction(null) },
                onDelete = {
                    viewModel.deleteFile(entry)
                    viewModel.selectForAction(null)
                },
                onRename = { newName ->
                    viewModel.renameFile(entry, newName)
                    viewModel.selectForAction(null)
                },
                onDownload = {
                    viewModel.startDownload(entry)
                    viewModel.selectForAction(null)
                },
                onSaveAs = {
                    saveAsEntry = entry
                    viewModel.selectForAction(null)
                    saveAsLauncher.launch(entry.name)
                },
                onAddToFavorites = {
                    val current = state.currentPath
                    val fullPath = if (current == "/") "/${entry.name}" else "$current/${entry.name}"
                    viewModel.selectForAction(null)
                    onAddToFavorites?.invoke(entry, fullPath)
                },
                onMove = {
                    moveEntry = entry
                    viewModel.selectForAction(null)
                },
                onDetails = {
                    detailsEntry = entry
                    viewModel.selectForAction(null)
                },
            )
        }

        moveEntry?.let { entry ->
            MoveDialog(
                entryName = entry.name,
                currentPath = state.currentPath,
                onConfirm = { dest ->
                    viewModel.moveFile(entry, dest)
                    moveEntry = null
                },
                onDismiss = { moveEntry = null },
                listDirectory = { path -> viewModel.listDirectory(path) },
            )
        }

        detailsEntry?.let { entry ->
            FileDetailsDialog(
                entry = entry,
                currentPath = state.currentPath,
                onDismiss = { detailsEntry = null },
            )
        }
    }
}

@Composable
private fun TransferBanner(downloadState: TransferState, uploadState: TransferState) {
    val running = (downloadState as? TransferState.Running)
        ?: (uploadState as? TransferState.Running)
        ?: return

    val label = if (downloadState is TransferState.Running) {
        "Downloading ${running.fileName}…"
    } else {
        "Uploading ${running.fileName}…"
    }
    val fraction = running.progressFraction

    Column(modifier = Modifier.fillMaxWidth()) {
        if (fraction >= 0f) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun BreadcrumbBar(
    path: String,
    shareName: String? = null,
    onNavigate: (String) -> Unit,
    onSharesRoot: (() -> Unit)? = null,
) {
    val segments = if (path == "/") listOf("/") else {
        listOf("/") + path.removePrefix("/").split("/")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (shareName != null && onSharesRoot != null) {
            Text(
                text = shareName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSharesRoot() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                text = "/",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(
                    text = "/",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val segPath = if (index == 0) "/" else {
                "/" + segments.subList(1, index + 1).joinToString("/")
            }
            val isActive = index == segments.lastIndex
            Text(
                text = if (index == 0 && shareName != null) "/" else segment,
                color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (isActive) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    .clickable { onNavigate(segPath) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    entries: List<FileEntry>,
    currentPath: String,
    previewViewModel: PreviewViewModel?,
    onTap: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.name }) { entry ->
            val thumbUrl = remember(entry.name, currentPath) {
                if (previewViewModel != null && isThumbnailable(entry.mime_type)) {
                    val remotePath = if (currentPath == "/") "/${entry.name}" else "$currentPath/${entry.name}"
                    previewViewModel.thumbnailUrl(remotePath)
                } else null
            }
            FileRow(
                entry = entry,
                thumbnailUrl = thumbUrl,
                imageLoader = previewViewModel?.imageLoader,
                onTap = { onTap(entry) },
                onLongPress = { onLongPress(entry) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    thumbnailUrl: String? = null,
    imageLoader: ImageLoader? = null,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val (icon, tint, bgColor) = entryVisual(entry)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnailUrl != null && imageLoader != null) {
            AsyncImage(
                model = thumbnailUrl,
                imageLoader = imageLoader,
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entrySubtext(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class EntryVisual(val icon: ImageVector, val tint: Color, val bg: Color)

private fun entryVisual(entry: FileEntry): EntryVisual {
    if (entry.type == "share") {
        val hostPath = entry.mime_type ?: ""
        val isDriveRoot = hostPath.matches(Regex("""^[A-Za-z]:[/\\]?$""")) || hostPath == "/"
        val icon = if (isDriveRoot) Icons.Filled.Storage else Icons.Filled.Folder
        return EntryVisual(
            icon,
            Color(0xFF90CAF9),
            Color(0xFF90CAF9).copy(alpha = 0.12f),
        )
    }
    if (entry.type == "dir") {
        return EntryVisual(
            Icons.Filled.Folder,
            Color(0xFFCFBCFF),
            Color(0xFFCFBCFF).copy(alpha = 0.12f),
        )
    }
    val mime = entry.mime_type ?: ""
    return when {
        mime.startsWith("image/") -> EntryVisual(
            Icons.Filled.Image,
            Color(0xFFEFB8C8),
            Color(0xFFEFB8C8).copy(alpha = 0.12f),
        )
        mime.startsWith("video/") -> EntryVisual(
            Icons.Filled.Videocam,
            Color(0xFFEFB8C8),
            Color(0xFFEFB8C8).copy(alpha = 0.12f),
        )
        mime.startsWith("audio/") -> EntryVisual(
            Icons.Filled.MusicNote,
            Color(0xFFCCC2DC),
            Color(0xFFCCC2DC).copy(alpha = 0.10f),
        )
        else -> EntryVisual(
            Icons.Filled.Description,
            Color(0xFFCCC2DC),
            Color(0xFFCCC2DC).copy(alpha = 0.10f),
        )
    }
}

private fun entrySubtext(entry: FileEntry): String {
    if (entry.type == "share") return entry.mime_type ?: ""
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .format(Date(entry.modified_at * 1000))
    return if (entry.type == "dir") {
        date
    } else {
        "${formatSize(entry.size ?: 0)} · $date"
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun isThumbnailable(mime: String?): Boolean {
    if (mime == null) return false
    return mime.startsWith("image/jpeg") ||
        mime.startsWith("image/png") ||
        mime.startsWith("image/gif") ||
        mime.startsWith("image/webp") ||
        mime.startsWith("video/")
}

