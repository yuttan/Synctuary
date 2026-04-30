package io.synctuary.android.data.secret

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.synctuary.android.crypto.B64Url

/**
 * Persistent secret storage backed by androidx.security
 * [EncryptedSharedPreferences].
 *
 * Stores per-paired-server material:
 *   - serverUrl     — the URL the user typed (helps re-pair after wipe)
 *   - serverId      — server's 16-byte identifier, base64url
 *   - serverFingerprint — 32-byte SHA-256 of the TLS leaf cert (§3.3)
 *   - deviceId      — our 16-byte client identifier
 *   - devicePub     — our 32-byte Ed25519 public key
 *   - devicePriv    — our 32-byte Ed25519 private seed
 *   - deviceToken   — server-issued bearer (32 raw bytes)
 *
 * Encryption: AES-256-GCM, key wrapped by Android Keystore (the master
 * key is non-exportable hardware-backed on devices that have a TEE).
 *
 * Single-server model for v0.4 — multi-server multi-pair lands in a
 * later phase.
 */
class SecretStore private constructor(private val prefs: SharedPreferences) {

    fun savePairedDevice(
        serverUrl: String,
        serverId: ByteArray,
        serverFingerprint: ByteArray?,
        deviceId: ByteArray,
        devicePub: ByteArray,
        devicePriv: ByteArray,
        deviceToken: ByteArray,
    ) {
        prefs.edit().apply {
            putString(K_SERVER_URL, serverUrl)
            putString(K_SERVER_ID, B64Url.encode(serverId))
            putString(K_SERVER_FP, serverFingerprint?.let { B64Url.encode(it) })
            putString(K_DEVICE_ID, B64Url.encode(deviceId))
            putString(K_DEVICE_PUB, B64Url.encode(devicePub))
            putString(K_DEVICE_PRIV, B64Url.encode(devicePriv))
            putString(K_DEVICE_TOKEN, B64Url.encode(deviceToken))
            apply()
        }
    }

    /** True once a paired device has been persisted. UI uses this to
     *  decide between onboarding and the main screen. */
    fun isPaired(): Boolean = prefs.contains(K_DEVICE_TOKEN)

    fun loadPairedDevice(): PairedDevice? {
        if (!isPaired()) return null
        return PairedDevice(
            serverUrl = prefs.getString(K_SERVER_URL, "").orEmpty(),
            serverId = B64Url.decode(prefs.getString(K_SERVER_ID, "").orEmpty()),
            serverFingerprint = prefs.getString(K_SERVER_FP, null)?.let { B64Url.decode(it) },
            deviceId = B64Url.decode(prefs.getString(K_DEVICE_ID, "").orEmpty()),
            devicePub = B64Url.decode(prefs.getString(K_DEVICE_PUB, "").orEmpty()),
            devicePriv = B64Url.decode(prefs.getString(K_DEVICE_PRIV, "").orEmpty()),
            deviceToken = B64Url.decode(prefs.getString(K_DEVICE_TOKEN, "").orEmpty()),
        )
    }

    /** Wipe everything. Used when the user explicitly un-pairs (mockup
     *  screen 7 — Danger Zone). After this the app falls back to
     *  onboarding on next launch. */
    fun wipe() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "synctuary-secrets"
        private const val K_SERVER_URL = "server_url"
        private const val K_SERVER_ID = "server_id"
        private const val K_SERVER_FP = "server_fingerprint"
        private const val K_DEVICE_ID = "device_id"
        private const val K_DEVICE_PUB = "device_pub"
        private const val K_DEVICE_PRIV = "device_priv"
        private const val K_DEVICE_TOKEN = "device_token"

        /** Build the singleton-ish store for an Application context.
         *  EncryptedSharedPreferences is internally cached, so calling
         *  this multiple times is cheap; we don't enforce singleton
         *  semantics here to keep the Phase 2 surface minimal. */
        fun create(context: Context): SecretStore {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return SecretStore(prefs)
        }
    }
}

/** Snapshot of the current paired-device state. Returned by
 *  [SecretStore.loadPairedDevice]; never modified in place. */
data class PairedDevice(
    val serverUrl: String,
    val serverId: ByteArray,
    val serverFingerprint: ByteArray?,
    val deviceId: ByteArray,
    val devicePub: ByteArray,
    val devicePriv: ByteArray,
    val deviceToken: ByteArray,
) {
    /** Bearer header value as the server expects it. */
    fun bearerHeader(): String = "Bearer " + B64Url.encode(deviceToken)
}
