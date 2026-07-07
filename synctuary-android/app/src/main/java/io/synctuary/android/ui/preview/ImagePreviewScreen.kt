package io.synctuary.android.ui.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * Display modes cycled by the single top-bar IconButton.
 *
 *  - [FIT]      whole image visible (zoom == 1x fit baseline)
 *  - [FILL]     screen completely filled, user pans to see the rest
 *  - [ORIGINAL] 1:1 actual pixels
 *
 * A mode is a "jump to" action, not a lock: after selecting one the user
 * may still pinch freely. See requirement §3.
 */
private enum class DisplayMode { FIT, FILL, ORIGINAL }

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

// Bounding box for the decoded bitmap. Full-resolution (Size.ORIGINAL)
// would OOM on large camera photos (a 48MP JPEG decodes to ~190MB) and
// can exceed the GPU max texture dimension (typically 4096px), which
// crashes hardware-bitmap rendering. 4096px keeps memory bounded
// (~33MB worst case) while giving ORIGINAL (1:1) mode real pixels for
// anything up to 4K — beyond that, 1:1 shows the max loaded resolution.
private const val MAX_BITMAP_DIM = 4096

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

    // Hoisted zoom flag for the current page: when true the horizontal pan is
    // consumed by the image (pinch pan) and must not swipe the pager. Reset
    // whenever the page changes so a freshly-swiped page starts pager-scrollable.
    var currentPageZoomed by remember { mutableStateOf(false) }
    // Display mode requested from the top bar; applied to the visible page.
    var displayMode by remember { mutableStateOf(DisplayMode.FIT) }
    // Chrome (top bar) is hidden by default; a single tap on the image toggles
    // it so tall/zoomed images are never covered.
    var chromeVisible by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        currentPageZoomed = false
        displayMode = DisplayMode.FIT
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !currentPageZoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pagePath = paths[page]
            val url = viewModel.contentUrl(pagePath)
            // Only the currently-visible page reports its zoom state and reacts
            // to the shared display mode; off-screen pages keep FIT so their
            // remembered zoom state resets when swiped back into view.
            val isActivePage = page == pagerState.currentPage
            ImagePage(
                url = url,
                fileName = pagePath.substringAfterLast('/'),
                viewModel = viewModel,
                displayMode = if (isActivePage) displayMode else DisplayMode.FIT,
                onZoomChanged = { zoomed ->
                    if (isActivePage) currentPageZoomed = zoomed
                },
                onTap = { chromeVisible = !chromeVisible },
            )
        }

        // Top bar overlays the image (does not occupy layout space) and
        // fades in/out on tap. Material3 TopAppBar handles status-bar insets.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(),
        ) {
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
                actions = {
                    DisplayModeButton(mode = displayMode, onCycle = { displayMode = it })
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
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

    var displayMode by remember { mutableStateOf(DisplayMode.FIT) }
    // Chrome (top bar) is hidden by default; a single tap toggles it.
    var chromeVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ImagePage(
            url = contentUrl,
            fileName = fileName,
            viewModel = viewModel,
            displayMode = displayMode,
            onZoomChanged = {},
            onTap = { chromeVisible = !chromeVisible },
        )

        // Top bar overlays the image and fades in/out on tap.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(),
        ) {
            TopAppBar(
                title = {
                    Text(text = fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    DisplayModeButton(mode = displayMode, onCycle = { displayMode = it })
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        }
    }
}

/**
 * Single top-bar control that cycles FIT -> FILL -> ORIGINAL -> FIT.
 * The icon shows the CURRENT mode; tapping advances to the next one.
 */
@Composable
private fun DisplayModeButton(mode: DisplayMode, onCycle: (DisplayMode) -> Unit) {
    val next = when (mode) {
        DisplayMode.FIT -> DisplayMode.FILL
        DisplayMode.FILL -> DisplayMode.ORIGINAL
        DisplayMode.ORIGINAL -> DisplayMode.FIT
    }
    IconButton(
        onClick = { onCycle(next) },
    ) {
        when (mode) {
            DisplayMode.FIT ->
                Icon(Icons.Filled.FitScreen, contentDescription = "Display mode: Fit (tap for Fill)")
            DisplayMode.FILL ->
                Icon(Icons.Filled.OpenInFull, contentDescription = "Display mode: Fill (tap for 1:1)")
            DisplayMode.ORIGINAL ->
                // No material icon reads as "1:1 actual pixels"; the label is clearest.
                Text(text = "1:1", fontSize = 16.sp)
        }
    }
}

/**
 * A single zoomable/pannable image page.
 *
 * All gesture state is hoisted OUTSIDE the pointerInput blocks — `remember {}`
 * cannot be called inside a pointerInput lambda (CLAUDE.md §6.7). Each page
 * instance owns its own state, so swiping away and back yields a fresh (fit)
 * state.
 *
 * @param displayMode when it changes, the scale animates to the computed
 *   target and the pan offset resets to centre. Manual pinch afterwards is
 *   still allowed (mode is a jump-to, not a lock).
 * @param onZoomChanged reports whether the page is currently zoomed (scale > 1)
 *   so the parent pager can disable horizontal swipe.
 * @param onTap single-tap callback (distinct from double-tap); the parent uses
 *   it to toggle the overlay chrome.
 */
@Composable
private fun ImagePage(
    url: String,
    fileName: String,
    viewModel: PreviewViewModel,
    displayMode: DisplayMode,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Gesture state — hoisted out of every pointerInput block.
    val scale = remember { Animatable(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Container (viewport) size in px, filled once laid out.
    var containerSize by remember { mutableStateOf(Size.Zero) }
    // Full-resolution intrinsic pixel size of the loaded bitmap. Size.ORIGINAL
    // on the request keeps Coil from downsampling to the view size, so this
    // reports REAL pixels for the 1:1 (ORIGINAL) computation. Unspecified until
    // the painter reaches Success.
    var intrinsicSize by remember { mutableStateOf(Size.Unspecified) }

    // Displayed image size under ContentScale.Fit (== scale 1 baseline).
    // Guarded: only valid once both container and intrinsic sizes are known.
    fun fitSize(): Size? {
        val c = containerSize
        val i = intrinsicSize
        if (c.width <= 0f || c.height <= 0f) return null
        if (i == Size.Unspecified || i.width <= 0f || i.height <= 0f) return null
        val fitScale = minOf(c.width / i.width, c.height / i.height)
        return Size(i.width * fitScale, i.height * fitScale)
    }

    // Clamp pan so the (scaled) image edge never moves past the container edge.
    fun clampOffset(candidate: Offset, atScale: Float): Offset {
        val fit = fitSize() ?: return Offset.Zero
        val maxX = ((fit.width * atScale - containerSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fit.height * atScale - containerSize.height) / 2f).coerceAtLeast(0f)
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY),
        )
    }

    // Report zoom state to the parent whenever the scale crosses 1x.
    val isZoomed = scale.value > 1f + 0.001f
    LaunchedEffect(isZoomed) { onZoomChanged(isZoomed) }

    // Apply a display-mode jump: animate scale to target, recentre pan.
    LaunchedEffect(displayMode, containerSize, intrinsicSize) {
        val fit = fitSize() ?: return@LaunchedEffect
        val target = when (displayMode) {
            DisplayMode.FIT -> MIN_SCALE
            DisplayMode.FILL -> {
                // Scale (relative to fit) needed to cover the container fully.
                val cover = maxOf(
                    containerSize.width / fit.width,
                    containerSize.height / fit.height,
                )
                cover.coerceIn(MIN_SCALE, MAX_SCALE)
            }
            DisplayMode.ORIGINAL -> {
                // 1:1 == real pixels / fit-displayed pixels.
                val oneToOne = intrinsicSize.width / fit.width
                oneToOne.coerceIn(MIN_SCALE, MAX_SCALE)
            }
        }
        offset = Offset.Zero
        scale.animateTo(target)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Custom transform loop. detectTransformGestures consumes SINGLE-
            // finger drags too, which starves the parent HorizontalPager of
            // swipes. Instead we only handle + consume when the gesture is
            // "ours" (two+ fingers, or already zoomed). A single finger at
            // scale 1 is consumed by nothing, so the pager gets the swipe.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointersDown = event.changes.count { it.pressed }
                        val zoomedNow = scale.value > 1.001f
                        if (pointersDown > 1 || zoomedNow) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid()

                            val old = scale.value
                            // Guard NaN / non-positive zoom deltas defensively.
                            val zoom = if (zoomChange > 0f && !zoomChange.isNaN()) zoomChange else 1f
                            val new = (old * zoom).coerceIn(MIN_SCALE, MAX_SCALE)

                            // Zoom around the pinch centroid: keep the point
                            // under the fingers stationary. Centroid is relative
                            // to the box, so convert to a centre-origin vector.
                            // calculateCentroid() returns Unspecified when no
                            // pointer moved this frame — fall back to the box
                            // centre so focus becomes zero (pure zoom, no shift).
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val focus = if (centroid == Offset.Unspecified) {
                                Offset.Zero
                            } else {
                                centroid - centre
                            }
                            val scaleDelta = if (old != 0f) new / old else 1f
                            // New offset that preserves the focal point, plus pan.
                            val candidate = (offset - focus) * scaleDelta + focus + panChange

                            scope.launch { scale.snapTo(new) }
                            offset = clampOffset(candidate, new)

                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        // Single finger + not zoomed: consume nothing so the
                        // parent HorizontalPager receives the swipe.
                    } while (event.changes.any { it.pressed })
                }
            }
            // Double-tap detector on a SEPARATE pointerInput so it coexists
            // with the transform detector (CLAUDE.md §1 gotcha).
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        val target = if (scale.value > 1f + 0.001f) MIN_SCALE else 2f
                        if (target == MIN_SCALE) {
                            offset = Offset.Zero
                            scope.launch { scale.animateTo(MIN_SCALE) }
                        } else {
                            // Zoom to 2x centred on the tapped point.
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val focus = tap - centre
                            val scaleDelta = if (scale.value != 0f) target / scale.value else target
                            val candidate = (offset - focus) * scaleDelta + focus
                            scope.launch {
                                scale.animateTo(target)
                            }
                            offset = clampOffset(candidate, target)
                        }
                    },
                )
            },
    ) {
        // Capture container size in px for the fit/fill/original math. Guard
        // the state write so it fires only on an actual size change (avoids a
        // write-during-composition feedback loop when the size is stable).
        val density = LocalDensity.current
        val measured = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        if (measured != containerSize) {
            containerSize = measured
        }

        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                // Decode up to MAX_BITMAP_DIM (not the view size) so
                // intrinsicSize reports enough pixels for ORIGINAL (1:1)
                // mode. Coil's default would downsample to the view size,
                // making 1:1 meaningless; Size.ORIGINAL would OOM on large
                // camera photos (see MAX_BITMAP_DIM).
                .size(MAX_BITMAP_DIM)
                .build(),
            imageLoader = viewModel.imageLoader,
            contentDescription = fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            val state = painter.state
            when (state) {
                is AsyncImagePainter.State.Success -> {
                    // Record true pixel size once, for ORIGINAL/FILL math.
                    val s = state.painter.intrinsicSize
                    if (s.isUsable() && s != intrinsicSize) {
                        intrinsicSize = s
                    }
                    SubcomposeAsyncImageContent()
                }
                is AsyncImagePainter.State.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                is AsyncImagePainter.State.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Failed to load image",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is AsyncImagePainter.State.Empty -> Unit
            }
        }
    }
}

/** True when the size is specified and has usable (positive, finite) dimensions. */
private fun Size.isUsable(): Boolean =
    this != Size.Unspecified && width > 0f && height > 0f && !width.isNaN() && !height.isNaN()
