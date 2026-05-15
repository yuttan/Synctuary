package io.synctuary.android.ui.files

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.synctuary.android.data.api.dto.FileEntry
import io.synctuary.android.ui.preview.PreviewViewModel

@Composable
fun FilesTabScreen(
    fileBrowserVm: FileBrowserViewModel,
    localFilesVm: LocalFilesViewModel,
    previewVm: PreviewViewModel? = null,
    leftHandMode: Boolean,
    onPreview: (FileEntry) -> Unit,
    onAddToFavorites: ((entry: FileEntry, path: String) -> Unit)?,
    onUploadFromLocal: (Uri) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            indicator = { tabPositions ->
                if (selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Server") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    // Refresh local listing each time the tab is shown
                    localFilesVm.loadDirectory()
                },
                text = { Text("Device") },
            )
        }

        when (selectedTab) {
            0 -> FileBrowserScreen(
                viewModel = fileBrowserVm,
                previewViewModel = previewVm,
                leftHandMode = leftHandMode,
                onPreview = onPreview,
                onAddToFavorites = onAddToFavorites,
            )
            1 -> LocalFilesScreen(
                viewModel = localFilesVm,
                onUploadToServer = onUploadFromLocal,
            )
        }
    }
}
