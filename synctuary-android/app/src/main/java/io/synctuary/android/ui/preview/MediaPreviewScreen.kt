package io.synctuary.android.ui.preview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import io.synctuary.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CONTROLS_TIMEOUT_MS = 5_000L
private const val SEEK_DELTA_MS = 10_000L
private const val MIN_DRAG_DISTANCE_DP = 16

/**
 * MX Player-style video player with full gesture support.
 *
 * Features:
 * - Double-tap left/right: seek ±10s with animated feedback
 * - Single tap top/bottom: toggle controls visibility
 * - Horizontal swipe: precise seeking along progress bar
 * - Vertical swipe (left half): brightness adjustment
 * - Vertical swipe (right half): volume adjustment
 * - Long-press: playback speed selection dialog
 * - Fullscreen toggle with system UI immersion
 * - Lock mode to prevent accidental touches
 * - Video info panel (resolution, FPS, duration)
 * - Resume position from last playback session
 */

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MediaPreviewScreen(
    remotePath: String,
    viewModel: PreviewViewModel,
    videoPlayerVm: VideoPlayerViewModel,
    onBack: () -> Unit,
    onFullscreenChanged: (fullscreen: Boolean, videoWidth: Int, videoHeight: Int) -> Unit,
) {
    val fileName = remotePath.substringAfterLast('/')
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    // Start in fullscreen for video files (#6).
    var isFullscreen by rememberSaveable { mutableStateOf(true) }
    var isLocked by rememberSaveable { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showInfoPanel by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1f) }

    // Gesture feedback state
    var seekFeedback by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var overlayFeedback by remember { mutableStateOf<OverlayFeedback?>(null) }
    var doubleTapIndicator by remember { mutableStateOf<DoubleTapIndicator?>(null) }

    // Controls auto-hide timer — cancel previous before launching a new one
    // so rapid taps don't cause premature hide.
    var controlsTimerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun resetControlsTimer() {
        controlsTimerJob?.cancel()
        controlsTimerJob = scope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            if (!showSpeedDialog && !showInfoPanel && !isLocked) {
                controlsVisible = false
            }
        }
    }

    // Collect player state early so the player reference below can react to
    // transcode fallback (which swaps the ExoPlayer instance inside the VM).
    val state by videoPlayerVm.playerState.collectAsStateWithLifecycle()
    val loopState by videoPlayerVm.loopState.collectAsStateWithLifecycle()

    // Get or build ExoPlayer — survives config changes (orientation, fullscreen)
    // because the ViewModel holds the player across recompositions (#5).
    // Keyed on playerGeneration: the VM bumps it on EVERY player instance
    // swap (transcode fallback and transcode seek-by-restart), so we always
    // re-read the live instance and re-bind it to the PlayerView. Keying on
    // transcodeActive alone would miss seek restarts and leave the view
    // attached to a released player (frozen video).
    val contentUrl = videoPlayerVm.contentUrl(remotePath)
    val exoPlayer = remember(state.playerGeneration) {
        videoPlayerVm.getOrBuildPlayer(remotePath, contentUrl)
    }

    // Release player and restore orientation when leaving this screen.
    // Using DisposableEffect ensures this runs regardless of how the user
    // navigates away (back button, edge swipe gesture, tab switch, etc.).
    DisposableEffect(Unit) {
        onDispose {
            videoPlayerVm.releasePlayer()
            activity?.let { act ->
                act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val window = act.window
                val ic = WindowCompat.getInsetsController(window, window.decorView)
                ic.show(WindowInsetsCompat.Type.systemBars())
                ic.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
    }

    // Trigger fullscreen on first composition so the activity matches (#6).
    LaunchedEffect(Unit) {
        onFullscreenChanged(true, 0, 0)
    }

    // Poll player state at ~10fps for progress updates
    LaunchedEffect(exoPlayer) {
        while (true) {
            videoPlayerVm.updateProgress()
            delay(100)
        }
    }

    // Sync controls visibility with system UI.
    // In fullscreen mode, system bars stay hidden regardless of controls visibility
    // (the controls overlay draws on top of the video, not in system bar space).
    LaunchedEffect(controlsVisible, isFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            // Always keep system bars hidden in fullscreen, even when controls are visible.
            insets.hide(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else if (controlsVisible) {
            insets.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Save resume position on back
    LaunchedEffect(Unit) {
        // Initial speed from player
        currentSpeed = videoPlayerVm.getCurrentSpeed()
    }

    // Update orientation when actual video dimensions become available.
    // The initial fullscreen call uses 0×0 (defaults to landscape);
    // once ExoPlayer reports the real size, re-orient for portrait videos.
    LaunchedEffect(state.videoWidth, state.videoHeight) {
        if (isFullscreen && state.videoWidth > 0 && state.videoHeight > 0) {
            onFullscreenChanged(true, state.videoWidth, state.videoHeight)
        }
    }

    // Enforce A-B loop boundary on each poll cycle
    LaunchedEffect(exoPlayer) {
        while (true) {
            val seekTo = videoPlayerVm.checkLoopBoundary()
            if (seekTo != null) {
                exoPlayer.seekTo(seekTo)
            }
            delay(100)
        }
    }

    // Use a plain Box instead of Scaffold so the TopAppBar overlays on
    // the video without reserving layout space (which caused a black bar
    // at the top when controls appeared in fullscreen).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
            // ExoPlayer video surface
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setKeepContentOnPlayerReset(true)
                        resizeMode = if (isFullscreen) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }.also { it.player = exoPlayer }
                },
                update = { pv ->
                    pv.resizeMode = if (isFullscreen) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // Re-bind on transcode fallback: the VM swaps the ExoPlayer
                    // instance, and `exoPlayer` here is keyed on transcodeActive.
                    if (pv.player !== exoPlayer) {
                        pv.player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Loading indicator (before video is ready)
            if (!state.isReady && state.error == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                }
            }

            // Error overlay
            state.error?.let { error ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Cancel, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Playback error: $error", color = Color.White)
                    }
                }
            }

            // Gesture capture layer — must be BEFORE controls so that
            // BottomControls (seek bar, buttons) receive touch priority.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        with(density) {
                            val screenWidth = size.width
                            val midX = screenWidth / 2f

                            detectTapGestures(
                                onDoubleTap = { tapOffset ->
                                    val side = if (tapOffset.x < midX) DoubleTapSide.LEFT else DoubleTapSide.RIGHT
                                    doubleTapIndicator = DoubleTapIndicator(side = side, isPlay = side == DoubleTapSide.CENTER)
                                    videoPlayerVm.seekRelative(
                                        if (side == DoubleTapSide.LEFT) -SEEK_DELTA_MS else SEEK_DELTA_MS
                                    )
                                    resetControlsTimer()
                                    // Clear indicator after animation
                                    scope.launch {
                                        delay(500L)
                                        doubleTapIndicator = null
                                    }
                                    true
                                },
                                onTap = { tapOffset ->
                                    if (!isLocked) {
                                        controlsVisible = !controlsVisible
                                        resetControlsTimer()
                                    }
                                },
                                onLongPress = {
                                    if (!isLocked) {
                                        showSpeedDialog = true
                                    }
                                },
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        var dragStartX = 0f
                        var isDragging = false
                        var isVertDragging = false
                        var vertDragSide: GestureDragType? = null
                        val dur = { state.duration.coerceAtLeast(1L) }

                        detectDragGestures(
                            onDragStart = { offset: Offset ->
                                dragStartX = offset.x
                                val midX = size.width / 2f
                                vertDragSide = if (offset.x < midX) GestureDragType.BRIGHTNESS else GestureDragType.VOLUME
                            },
                            onDragCancel = {
                                isDragging = false
                                isVertDragging = false
                            },
                            onDragEnd = {
                                if (isDragging) {
                                    val finalPos = seekFeedback?.first ?: exoPlayer.currentPosition
                                    videoPlayerVm.seekTo(finalPos)
                                    isDragging = false
                                    scope.launch {
                                        delay(800L)
                                        seekFeedback = null
                                    }
                                }
                                if (isVertDragging) {
                                    isVertDragging = false
                                    scope.launch {
                                        delay(1000L)
                                        overlayFeedback = null
                                    }
                                }
                            },
                            onDrag = { _change: androidx.compose.ui.input.pointer.PointerInputChange, delta: Offset ->
                                val absDx = kotlin.math.abs(delta.x)
                                val absDy = kotlin.math.abs(delta.y)

                                if (absDx > MIN_DRAG_DISTANCE_DP.dp.toPx() && absDx > absDy) {
                                    if (!isDragging && !isVertDragging) {
                                        isDragging = true
                                        controlsVisible = true
                                    }
                                    if (isDragging) {
                                        val startPos = dragStartX
                                        val msPerPx = dur().toFloat() / size.width
                                        val basePos = (exoPlayer.currentPosition - startPos * msPerPx).toLong()
                                        val newPos = (basePos + delta.x * msPerPx).toLong().coerceIn(0L, dur())
                                        seekFeedback = Pair(newPos, dur())
                                    }
                                }

                                if (absDy > MIN_DRAG_DISTANCE_DP.dp.toPx() && absDy > absDx) {
                                    if (!isVertDragging && !isDragging) {
                                        isVertDragging = true
                                        controlsVisible = true
                                    }
                                    if (isVertDragging) {
                                        val sensitivity = 0.003f
                                        val changeVal = (-delta.y) * sensitivity

                                        when (vertDragSide) {
                                            GestureDragType.BRIGHTNESS -> {
                                                activity?.window?.let { window ->
                                                    val attrs = window.attributes
                                                    val currentBrightness = attrs.screenBrightness.coerceIn(-0.1f, 1f)
                                                    val newBrightness = (currentBrightness + changeVal).coerceIn(-0.1f, 1f)
                                                    attrs.screenBrightness = newBrightness
                                                    window.attributes = attrs

                                                    overlayFeedback = OverlayFeedback(
                                                        type = GestureDragType.BRIGHTNESS,
                                                        value = when {
                                                            newBrightness < 0 -> -1f
                                                            else -> newBrightness
                                                        },
                                                    )
                                                }
                                            }
                                            GestureDragType.VOLUME -> {
                                                val am = context.getSystemService(
                                                    android.content.Context.AUDIO_SERVICE
                                                ) as android.media.AudioManager
                                                val maxVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                                val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                                val newVol = (currentVol + changeVal * maxVol).coerceIn(0f, maxVol.toFloat())
                                                overlayFeedback = OverlayFeedback(
                                                    type = GestureDragType.VOLUME,
                                                    value = newVol / maxVol,
                                                )
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                            },
                        )
                    },
            )

            // Gesture feedback overlays (visual only, no touch needed)
            seekFeedback?.let { (pos, dur) ->
                SeekFeedbackOverlay(
                    currentPos = pos,
                    duration = dur,
                    seekPreview = { seconds -> viewModel.thumbnailUrl(remotePath, size = 320, timeSeconds = seconds) },
                    imageLoader = viewModel.imageLoader,
                )
            }

            overlayFeedback?.let { feedback ->
                OverlayFeedbackView(feedback = feedback)
            }

            doubleTapIndicator?.let { indicator ->
                DoubleTapIndicatorView(indicator = indicator)
            }

            // Controls overlay (bottom) — drawn after gesture layer so
            // the seek bar and buttons receive touch events.
            AnimatedVisibility(
                visible = controlsVisible && !isLocked,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomControls(
                    duration = state.duration,
                    currentPosition = state.currentPosition,
                    isPlaying = state.isPlaying,
                    onSeek = { videoPlayerVm.seekTo(it) },
                    onPlayPause = {
                        videoPlayerVm.togglePlayPause()
                        resetControlsTimer()
                    },
                    onSpeedClick = { showSpeedDialog = true },
                    onLockToggle = { isLocked = !isLocked },
                    currentSpeed = currentSpeed,
                    loopState = loopState,
                    onFrameStepForward = { videoPlayerVm.frameStepForward() },
                    onFrameStepBackward = { videoPlayerVm.frameStepBackward() },
                    onSetLoopA = { videoPlayerVm.setLoopPointA(state.currentPosition) },
                    onSetLoopB = { videoPlayerVm.setLoopPointB(state.currentPosition) },
                    onClearLoop = { videoPlayerVm.clearLoop() },
                    seekPreview = { seconds -> viewModel.thumbnailUrl(remotePath, size = 320, timeSeconds = seconds) },
                    previewImageLoader = viewModel.imageLoader,
                )
            }

            // Top bar overlay — drawn on top of the video, not in Scaffold layout
            AnimatedVisibility(
                visible = controlsVisible && !isLocked,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = fileName,
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
                        IconButton(onClick = { showInfoPanel = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Video info")
                        }
                        IconButton(onClick = {
                            isFullscreen = !isFullscreen
                            onFullscreenChanged(isFullscreen, state.videoWidth, state.videoHeight)
                        }) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                )
            }

            // Lock mode indicator (top-right)
            AnimatedVisibility(
                visible = isLocked && controlsVisible,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Card(
                    modifier = Modifier
                        .padding(16.dp)
                        .clickable { isLocked = false },
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Unlock",
                        tint = Color.White,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            // Transcode indicator — shown when the file couldn't be played
            // directly and playback fell back to server-side transcoding.
            AnimatedVisibility(
                visible = state.transcodeActive && controlsVisible && !isLocked,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp),
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.transcode_active),
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

    // Speed selection dialog
    if (showSpeedDialog) {
        SpeedSelectionDialog(
            currentSpeed = currentSpeed,
            onSpeedSelected = { speed ->
                val p = exoPlayer
                val old = p?.playbackParameters
                p?.playbackParameters = androidx.media3.common.PlaybackParameters(speed, old?.pitch ?: 1f)
                currentSpeed = speed
                showSpeedDialog = false
            },
            onDismiss = { showSpeedDialog = false },
        )
    }

    // Video info panel
    if (showInfoPanel) {
        VideoInfoDialog(
            fileName = fileName,
            width = state.videoWidth,
            height = state.videoHeight,
            fps = state.framesPerSecond,
            durationMs = state.duration,
            onDismiss = { showInfoPanel = false },
        )
    }
}

// ============================================================================
// Data classes for gesture feedback
// ============================================================================

enum class DoubleTapSide { LEFT, CENTER, RIGHT }

enum class GestureDragType { BRIGHTNESS, VOLUME, SEEK }

data class OverlayFeedback(
    val type: GestureDragType,
    val value: Float,
)

data class DoubleTapIndicator(
    val side: DoubleTapSide,
    val isPlay: Boolean,
)

// ============================================================================
// Seek-preview thumbnails (YouTube-style scrubbing)
// ============================================================================

private const val PREVIEW_W_DP = 160
private const val PREVIEW_H_DP = 90
private const val PREVIEW_THUMB_SIZE = 320

// bucketPreviewSeconds stabilizes the requested timestamp so back-and-forth
// scrubbing reuses the same URLs (and therefore Coil's memory/disk cache)
// instead of hammering the server with one request per pixel of drag. The
// bucket widens with duration so a 3-hour movie doesn't generate thousands
// of distinct 1-second frames.
private fun bucketPreviewSeconds(targetMs: Long, durationMs: Long): Long {
    val targetSec = targetMs / 1000
    val durationSec = durationMs / 1000
    val bucket = (durationSec / 100).coerceAtLeast(2)
    return (targetSec / bucket) * bucket
}

// SeekPreviewBubble draws a rounded thumbnail of the target frame above the
// slider thumb, tracking it horizontally (clamped so it stays on-screen),
// with the target time (hh:mm:ss) overlaid INSIDE the image, bottom-center.
// The time must live inside the image box: a label below it lands in the
// slider row area, where the Slider (emitted later, thus drawn on top)
// covers it — that was a real bug reported from device testing.
@Composable
private fun SeekPreviewBubble(
    fraction: Float,
    targetMs: Long,
    durationMs: Long,
    rowWidthPx: Int,
    seekPreview: (seconds: Long) -> String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val previewSec = bucketPreviewSeconds(targetMs, durationMs)
    val bubbleWidthPx = with(density) { PREVIEW_W_DP.dp.toPx() }

    // Center the bubble on the thumb, then clamp so it never overflows the
    // slider row horizontally.
    val thumbXPx = fraction.coerceIn(0f, 1f) * rowWidthPx
    val maxOffset = (rowWidthPx - bubbleWidthPx).coerceAtLeast(0f)
    val offsetXPx = (thumbXPx - bubbleWidthPx / 2f).coerceIn(0f, maxOffset)
    val offsetXDp = with(density) { offsetXPx.toDp() }

    Box(
        modifier = modifier
            .offset(x = offsetXDp, y = (-(PREVIEW_H_DP + 12)).dp)
            .width(PREVIEW_W_DP.dp)
            .height(PREVIEW_H_DP.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(seekPreview(previewSec))
                // Instant swap while scrubbing — crossfade lags behind
                // rapid drags and looks worse than a hard cut.
                .crossfade(false)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = formatTimeHms(targetMs),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ============================================================================
// Seek Feedback Overlay — centered pill showing current time / duration
// ============================================================================

@Composable
private fun SeekFeedbackOverlay(
    currentPos: Long,
    duration: Long,
    seekPreview: ((seconds: Long) -> String)? = null,
    imageLoader: ImageLoader? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Thumbnail of the target frame above the time pill. Uses the
            // same bucketing as the slider bubble so scrubbing back and
            // forth reuses cached URLs.
            if (seekPreview != null && imageLoader != null && duration > 0) {
                val previewSec = bucketPreviewSeconds(currentPos, duration)
                Box(
                    modifier = Modifier
                        .width(PREVIEW_W_DP.dp)
                        .height(PREVIEW_H_DP.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(seekPreview(previewSec))
                            .crossfade(false)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = formatTimeHms(currentPos),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                    )
                    Text(text = " / ", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                    Text(
                        text = formatTimeHms(duration),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

// ============================================================================
// Brightness / Volume Overlay — split-screen with icon + progress bar
// ============================================================================

@Composable
private fun OverlayFeedbackView(feedback: OverlayFeedback) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Dimmed side
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.33f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.4f)),
            )
            // Content side
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.67f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = when (feedback.type) {
                            GestureDragType.BRIGHTNESS -> Icons.Default.BrightnessHigh
                            GestureDragType.VOLUME -> Icons.Default.VolumeUp
                            else -> Icons.Default.BrightnessHigh
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = feedback.value.coerceIn(0f, 1f),
                        modifier = Modifier
                            .width(60.dp)
                            .height(4.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}

// ============================================================================
// Double-tap indicator — arrow icons that flash on double-tap seek
// ============================================================================

@Composable
private fun DoubleTapIndicatorView(indicator: DoubleTapIndicator) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = when (indicator.side) {
            DoubleTapSide.LEFT -> Alignment.CenterStart
            DoubleTapSide.CENTER -> Alignment.Center
            DoubleTapSide.RIGHT -> Alignment.CenterEnd
        },
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.3f)),
            shape = CircleShape,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = when (indicator.side) {
                    DoubleTapSide.LEFT -> Icons.Default.ChevronLeft
                    DoubleTapSide.CENTER -> if (indicator.isPlay) Icons.Default.PlayArrow else Icons.Default.Pause
                    DoubleTapSide.RIGHT -> Icons.Default.ChevronRight
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// ============================================================================
// Bottom Controls — progress bar, play/pause, speed, lock
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControls(
    duration: Long,
    currentPosition: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSpeedClick: () -> Unit,
    onLockToggle: () -> Unit,
    currentSpeed: Float,
    loopState: ABLoopState,
    onFrameStepForward: () -> Unit,
    onFrameStepBackward: () -> Unit,
    onSetLoopA: () -> Unit,
    onSetLoopB: () -> Unit,
    onClearLoop: () -> Unit,
    // seekPreview maps a target position (whole seconds) to a thumbnail
    // URL for the scrubbing preview bubble. null disables the feature
    // (keeps BottomControls independent of PreviewViewModel for testing).
    seekPreview: ((seconds: Long) -> String)? = null,
    previewImageLoader: ImageLoader? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color.Black.copy(alpha = 0.5f),
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Jump-to-time: tapping the current-position label opens a dialog
        // accepting hh:mm:ss / mm:ss / seconds. Only when duration is known
        // (same condition as the slider).
        var showJumpDialog by remember { mutableStateOf(false) }
        if (showJumpDialog) {
            JumpToTimeDialog(
                durationMs = duration,
                onJump = { targetMs ->
                    onSeek(targetMs)
                    showJumpDialog = false
                },
                onDismiss = { showJumpDialog = false },
            )
        }

        // Progress bar with A/B marker lines
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatTime(currentPosition),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier
                    .width(50.dp)
                    .clickable(enabled = duration > 0) { showJumpDialog = true },
            )
            var sliderRowWidthPx by remember { mutableIntStateOf(0) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .onGloballyPositioned { sliderRowWidthPx = it.size.width },
            ) {
                // Seek only on drag RELEASE, not continuously. Per-tick
                // seeking spammed player.seekTo in direct mode and, in
                // transcode mode, restarted the whole ffmpeg stream on
                // every drag movement. While dragging, dragFraction drives
                // the thumb locally; the single onSeek fires on release.
                // duration <= 0 (unknown, e.g. C.TIME_UNSET during a WMV
                // transcode) disables seeking entirely — fraction * TIME_UNSET
                // used to produce a negative target that clamped to 0 and
                // sent playback back to the start.
                var dragFraction by remember { mutableStateOf<Float?>(null) }

                // Seek-preview bubble: while dragging, show a thumbnail of the
                // target frame above the thumb, tracking it horizontally.
                if (seekPreview != null && previewImageLoader != null) {
                    dragFraction?.let { fraction ->
                        if (duration > 0) {
                            SeekPreviewBubble(
                                fraction = fraction,
                                targetMs = (fraction * duration).toLong(),
                                durationMs = duration,
                                rowWidthPx = sliderRowWidthPx,
                                seekPreview = seekPreview,
                                imageLoader = previewImageLoader,
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                        }
                    }
                }
                androidx.compose.material3.Slider(
                    value = dragFraction
                        ?: if (duration > 0) currentPosition.toFloat() / duration else 0f,
                    onValueChange = { fraction ->
                        if (duration > 0) dragFraction = fraction
                    },
                    onValueChangeFinished = {
                        dragFraction?.let { fraction ->
                            onSeek((fraction * duration).toLong())
                        }
                        dragFraction = null
                    },
                    enabled = duration > 0,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        disabledThumbColor = Color.White.copy(alpha = 0.4f),
                        disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    ),
                )
                // A/B marker lines drawn on top of the slider track
                if (loopState.enabled && duration > 0) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val thumbPad = 10.dp.toPx()
                        val trackStart = thumbPad
                        val trackWidth = size.width - thumbPad * 2
                        fun markerX(pos: Long) = trackStart + trackWidth * (pos.toFloat() / duration).coerceIn(0f, 1f)

                        if (loopState.pointA > 0) {
                            val x = markerX(loopState.pointA)
                            drawLine(Color(0xFF4CAF50), androidx.compose.ui.geometry.Offset(x, size.height * 0.1f), androidx.compose.ui.geometry.Offset(x, size.height * 0.9f), strokeWidth = 3.dp.toPx())
                        }
                        if (loopState.pointB > 0) {
                            val x = markerX(loopState.pointB)
                            drawLine(Color(0xFFEF5350), androidx.compose.ui.geometry.Offset(x, size.height * 0.1f), androidx.compose.ui.geometry.Offset(x, size.height * 0.9f), strokeWidth = 3.dp.toPx())
                        }
                    }
                }
            }
            Text(
                text = formatTime(duration),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier.width(50.dp),
            )
        }

        // Control buttons row
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
        ) {
            // Left: lock
            IconButton(onClick = onLockToggle) {
                Icon(
                    if (isPlaying) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Lock",
                    tint = Color.White,
                )
            }

            // Center: frame step backward + play/pause + frame step forward
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onFrameStepBackward, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Frame back",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                IconButton(onClick = onFrameStepForward, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Frame forward",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Right: A-B repeat button + speed
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A-B repeat: tap cycles idle → A set → looping → idle
                val abColor = when {
                    loopState.enabled && loopState.pointB > loopState.pointA -> MaterialTheme.colorScheme.primary
                    loopState.enabled && loopState.pointA > 0 -> Color(0xFF4CAF50)
                    else -> Color.White.copy(alpha = 0.4f)
                }
                val abLabel = when {
                    loopState.enabled && loopState.pointB > loopState.pointA -> "A↔B"
                    loopState.enabled && loopState.pointA > 0 -> "A · B"
                    else -> "A-B"
                }
                Text(
                    text = abLabel,
                    color = abColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            when {
                                !loopState.enabled -> onSetLoopA()
                                loopState.enabled && (loopState.pointB == 0L || loopState.pointB <= loopState.pointA) -> onSetLoopB()
                                else -> onClearLoop()
                            }
                        }
                        .padding(8.dp),
                )
                Text(
                    text = "${currentSpeed}x",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            onSpeedClick()
                        }
                        .padding(8.dp),
                )
            }
        }

        // Loop info bar (when active)
        if (loopState.enabled && loopState.pointB > loopState.pointA) {
            Text(
                text = "${formatTime(loopState.pointA)} → ${formatTime(loopState.pointB)}",
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ============================================================================
// Speed Selection Dialog — bottom sheet with speed options
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSelectionDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Playback Speed",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            DEFAULT_SPEEDS.forEach { speed ->
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSpeedSelected(speed) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        text = "${speed}x",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (abs(speed - currentSpeed) < 0.01) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Text(
                text = "Dismiss",
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { onDismiss() }
                    .padding(vertical = 16.dp),
            )
        }
    }
}

// ============================================================================
// Video Info Dialog — shows resolution, FPS, duration
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoInfoDialog(
    fileName: String,
    width: Int,
    height: Int,
    fps: Float,
    durationMs: Long,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1E1E),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Video Information",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            InfoRow(label = "File", value = fileName)
            if (width > 0 && height > 0) {
                InfoRow(label = "Resolution", value = "${width} × ${height}")
            }
            if (fps > 0.5f) {
                InfoRow(label = "Frame rate", value = String.format("%.1f fps", fps))
            }
            if (durationMs > 0 && durationMs != C.TIME_UNSET) {
                InfoRow(label = "Duration", value = formatTime(durationMs))
            }

            Text(
                text = "Close",
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { onDismiss() }
                    .padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        Text(text = value, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ============================================================================
// Time formatting utility
// ============================================================================

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, min, sec)
    } else {
        String.format("%02d:%02d", min, sec)
    }
}

// ============================================================================
// Jump-to-time dialog — opened by tapping the current-position label
// ============================================================================

@Composable
private fun JumpToTimeDialog(
    durationMs: Long,
    onJump: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    fun tryJump() {
        val ms = parseTimeInput(input)
        if (ms == null || (durationMs > 0 && ms > durationMs)) {
            invalid = true
            return
        }
        onJump(ms)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_jump_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalid = false
                    },
                    singleLine = true,
                    placeholder = { Text("hh:mm:ss") },
                    isError = invalid,
                )
                if (invalid) {
                    Text(
                        text = stringResource(R.string.player_jump_invalid),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { tryJump() }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/**
 * Always-zero-padded hh:mm:ss, used where the user asked to see the full
 * clock position while scrubbing (seek-preview bubble, gesture overlay,
 * jump-to-time dialog). [formatTime] stays compact for the bar labels.
 */
internal fun formatTimeHms(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    val hours = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d:%02d", hours, min, sec)
}

/**
 * Parses user time input for the jump-to-time dialog into milliseconds.
 * Accepts "hh:mm:ss", "mm:ss", or plain seconds. Returns null for
 * malformed input (non-numeric, negative, >2 separators, or minute/second
 * fields >= 60 when a higher field is present).
 */
internal fun parseTimeInput(text: String): Long? {
    val parts = text.trim().split(":")
    if (parts.isEmpty() || parts.size > 3 || parts.any { it.isBlank() }) return null
    val nums = parts.map { it.trim().toLongOrNull() ?: return null }
    if (nums.any { it < 0 }) return null
    val totalSec = when (nums.size) {
        1 -> nums[0]
        2 -> {
            if (nums[1] > 59) return null
            nums[0] * 60 + nums[1]
        }
        else -> {
            if (nums[1] > 59 || nums[2] > 59) return null
            nums[0] * 3600 + nums[1] * 60 + nums[2]
        }
    }
    return totalSec * 1000
}
