package io.synctuary.android.ui.preview

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import coil.ImageLoader
import io.synctuary.android.data.api.AuthInterceptor
import io.synctuary.android.data.api.NetworkModule
import io.synctuary.android.data.secret.SecretStore
import okhttp3.OkHttpClient

class PreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = SecretStore.create(application)

    var imagePaths: List<String> = emptyList()
        private set

    // When non-null, contentUrl() resolves entries via the archive-content
    // endpoint (§6.10) with this archive as `path` and the image path as
    // `entry`. Normal (non-archive) browsing clears it via setImageList.
    var archivePath: String? = null
        private set

    fun setImageList(paths: List<String>) {
        imagePaths = paths
        archivePath = null
    }

    /**
     * Configure the pager to page through image ENTRIES of an archive
     * (comic-reader mode). [paths] are archive-internal entry paths.
     */
    fun setArchiveImageList(archivePath: String, paths: List<String>) {
        this.archivePath = archivePath
        imagePaths = paths
    }

    fun indexOfImage(path: String): Int =
        imagePaths.indexOf(path).coerceAtLeast(0)

    private var _cachedUrl: String? = null
    private var _client: OkHttpClient? = null
    private var _imageLoader: ImageLoader? = null

    // Rebuilds the client (and drops the image loader) when the active
    // server URL changes — e.g. the user switches Home <-> Remote in
    // Settings. Without this, the old fingerprint-pinned client keeps
    // being used and every request to the new server fails TLS.
    private fun ensureClient(): OkHttpClient {
        // Freshness check via getActiveUrl() (2 decrypted prefs reads)
        // instead of loadPairedDevice() (7) — this runs per thumbnail row.
        val activeUrl = secretStore.getActiveUrl()
        _client?.let { existing ->
            if (activeUrl != null && _cachedUrl == activeUrl) return existing
        }
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        // URL changed: shut down the old image loader so its Coil memory
        // cache (images from the previous server context) is released.
        _imageLoader?.shutdown()
        _imageLoader = null
        _cachedUrl = paired.serverUrl
        return NetworkModule.createOkHttpClient(
            paired.serverUrl,
            paired.serverFingerprint,
            AuthInterceptor(secretStore),
        ).also { _client = it }
    }

    val authenticatedClient: OkHttpClient
        get() = ensureClient()

    val imageLoader: ImageLoader
        get() {
            val client = ensureClient()
            _imageLoader?.let { return it }
            return ImageLoader.Builder(getApplication())
                .okHttpClient(client)
                .crossfade(true)
                .build()
                .also { _imageLoader = it }
        }

    var currentShareId: String? = null

    // Base URL for building content/thumbnail URLs. ensureClient()
    // refreshes _cachedUrl from the (expensive, AES-decrypting)
    // EncryptedSharedPreferences read only when needed; per-row calls
    // during list scroll reuse the cached value.
    private fun baseUrl(): String {
        ensureClient()
        return _cachedUrl!!.trimEnd('/')
    }

    fun contentUrl(remotePath: String): String {
        val shareParam = currentShareId?.let { "&share=${Uri.encode(it)}" } ?: ""
        archivePath?.let { archive ->
            // remotePath is an archive-internal entry path (§6.10).
            return "${baseUrl()}/api/v1/files/archive/content" +
                "?path=${Uri.encode(archive)}&entry=${Uri.encode(remotePath)}$shareParam"
        }
        return "${baseUrl()}/api/v1/files/content?path=${Uri.encode(remotePath)}$shareParam"
    }

    // timeSeconds > 0 requests a seek-preview frame at that timestamp
    // (PROTOCOL §6.7 `t` param). 0 keeps the default DB-cached thumbnail.
    fun thumbnailUrl(remotePath: String, size: Int = 256, timeSeconds: Long = 0): String {
        val shareParam = currentShareId?.let { "&share=${Uri.encode(it)}" } ?: ""
        val timeParam = if (timeSeconds > 0) "&t=$timeSeconds" else ""
        return "${baseUrl()}/api/v1/files/thumbnail?path=${Uri.encode(remotePath)}&size=$size$shareParam$timeParam"
    }

    override fun onCleared() {
        _imageLoader?.shutdown()
        super.onCleared()
    }
}
