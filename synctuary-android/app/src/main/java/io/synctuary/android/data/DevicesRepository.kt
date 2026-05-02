package io.synctuary.android.data

import io.synctuary.android.data.api.AuthInterceptor
import io.synctuary.android.data.api.NetworkModule
import io.synctuary.android.data.api.dto.DeviceDto
import io.synctuary.android.data.secret.SecretStore

class DevicesRepository(private val secretStore: SecretStore) {

    private val api by lazy {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        NetworkModule.create(
            baseUrl = paired.serverUrl,
            fingerprint = paired.serverFingerprint,
            authInterceptor = AuthInterceptor(secretStore),
        )
    }

    suspend fun listAll(): List<DeviceDto> = api.devicesList().devices

    suspend fun revoke(deviceId: String) {
        api.devicesRevoke(deviceId)
    }
}
