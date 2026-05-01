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

    val authenticatedClient: OkHttpClient by lazy {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        NetworkModule.createOkHttpClient(
            paired.serverUrl,
            paired.serverFingerprint,
            AuthInterceptor(secretStore),
        )
    }

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(getApplication())
            .okHttpClient(authenticatedClient)
            .crossfade(true)
            .build()
    }

    fun contentUrl(remotePath: String): String {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        val base = paired.serverUrl.trimEnd('/')
        return "$base/api/v1/files/content?path=${Uri.encode(remotePath)}"
    }
}
