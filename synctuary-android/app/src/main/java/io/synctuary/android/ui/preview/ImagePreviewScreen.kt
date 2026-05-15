package io.synctuary.android.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewScreen(
    remotePath: String,
    viewModel: PreviewViewModel,
    onBack: () -> Unit,
) {
    val paths = viewModel.imagePaths
    val initialIndex = viewModel.indexOfImage(remotePath)

    if (paths.isEmpty()) {
        SingleImagePreview(remotePath, viewModel, onBack)
        return
    }

    val pagerState = rememberPagerState(initialPage = initialIndex) { paths.size }

    val currentPath by remember {
        derivedStateOf { paths.getOrElse(pagerState.currentPage) { remotePath } }
    }
    val currentFileName by remember {
        derivedStateOf { currentPath.substringAfterLast('/') }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "${pagerState.currentPage + 1}/${paths.size}  $currentFileName",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) { page ->
            val pagePath = paths[page]
            val url = viewModel.contentUrl(pagePath)
            ImagePage(url = url, fileName = pagePath.substringAfterLast('/'), viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleImagePreview(
    remotePath: String,
    viewModel: PreviewViewModel,
    onBack: () -> Unit,
) {
    val fileName = remotePath.substringAfterLast('/')
    val contentUrl = viewModel.contentUrl(remotePath)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            ImagePage(url = contentUrl, fileName = fileName, viewModel = viewModel)
        }
    }
}

@Composable
private fun ImagePage(url: String, fileName: String, viewModel: PreviewViewModel) {
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        imageLoader = viewModel.imageLoader,
        contentDescription = fileName,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        },
        error = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Failed to load image",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
