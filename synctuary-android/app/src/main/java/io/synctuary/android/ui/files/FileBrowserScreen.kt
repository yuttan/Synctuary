package io.synctuary.android.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.synctuary.android.data.api.dto.FileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(viewModel: FileBrowserViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Synctuary") },
                actions = {
                    IconButton(onClick = { /* search — future */ }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { /* overflow menu — future */ }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* upload — Phase 4 */ },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Upload")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Breadcrumbs
            BreadcrumbBar(
                path = state.currentPath,
                onNavigate = { viewModel.navigateToBreadcrumb(it) },
            )

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
                state.entries.isEmpty() -> {
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
                }
                else -> {
                    FileList(
                        entries = state.entries,
                        onTap = { entry ->
                            if (entry.type == "dir") {
                                viewModel.navigateInto(entry.name)
                            }
                        },
                        onLongPress = { entry ->
                            viewModel.selectForAction(entry)
                        },
                    )
                }
            }
        }

        // Bottom sheet
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
            )
        }
    }
}

@Composable
private fun BreadcrumbBar(path: String, onNavigate: (String) -> Unit) {
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
                    .combinedClickable(onClick = { onNavigate(segPath) })
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    entries: List<FileEntry>,
    onTap: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.name }) { entry ->
            FileRow(
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
private fun FileRow(
    entry: FileEntry,
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
                text = entrySubtext(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class EntryVisual(val icon: ImageVector, val tint: Color, val bg: Color)

private fun entryVisual(entry: FileEntry): EntryVisual {
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
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
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
