package io.synctuary.android.ui.preview

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
// ResizeMode accessed via PlayerView.resizeMode property (enum constants: FIT=0, ZOOM=1)
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val CONTROLS_TIMEOUT_MS = 4_000L
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
    onFullscreenChanged: (Boolean) -> Unit,
) {
    val fileName = remotePath.substringAfterLast('/')
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var isLocked by rememberSaveable { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showInfoPanel by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1f) }

    // Gesture feedback state
    var seekFeedback by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var overlayFeedback by remember { mutableStateOf<OverlayFeedback?>(null) }
    var doubleTapIndicator by remember { mutableStateOf<DoubleTapIndicator?>(null) }

    // Controls auto-hide timer
    fun resetControlsTimer() {
        scope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            if (!showSpeedDialog && !showInfoPanel && !isLocked) {
                controlsVisible = false
            }
        }
    }

    // Build ExoPlayer
    val contentUrl = videoPlayerVm.contentUrl(remotePath)
    val exoPlayer = remember { videoPlayerVm.buildPlayer(remotePath, contentUrl) }

    // Lifecycle: release player on dispose
    DisposableEffect(Unit) {
        onDispose { videoPlayerVm.onCleared() }
    }

    // Poll player state at ~10fps for progress updates
    LaunchedEffect(exoPlayer) {
        while (true) {
            videoPlayerVm.updateProgress()
            delay(100)
        }
    }

    // Sync controls visibility with system UI (only hides; MainActivity manages fullscreen behavior)
    LaunchedEffect(controlsVisible) {
        val window = activity?.window ?: return@LaunchedEffect
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (controlsVisible) {
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

    // Collect player state
    val state by videoPlayerVm.playerState.collectAsStateWithLifecycle()
    val loopState by videoPlayerVm.loopState.collectAsStateWithLifecycle()

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

    Scaffold(
        topBar = {
            AnimatedVisibility(visible = controlsVisible && !isLocked) {
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
                            onFullscreenChanged(isFullscreen)
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
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            // ExoPlayer video surface
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setKeepContentOnPlayerReset(true)
                        resizeMode = if (isFullscreen) androidx.media3.ui.PlayerView.ResizeMode.ZOOM else androidx.media3.ui.PlayerView.ResizeMode.FIT
                    }.also { it.player = exoPlayer }
                },
                update = { pv ->
                    pv.resizeMode = if (isFullscreen) androidx.media3.ui.PlayerView.ResizeMode.ZOOM else androidx.media3.ui.PlayerView.ResizeMode.FIT
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

            // Gesture feedback overlays
            seekFeedback?.let { (pos, dur) ->
                SeekFeedbackOverlay(currentPos = pos, duration = dur)
            }

            overlayFeedback?.let { feedback ->
                OverlayFeedbackView(feedback = feedback)
            }

            doubleTapIndicator?.let { indicator ->
                DoubleTapIndicatorView(indicator = indicator)
            }

            // Controls overlay (bottom)
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
                    onToggleLoop = { videoPlayerVm.toggleLoop(!loopState.enabled) },
                    onClearLoop = { videoPlayerVm.clearLoop() },
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

            // Gesture capture layer
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
                        var dragStartX by remember { mutableFloatStateOf(0f) }
                        var isDragging by remember { mutableStateOf(false) }
                        var isVertDragging by remember { mutableStateOf(false) }
                        var vertDragSide: GestureDragType? = null
                        val dur = { state.duration.coerceAtLeast(1L) }

                        detectDragGestures(
                            onStart = { offset ->
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
                            onDrag = { change, delta ->
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
        }
    }

    // Speed selection dialog
    if (showSpeedDialog) {
        SpeedSelectionDialog(
            currentSpeed = currentSpeed,
            onSpeedSelected = { speed ->
                val p = exoPlayer
                val old = p?.playbackParameters
                p?.playbackParameters = androidx.media3.common.PlaybackParameters(speed, old?.pitch ?: 0f)
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
// Seek Feedback Overlay — centered pill showing current time / duration
// ============================================================================

@Composable
private fun SeekFeedbackOverlay(currentPos: Long, duration: Long) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
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
                    text = formatTime(currentPos),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                )
                Text(text = " / ", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                Text(
                    text = formatTime(duration),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                )
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
    onToggleLoop: () -> Unit,
    onClearLoop: () -> Unit,
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
        // Progress bar with loop indicators
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatTime(currentPosition),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier.width(50.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                androidx.compose.material3.Slider(
                    value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                    onValueChange = { fraction ->
                        onSeek((fraction * duration).toLong())
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                // Loop range overlay
                if (loopState.enabled && loopState.pointB > loopState.pointA && duration > 0) {
                    val startFrac = loopState.pointA.toFloat() / duration
                    val endFrac = loopState.pointB.toFloat() / duration
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = (startFrac * 100).dp,
                                end = ((1f - endFrac) * 100).dp,
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        )
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
            // Left: lock + loop toggle
            Row {
                IconButton(onClick = onLockToggle) {
                    Icon(
                        if (isPlaying) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Lock",
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = {
                        if (loopState.enabled) {
                            onClearLoop()
                        } else {
                            onToggleLoop()
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.Loop,
                        contentDescription = "A-B Repeat",
                        tint = if (loopState.enabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    )
                }
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

            // Right: speed + loop point buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loopState.enabled && loopState.pointB > loopState.pointA) {
                    // Both points set — show active indicator, tap to clear
                    Text(
                        text = "AB",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                onClearLoop()
                            }
                            .padding(6.dp),
                    )
                } else if (loopState.enabled && loopState.pointA > 0) {
                    // A set — prompt to set B
                    Text(
                        text = "Set B",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                onSetLoopB()
                            }
                            .padding(6.dp),
                    )
                } else if (loopState.enabled) {
                    // Prompt to set A
                    Text(
                        text = "Set A",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                onSetLoopA()
                            }
                            .padding(6.dp),
                    )
                }
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

        // Loop info bar (when loop is active with both points)
        if (loopState.enabled && loopState.pointB > loopState.pointA) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                Text(
                    text = "Loop: ${formatTime(loopState.pointA)} — ${formatTime(loopState.pointB)}",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Clear",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            onClearLoop()
                        }
                        .padding(4.dp),
                )
            }
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
