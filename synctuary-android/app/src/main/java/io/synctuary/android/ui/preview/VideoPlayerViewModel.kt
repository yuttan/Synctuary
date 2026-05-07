package io.synctuary.android.ui.preview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.abs
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing ExoPlayer lifecycle, playback state, and resume positions.
 *
 * Responsibilities:
 * - Create/destroy ExoPlayer with authenticated OkHttp data source
 * - Track and restore resume positions per file path (in-memory)
 * - Expose player state as StateFlow for Compose UI consumption
 * - Manage playback speed toggling cycle
 */

data class PlayerState(
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val bufferPercent: Int = 0,
    val duration: Long = C.TIME_UNSET,
    val currentPosition: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val framesPerSecond: Float = 0f,
    val error: String? = null,
)

data class ABLoopState(
    val enabled: Boolean = false,
    val pointA: Long = 0L,
    val pointB: Long = 0L,
)

val DEFAULT_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = io.synctuary.android.data.secret.SecretStore.create(application)

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _loopState = MutableStateFlow(ABLoopState())
    val loopState: StateFlow<ABLoopState> = _loopState.asStateFlow()

    var exoPlayer: ExoPlayer? = null

    // In-memory resume position cache: path -> milliseconds
    private val resumePositions = mutableMapOf<String, Long>()

    private var currentPath: String = ""

    fun buildPlayer(path: String, contentUrl: String): ExoPlayer {
        currentPath = path
        val httpFactory = OkHttpDataSource.Factory(authenticatedClient)
        val player = ExoPlayer.Builder(application)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()

        player.setMediaItem(MediaItem.fromUri(contentUrl))
        player.prepare()
        player.playWhenReady = true

        // Restore resume position
        val resumePos = resumePositions[path] ?: 0L
        if (resumePos > 0 && player.duration > resumePos) {
            player.seekTo(resumePos)
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playerState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playerState.update { it.copy(isReady = playbackState != Player.STATE_IDLE, error = null) }
                if (playbackState == Player.STATE_ENDED) {
                    _playerState.update { it.copy(isPlaying = false) }
                    return
                }
                _playerState.update { it.copy(duration = player.duration, currentPosition = player.currentPosition) }
                if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                    _playerState.update {
                        it.copy(bufferPercent = player.bufferedPosition.coerceAtMost(player.duration).let { buf ->
                            if (player.duration > 0) (buf.toFloat() / player.duration * 100).toInt() else 0
                        })
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _playerState.update {
                    it.copy(
                        videoWidth = videoSize.width,
                        videoHeight = videoSize.height,
                        framesPerSecond = 0f,
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _playerState.update { it.copy(error = error.errorCodeName) }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                @Player.DiscontinuityReason reason: Int,
            ) {
                // Save resume position on user-initiated seek
                if (reason == Player.DISCONTINUITY_REASON_USER_SEEK) {
                    saveResumePosition(newPosition.positionMs)
                }
            }
        })

        exoPlayer = player
        return player
    }

    fun updateProgress() {
        val p = exoPlayer ?: return
        _playerState.update {
            it.copy(
                isPlaying = p.isPlaying,
                duration = if (p.duration > 0) p.duration else C.TIME_UNSET,
                currentPosition = p.currentPosition,
            )
        }
    }

    fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    fun seekRelative(deltaMs: Long) {
        val p = exoPlayer ?: return
        val newPos = (p.currentPosition + deltaMs).coerceIn(0L, p.duration)
        p.seekTo(newPos)
    }

    fun cycleSpeed(): Float {
        val p = exoPlayer ?: return 1f
        val current = p.playbackParameters.speed
        val currentIndex = DEFAULT_SPEEDS.indexOfFirst { abs(it - current) < 0.01 }.let { idx ->
            if (idx < 0) 2 // default to index 2 (1x)
        }
        val nextIndex = (currentIndex + 1) % DEFAULT_SPEEDS.size
        val newSpeed = DEFAULT_SPEEDS[nextIndex]
        val old = p.playbackParameters
        p.playbackParameters = androidx.media3.common.PlaybackParameters(newSpeed, old.pitch)
        return newSpeed
    }

    fun getCurrentSpeed(): Float {
        return exoPlayer?.playbackParameters?.speed ?: 1f
    }

    fun saveResumePosition(position: Long) {
        if (currentPath.isNotEmpty() && position > 30_000) {
            resumePositions[currentPath] = position
        }
    }

    fun clearError() {
        _playerState.update { it.copy(error = null) }
    }

    // ---- A-B Repeat ----

    fun setLoopPointA(position: Long) {
        _loopState.value = ABLoopState(
            enabled = true,
            pointA = position,
            pointB = _loopState.value.pointB,
        )
    }

    fun setLoopPointB(position: Long) {
        _loopState.value = ABLoopState(
            enabled = true,
            pointA = _loopState.value.pointA,
            pointB = position,
        )
    }

    fun toggleLoop(enable: Boolean) {
        _loopState.value = _loopState.value.copy(enabled = enable)
    }

    fun clearLoop() {
        _loopState.value = ABLoopState()
    }

    /** Called from UI each frame to enforce loop boundary. */
    fun checkLoopBoundary(): Long? {
        val loop = _loopState.value
        if (!loop.enabled || loop.pointB == 0L) return null
        val p = exoPlayer ?: return null
        if (p.currentPosition >= loop.pointB && p.isPlaying) {
            return loop.pointA
        }
        return null
    }

    // ---- Frame Step ----

    /** Seek forward by one frame (~33ms at 30fps, or use actual FPS). */
    fun frameStepForward() {
        val p = exoPlayer ?: return
        val ms = frameDurationMs().coerceIn(16L, 200L)
        val newPos = (p.currentPosition + ms).coerceIn(0L, p.duration)
        p.seekTo(newPos)
    }

    /** Seek backward by one frame. */
    fun frameStepBackward() {
        val p = exoPlayer ?: return
        val ms = frameDurationMs().coerceIn(16L, 200L)
        val newPos = (p.currentPosition - ms).coerceIn(0L, p.duration)
        p.seekTo(newPos)
    }

    private fun frameDurationMs(): Long {
        val fps = _playerState.value.framesPerSecond
        return if (fps > 1f) (1000f / fps).toLong() else 33L
    }

    public override fun onCleared() {
        // Save final position before release
        exoPlayer?.let { p ->
            if (p.currentPosition > 30_000) {
                resumePositions[currentPath] = p.currentPosition
            }
        }
        exoPlayer?.release()
        exoPlayer = null
    }

    private val authenticatedClient by lazy {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        io.synctuary.android.data.api.NetworkModule.createOkHttpClient(
            paired.serverUrl,
            paired.serverFingerprint,
            io.synctuary.android.data.api.AuthInterceptor(secretStore),
        )
    }

    fun contentUrl(remotePath: String): String {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        val base = paired.serverUrl.trimEnd('/')
        return "$base/api/v1/files/content?path=${android.net.Uri.encode(remotePath)}"
    }
}
