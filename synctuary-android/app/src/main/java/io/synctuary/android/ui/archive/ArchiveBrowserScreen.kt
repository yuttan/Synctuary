package io.synctuary.android.ui.archive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.synctuary.android.R
import kotlinx.coroutines.launch

/**
 * Browses the contents of a single archive. Folder rows navigate deeper;
 * file rows open image entries in the comic-reader pager, media entries in
 * the media player, and show a "not supported" snackbar for anything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveBrowserScreen(
    archivePath: String,
    shareId: String?,
    viewModel: ArchiveBrowserViewModel,
    onBack: () -> Unit,
    onOpenImage: (entryPath: String, allImagePaths: List<String>) -> Unit,
    onOpenMedia: (entryPath: String) -> Unit,
) {
    LaunchedEffect(archivePath, shareId) {
        viewModel.start(archivePath, shareId)
    }

    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unsupportedMsg = stringResource(R.string.archive_preview_unsupported)

    // Back navigates up within the archive first, then leaves the screen.
    BackHandler(enabled = true) {
        if (!viewModel.navigateUp()) onBack()
    }

    val archiveName = archivePath.substringAfterLast('/')

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = archiveName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.navigateUp()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.archive_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ArchiveBreadcrumb(
                currentDir = state.currentDir,
                onNavigate = { viewModel.navigateToBreadcrumb(it) },
            )

            when {
                state.loading -> CenterBox { CircularProgressIndicator() }
                state.error != null -> CenterBox {
                    Text(
                        text = state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.items.isEmpty() -> CenterBox {
                    Text(
                        text = stringResource(R.string.archive_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.path }) { item ->
                        ArchiveRow(
                            item = item,
                            onTap = {
                                if (item.isDir) {
                                    viewModel.navigateInto(item)
                                } else when (entryKind(item.path)) {
                                    EntryKind.IMAGE -> onOpenImage(item.path, viewModel.imageEntryPaths())
                                    EntryKind.VIDEO, EntryKind.AUDIO -> onOpenMedia(item.path)
                                    EntryKind.OTHER -> scope.launch {
                                        snackbarHostState.showSnackbar(unsupportedMsg)
                                    }
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ArchiveBreadcrumb(
    currentDir: String,
    onNavigate: (String) -> Unit,
) {
    // segments: ["/"] at root, plus each directory component.
    val parts = if (currentDir.isEmpty()) emptyList() else currentDir.split("/")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreadcrumbChip(
            label = stringResource(R.string.archive_root),
            active = parts.isEmpty(),
            onClick = { onNavigate("") },
        )
        parts.forEachIndexed { index, segment ->
            Text(
                text = "/",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
            )
            val segPath = parts.subList(0, index + 1).joinToString("/")
            BreadcrumbChip(
                label = segment,
                active = index == parts.lastIndex,
                onClick = { onNavigate(segPath) },
            )
        }
    }
}

@Composable
private fun BreadcrumbChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ArchiveRow(item: ArchiveItem, onTap: () -> Unit) {
    val (icon, tint, bg) = archiveItemVisual(item)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(bg),
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
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (item.isDir) stringResource(R.string.archive_folder)
                else formatArchiveSize(item.size ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class ArchiveVisual(val icon: ImageVector, val tint: Color, val bg: Color)

private fun archiveItemVisual(item: ArchiveItem): ArchiveVisual {
    if (item.isDir) {
        return ArchiveVisual(
            Icons.Filled.Folder,
            Color(0xFFCFBCFF),
            Color(0xFFCFBCFF).copy(alpha = 0.12f),
        )
    }
    return when (entryKind(item.path)) {
        EntryKind.IMAGE -> ArchiveVisual(Icons.Filled.Image, Color(0xFFEFB8C8), Color(0xFFEFB8C8).copy(alpha = 0.12f))
        EntryKind.VIDEO -> ArchiveVisual(Icons.Filled.Videocam, Color(0xFFEFB8C8), Color(0xFFEFB8C8).copy(alpha = 0.12f))
        EntryKind.AUDIO -> ArchiveVisual(Icons.Filled.MusicNote, Color(0xFFCCC2DC), Color(0xFFCCC2DC).copy(alpha = 0.10f))
        EntryKind.OTHER -> ArchiveVisual(Icons.Filled.Description, Color(0xFFCCC2DC), Color(0xFFCCC2DC).copy(alpha = 0.10f))
    }
}

private fun formatArchiveSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
}
