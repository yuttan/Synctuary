package io.synctuary.android.ui.files

import android.net.Uri
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFilesScreen(
    viewModel: LocalFilesViewModel,
    onUploadToServer: (Uri) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var selectedEntry by remember { mutableStateOf<LocalFileEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            !state.folderConfigured -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No download folder configured",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Set a download folder in Settings",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

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

            else -> {
                // Breadcrumb path bar
                LocalBreadcrumbBar(
                    relativePath = state.currentPath,
                    onNavigateRoot = {
                        // Pop back to root
                        while (viewModel.navigateUp()) { /* drain */ }
                    },
                    onNavigateSegment = { segmentIndex ->
                        // Navigate up to the requested depth: current depth minus target
                        val segments = state.currentPath.split("/").filter { it.isNotEmpty() }
                        val popsNeeded = segments.size - segmentIndex - 1
                        repeat(popsNeeded) { viewModel.navigateUp() }
                    },
                )

                if (state.entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Empty folder",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    LocalFileList(
                        entries = state.entries,
                        onTap = { entry ->
                            if (entry.isDirectory) {
                                viewModel.navigateInto(entry.name)
                            } else {
                                viewModel.openFile(entry)
                            }
                        },
                        onLongPress = { entry ->
                            selectedEntry = entry
                        },
                    )
                }
            }
        }
    }

    // Action bottom sheet
    selectedEntry?.let { entry ->
        LocalFileActionSheet(
            entry = entry,
            onDismiss = { selectedEntry = null },
            onOpen = {
                viewModel.openFile(entry)
                selectedEntry = null
            },
            onShare = {
                viewModel.shareFile(entry)
                selectedEntry = null
            },
            onUploadToServer = {
                onUploadToServer(entry.uri)
                selectedEntry = null
            },
            onDelete = {
                viewModel.deleteFile(entry)
                selectedEntry = null
            },
        )
    }
}

// -- Breadcrumb bar ----------------------------------------------------------

@Composable
private fun LocalBreadcrumbBar(
    relativePath: String,
    onNavigateRoot: () -> Unit,
    onNavigateSegment: (Int) -> Unit,
) {
    val segments = if (relativePath.isBlank()) {
        emptyList()
    } else {
        relativePath.split("/").filter { it.isNotEmpty() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Root chip (always shown)
        val isRootActive = segments.isEmpty()
        Text(
            text = "Downloads",
            color = if (isRootActive) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (isRootActive) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                )
                .clickable { onNavigateRoot() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        segments.forEachIndexed { index, segment ->
            Text(
                text = "/",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )

            val isActive = index == segments.lastIndex
            Text(
                text = segment,
                color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (isActive) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    .clickable { onNavigateSegment(index) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

// -- File list ---------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalFileList(
    entries: List<LocalFileEntry>,
    onTap: (LocalFileEntry) -> Unit,
    onLongPress: (LocalFileEntry) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.name }) { entry ->
            LocalFileRow(
                entry = entry,
                onTap = { onTap(entry) },
                onLongPress = { onLongPress(entry) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalFileRow(
    entry: LocalFileEntry,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val (icon, tint, bgColor) = localEntryVisual(entry)

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
                text = localEntrySubtext(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -- Action bottom sheet -----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalFileActionSheet(
    entry: LocalFileEntry,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onUploadToServer: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = localEntryIcon(entry),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = localEntrySubtext(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // Actions
        if (!entry.isDirectory) {
            LocalSheetAction(Icons.Filled.OpenInNew, "Open") { onOpen() }
            LocalSheetAction(Icons.Filled.Share, "Share") { onShare() }
            LocalSheetAction(Icons.Filled.CloudUpload, "Upload to server") { onUploadToServer() }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        LocalSheetAction(
            icon = Icons.Filled.Delete,
            label = "Delete",
            tint = MaterialTheme.colorScheme.error,
            onClick = onDelete,
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LocalSheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tint == MaterialTheme.colorScheme.onSurface) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

// -- Visual helpers (matching remote FileBrowserScreen palette) ---------------

private data class LocalEntryVisual(val icon: ImageVector, val tint: Color, val bg: Color)

private fun localEntryVisual(entry: LocalFileEntry): LocalEntryVisual {
    if (entry.isDirectory) {
        return LocalEntryVisual(
            Icons.Filled.Folder,
            Color(0xFFCFBCFF),
            Color(0xFFCFBCFF).copy(alpha = 0.12f),
        )
    }
    val mime = entry.mimeType ?: ""
    return when {
        mime.startsWith("image/") -> LocalEntryVisual(
            Icons.Filled.Image,
            Color(0xFFEFB8C8),
            Color(0xFFEFB8C8).copy(alpha = 0.12f),
        )
        mime.startsWith("video/") -> LocalEntryVisual(
            Icons.Filled.Videocam,
            Color(0xFFEFB8C8),
            Color(0xFFEFB8C8).copy(alpha = 0.12f),
        )
        mime.startsWith("audio/") -> LocalEntryVisual(
            Icons.Filled.MusicNote,
            Color(0xFFCCC2DC),
            Color(0xFFCCC2DC).copy(alpha = 0.10f),
        )
        else -> LocalEntryVisual(
            Icons.Filled.Description,
            Color(0xFFCCC2DC),
            Color(0xFFCCC2DC).copy(alpha = 0.10f),
        )
    }
}

private fun localEntryIcon(entry: LocalFileEntry): ImageVector {
    if (entry.isDirectory) return Icons.Filled.Folder
    val mime = entry.mimeType ?: ""
    return when {
        mime.startsWith("image/") -> Icons.Filled.Image
        mime.startsWith("video/") -> Icons.Filled.Videocam
        mime.startsWith("audio/") -> Icons.Filled.MusicNote
        else -> Icons.Filled.Description
    }
}

private fun localEntrySubtext(entry: LocalFileEntry): String {
    val date = if (entry.lastModified > 0L) {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(entry.lastModified))
    } else {
        ""
    }
    return if (entry.isDirectory) {
        date
    } else {
        val size = localFormatSize(entry.size)
        if (date.isNotEmpty()) "$size · $date" else size
    }
}

private fun localFormatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
}
