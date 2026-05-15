package io.synctuary.android.data

import io.synctuary.android.data.api.AuthInterceptor
import io.synctuary.android.data.api.NetworkModule
import io.synctuary.android.data.api.dto.DeviceDto
import io.synctuary.android.data.secret.SecretStore

class DevicesRepository(private val secretStore: SecretStore) {

    private var api: io.synctuary.android.data.api.SynctuaryApi? = null
    private var cachedUrl: String? = null

    private fun authenticatedApi(): io.synctuary.android.data.api.SynctuaryApi {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        if (api != null && cachedUrl == paired.serverUrl) return api!!
        cachedUrl = paired.serverUrl
        return NetworkModule.create(
            baseUrl = paired.serverUrl,
            fingerprint = paired.serverFingerprint,
            authInterceptor = AuthInterceptor(secretStore),
        ).also { api = it }
    }

    fun resetApiCache() {
        api = null
        cachedUrl = null
    }

    suspend fun listAll(): List<DeviceDto> = authenticatedApi().devicesList().devices

    suspend fun revoke(deviceId: String) {
        authenticatedApi().devicesRevoke(deviceId)
    }
}
