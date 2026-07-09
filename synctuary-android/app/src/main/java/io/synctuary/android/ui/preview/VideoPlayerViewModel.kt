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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

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
    // True once we have fallen back to server-side transcode playback for
    // this file (the source container/codec was unplayable directly).
    val transcodeActive: Boolean = false,
    // Bumped every time the VM swaps the underlying ExoPlayer instance
    // (transcode fallback AND transcode seek-by-restart). The UI keys its
    // remembered player reference on this so the PlayerView re-binds to
    // the live instance — keying on transcodeActive alone misses seek
    // restarts, leaving the view attached to a released player.
    val playerGeneration: Int = 0,
)

data class ABLoopState(
    val enabled: Boolean = false,
    val pointA: Long = 0L,
    val pointB: Long = 0L,
)

val DEFAULT_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

// ---- Resume persistence tuning ----
// Positions under 30s aren't worth resuming; positions within 5s of the
// end mean the user effectively finished — clear so re-opening starts at 0.
private const val RESUME_MIN_MS = 30_000L
private const val RESUME_END_MARGIN_MS = 5_000L

// Cap the persisted resume entries; oldest (by write timestamp) are pruned.
private const val RESUME_MAX_ENTRIES = 200

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = io.synctuary.android.data.secret.SecretStore.create(application)

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _loopState = MutableStateFlow(ABLoopState())
    val loopState: StateFlow<ABLoopState> = _loopState.asStateFlow()

    var exoPlayer: ExoPlayer? = null

    // In-memory resume position cache: share-aware key -> milliseconds.
    // Backed by a plain (NOT encrypted) SharedPreferences file so resume
    // survives process death; entries are read through lazily and pruned
    // to RESUME_MAX_ENTRIES by write timestamp.
    private val resumePositions = mutableMapOf<String, Long>()

    private val resumePrefs by lazy {
        getApplication<Application>()
            .getSharedPreferences("resume_positions", android.content.Context.MODE_PRIVATE)
    }

    // Paths are share-relative: the same "/movies/a.avi" can exist in two
    // shares, so the persistence key includes the share id.
    private fun resumeKey(path: String) = "${currentShareId ?: "default"}|$path"

    private fun loadResumePosition(path: String): Long {
        val key = resumeKey(path)
        resumePositions[key]?.let { return it }
        val v = resumePrefs.getLong(key, 0L)
        if (v > 0L) resumePositions[key] = v
        return v
    }

    private fun persistResumePosition(path: String, positionMs: Long) {
        val key = resumeKey(path)
        resumePositions[key] = positionMs
        resumePrefs.edit()
            .putLong(key, positionMs)
            .putLong("$key#ts", System.currentTimeMillis())
            .apply()
        pruneResumePositions()
    }

    private fun clearResumePosition(path: String) {
        val key = resumeKey(path)
        resumePositions.remove(key)
        resumePrefs.edit().remove(key).remove("$key#ts").apply()
    }

    private fun pruneResumePositions() {
        val all = resumePrefs.all
        val tsKeys = all.keys.filter { it.endsWith("#ts") }
        if (tsKeys.size <= RESUME_MAX_ENTRIES) return
        val oldestFirst = tsKeys.sortedBy { (all[it] as? Long) ?: 0L }
        val editor = resumePrefs.edit()
        for (tsKey in oldestFirst.take(tsKeys.size - RESUME_MAX_ENTRIES)) {
            editor.remove(tsKey).remove(tsKey.removeSuffix("#ts"))
        }
        editor.apply()
    }

    private var currentPath: String = ""

    // ---- Transcode fallback state ----
    // When the direct stream can't be decoded (legacy container/codec), we
    // rebuild the player against the server's /transcode endpoint. That
    // stream is a non-seekable progressive fMP4, so seeking is implemented
    // by restarting the stream at a new `start` offset; the displayed
    // position is transcodeBaseOffsetMs + player.currentPosition.
    var transcodeActive: Boolean = false
        private set

    var transcodeBaseOffsetMs: Long = 0L
        private set

    // Best-known duration for the file, learned from the failed direct
    // attempt if it managed to read metadata before erroring. C.TIME_UNSET
    // when unknown (then the transcode seek bar is hidden).
    private var knownDurationMs: Long = C.TIME_UNSET

    // Mirrors PlayerState.playerGeneration; incremented on every player
    // instance swap so the UI re-binds.
    private var playerGeneration: Int = 0

    /**
     * Return the existing player if it's already playing this path,
     * otherwise create a new one. This prevents player recreation on
     * config changes (orientation, fullscreen toggle).
     */
    fun getOrBuildPlayer(path: String, contentUrl: String): ExoPlayer {
        val existing = exoPlayer
        if (existing != null && currentPath == path) {
            return existing
        }
        return buildPlayer(path, contentUrl)
    }

    fun buildPlayer(path: String, contentUrl: String): ExoPlayer {
        // Release previous player if switching paths. Persist the outgoing
        // file's position (file-absolute even in transcode mode) BEFORE the
        // transcode state below is reset.
        exoPlayer?.let { old ->
            val abs = if (transcodeActive) transcodeBaseOffsetMs + old.currentPosition
                      else old.currentPosition
            saveResumePosition(abs)
            old.release()
        }
        // A fresh direct attempt for a new path resets transcode state.
        currentPath = path
        transcodeActive = false
        transcodeBaseOffsetMs = 0L
        knownDurationMs = C.TIME_UNSET
        _playerState.update { it.copy(transcodeActive = false) }
        return buildPlayerInternal(path, contentUrl, transcode = false)
    }

    /**
     * Shared player construction for both direct and transcode playback.
     * When [transcode] is true, [uri] points at the /transcode endpoint and
     * the player is treated as a non-seekable progressive stream.
     */
    private fun buildPlayerInternal(path: String, uri: String, transcode: Boolean): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(authenticatedClient)
        val player = ExoPlayer.Builder(getApplication())
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()

        // New live instance: bump the generation so the UI's remembered
        // player reference (keyed on it) re-reads and re-binds.
        playerGeneration++
        _playerState.update { it.copy(playerGeneration = playerGeneration) }

        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true

        // Restore resume position — only for direct playback. In transcode
        // mode seeking is done by restarting the stream at a `start` offset
        // (see the onPlayerError fallback, which passes the resume second).
        //
        // NOTE: no duration guard here. At this point prepare() hasn't
        // completed and player.duration is still C.TIME_UNSET (negative), so
        // the old `player.duration > resumePos` check was always false and
        // resume silently never happened. ExoPlayer queues a pre-prepare
        // seek and clamps out-of-range targets, so a bare seekTo is safe.
        if (!transcode) {
            val resumePos = loadResumePosition(path)
            if (resumePos > 0) {
                player.seekTo(resumePos)
            }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playerState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playerState.update { it.copy(isReady = playbackState != Player.STATE_IDLE, error = null) }
                if (playbackState == Player.STATE_ENDED) {
                    _playerState.update { it.copy(isPlaying = false) }
                    // Watched to the end: re-opening should start from 0.
                    clearResumePosition(path)
                    return
                }
                if (transcode) {
                    // Displayed position is the stream restart offset plus the
                    // player's stream-local position; duration is the best value
                    // learned from the direct attempt (or unknown).
                    _playerState.update {
                        it.copy(
                            duration = knownDurationMs,
                            currentPosition = transcodeBaseOffsetMs + player.currentPosition,
                        )
                    }
                } else {
                    // Remember a valid duration so a later transcode fallback can
                    // still render the seek bar.
                    if (player.duration > 0 && player.duration != C.TIME_UNSET) {
                        knownDurationMs = player.duration
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
                // If a direct attempt failed because the container/codec is
                // unplayable, transparently fall back to server transcode —
                // starting at the saved resume position, since the direct
                // attempt's pre-prepare resume seek died with the player.
                if (!transcode && isTranscodableError(error)) {
                    startTranscodeFallback(path, loadResumePosition(path) / 1000L)
                    return
                }
                _playerState.update { it.copy(error = error.errorCodeName) }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                @Player.DiscontinuityReason reason: Int,
            ) {
                // Save resume position on user-initiated seek (direct mode only;
                // transcode positions are stream-local, not file-absolute).
                if (!transcode && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    saveResumePosition(newPosition.positionMs)
                }
            }
        })

        exoPlayer = player
        return player
    }

    /**
     * Returns true if [error] indicates the source container/codec cannot be
     * played directly (so transcode fallback is warranted). Deliberately does
     * NOT include generic network errors (ERROR_CODE_IO_*), which would
     * otherwise trigger a pointless re-fetch through the transcoder.
     */
    private fun isTranscodableError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
            else -> false
        }
    }

    /** Rebuild the player against the /transcode endpoint from [startSeconds]. */
    private fun startTranscodeFallback(path: String, startSeconds: Long = 0L) {
        exoPlayer?.release()
        transcodeActive = true
        transcodeBaseOffsetMs = startSeconds * 1000L
        _playerState.update {
            it.copy(
                transcodeActive = true,
                error = null,
                isReady = false,
                currentPosition = transcodeBaseOffsetMs,
                duration = knownDurationMs,
            )
        }
        exoPlayer = buildPlayerInternal(path, transcodeUrl(path, startSeconds), transcode = true)

        // When the duration is still unknown (the direct attempt died during
        // container parsing before any metadata), fetch it out-of-band so the
        // slider, seek-preview bubble, and seek-by-restart all enable. Gated
        // on knownDurationMs only — the first fallback can now start at a
        // resume offset (startSeconds > 0), and seek-restarts naturally skip
        // this because the duration is already known by then.
        if (knownDurationMs <= 0L) {
            viewModelScope.launch {
                val ms = fetchMediaInfo(path) ?: return@launch
                if (ms > 0L) {
                    knownDurationMs = ms
                    // The player may have been released while we awaited (screen
                    // closed); updating state is still safe — the UI re-reads it
                    // on recomposition and the seek bar enables reactively.
                    _playerState.update { it.copy(duration = knownDurationMs) }
                }
            }
        }
    }

    /**
     * Fetch the video's total duration (in ms) from the server's mediainfo
     * endpoint (PROTOCOL §6.8). Used only for transcode playback of
     * unplayable containers, where ExoPlayer never learns the duration
     * directly. Returns null on any failure (endpoint absent, non-video,
     * network error) so the caller simply leaves the seek bar disabled.
     */
    private suspend fun fetchMediaInfo(path: String): Long? = withContext(Dispatchers.IO) {
        try {
            val paired = secretStore.loadPairedDevice() ?: return@withContext null
            val base = paired.serverUrl.trimEnd('/')
            val shareParam = currentShareId?.let { "&share=${android.net.Uri.encode(it)}" } ?: ""
            val url = "$base/api/v1/files/mediainfo?path=${android.net.Uri.encode(path)}$shareParam"

            val request = Request.Builder().url(url).get().build()
            authenticatedClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val json = org.json.JSONObject(bodyStr)
                val seconds = json.optDouble("duration", 0.0)
                if (seconds > 0.0) (seconds * 1000.0).toLong() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun updateProgress() {
        val p = exoPlayer ?: return
        if (transcodeActive) {
            // Duration is the value learned from the direct attempt (or unknown);
            // position is the restart offset plus the stream-local position.
            _playerState.update {
                it.copy(
                    isPlaying = p.isPlaying,
                    duration = knownDurationMs,
                    currentPosition = transcodeBaseOffsetMs + p.currentPosition,
                )
            }
        } else {
            _playerState.update {
                it.copy(
                    isPlaying = p.isPlaying,
                    duration = if (p.duration > 0) p.duration else C.TIME_UNSET,
                    currentPosition = p.currentPosition,
                )
            }
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
        if (transcodeActive) {
            // Non-seekable progressive stream: restart the transcode at the
            // requested file-absolute second. `position` is already
            // file-absolute (the UI's seek bar uses the displayed duration).
            val target = position.coerceAtLeast(0L)
            saveResumePosition(target)
            startTranscodeFallback(currentPath, target / 1000L)
            return
        }
        exoPlayer?.seekTo(position)
    }

    fun seekRelative(deltaMs: Long) {
        val p = exoPlayer ?: return
        if (transcodeActive) {
            // File-absolute position = base offset + stream-local position.
            val abs = transcodeBaseOffsetMs + p.currentPosition
            val target = (abs + deltaMs).coerceAtLeast(0L)
            saveResumePosition(target)
            startTranscodeFallback(currentPath, target / 1000L)
            return
        }
        val newPos = (p.currentPosition + deltaMs).coerceIn(0L, p.duration)
        p.seekTo(newPos)
    }

    fun cycleSpeed(): Float {
        val p = exoPlayer ?: return 1f
        val current = p.playbackParameters.speed
        val currentIndex = DEFAULT_SPEEDS.indexOfFirst { abs(it - current) < 0.01 }.takeIf { it >= 0 } ?: 2
        val nextIndex = (currentIndex + 1) % DEFAULT_SPEEDS.size
        val newSpeed = DEFAULT_SPEEDS[nextIndex]
        val old = p.playbackParameters
        p.playbackParameters = androidx.media3.common.PlaybackParameters(newSpeed, old.pitch)
        return newSpeed
    }

    fun getCurrentSpeed(): Float {
        return exoPlayer?.playbackParameters?.speed ?: 1f
    }

    /**
     * Persist [position] (file-absolute ms) as the resume point for the
     * current file. Positions under [RESUME_MIN_MS] are ignored; positions
     * within [RESUME_END_MARGIN_MS] of a known duration clear the entry
     * instead (the user effectively finished the video).
     */
    fun saveResumePosition(position: Long) {
        if (currentPath.isEmpty()) return
        val dur = knownDurationMs
        if (dur > 0 && position >= dur - RESUME_END_MARGIN_MS) {
            clearResumePosition(currentPath)
            return
        }
        if (position > RESUME_MIN_MS) {
            persistResumePosition(currentPath, position)
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
        // Frame stepping is unavailable during transcode: the progressive
        // stream isn't precisely seekable and each step would restart it.
        if (transcodeActive) return
        val p = exoPlayer ?: return
        val ms = frameDurationMs().coerceIn(16L, 200L)
        val newPos = (p.currentPosition + ms).coerceIn(0L, p.duration)
        p.seekTo(newPos)
    }

    /** Seek backward by one frame. */
    fun frameStepBackward() {
        if (transcodeActive) return
        val p = exoPlayer ?: return
        val ms = frameDurationMs().coerceIn(16L, 200L)
        val newPos = (p.currentPosition - ms).coerceIn(0L, p.duration)
        p.seekTo(newPos)
    }

    private fun frameDurationMs(): Long {
        val fps = _playerState.value.framesPerSecond
        return if (fps > 1f) (1000f / fps).toLong() else 33L
    }

    /** Release the player when leaving the media screen. Saves resume position. */
    fun releasePlayer() {
        exoPlayer?.let { p ->
            // Persist file-absolute position — for transcode playback that
            // is the restart offset plus the stream-local position.
            val abs = if (transcodeActive) transcodeBaseOffsetMs + p.currentPosition
                      else p.currentPosition
            saveResumePosition(abs)
            p.release()
        }
        exoPlayer = null
        transcodeActive = false
        transcodeBaseOffsetMs = 0L
        knownDurationMs = C.TIME_UNSET
        _playerState.value = PlayerState()
        clearLoop()
    }

    public override fun onCleared() {
        releasePlayer()
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

    var currentShareId: String? = null

    fun contentUrl(remotePath: String): String {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        val base = paired.serverUrl.trimEnd('/')
        val shareParam = currentShareId?.let { "&share=${android.net.Uri.encode(it)}" } ?: ""
        return "$base/api/v1/files/content?path=${android.net.Uri.encode(remotePath)}$shareParam"
    }

    /**
     * URL for the server-side transcode endpoint (PROTOCOL §6.6). Mirrors
     * [contentUrl] but hits /transcode, adding `&start=` for coarse seeking
     * when [startSeconds] > 0. The result is a non-seekable progressive fMP4.
     */
    fun transcodeUrl(remotePath: String, startSeconds: Long = 0L): String {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        val base = paired.serverUrl.trimEnd('/')
        val shareParam = currentShareId?.let { "&share=${android.net.Uri.encode(it)}" } ?: ""
        val startParam = if (startSeconds > 0L) "&start=$startSeconds" else ""
        return "$base/api/v1/files/transcode?path=${android.net.Uri.encode(remotePath)}$shareParam$startParam"
    }
}
