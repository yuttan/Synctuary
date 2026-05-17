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
        val homeUrl = prefs.getString(K_SERVER_URL, "").orEmpty()
        val activeUrl = getActiveUrl() ?: homeUrl
        return PairedDevice(
            serverUrl = activeUrl,
            serverId = B64Url.decode(prefs.getString(K_SERVER_ID, "").orEmpty()),
            serverFingerprint = prefs.getString(K_SERVER_FP, null)?.let { B64Url.decode(it) },
            deviceId = B64Url.decode(prefs.getString(K_DEVICE_ID, "").orEmpty()),
            devicePub = B64Url.decode(prefs.getString(K_DEVICE_PUB, "").orEmpty()),
            devicePriv = B64Url.decode(prefs.getString(K_DEVICE_PRIV, "").orEmpty()),
            deviceToken = B64Url.decode(prefs.getString(K_DEVICE_TOKEN, "").orEmpty()),
        )
    }

    fun loadHomeUrl(): String = prefs.getString(K_SERVER_URL, "").orEmpty()

    // ── Remote URL storage (up to MAX_REMOTE_URLS slots) ────────────

    /** Save a remote URL at the given slot index. */
    fun saveRemoteUrl(url: String?, index: Int = 0) {
        if (index !in 0 until MAX_REMOTE_URLS) return
        prefs.edit().apply {
            if (url != null) putString(remoteUrlKey(index), url)
            else remove(remoteUrlKey(index))
        }.apply()
    }

    /** Load a remote URL from the given slot. */
    fun loadRemoteUrl(index: Int = 0): String? {
        if (index !in 0 until MAX_REMOTE_URLS) return null
        return prefs.getString(remoteUrlKey(index), null)
    }

    /** Save an optional label for a remote URL slot. */
    fun saveRemoteLabel(label: String?, index: Int) {
        if (index !in 0 until MAX_REMOTE_URLS) return
        prefs.edit().apply {
            if (label != null) putString(remoteLabelKey(index), label)
            else remove(remoteLabelKey(index))
        }.apply()
    }

    /** Load the label for a remote URL slot. */
    fun loadRemoteLabel(index: Int): String? {
        if (index !in 0 until MAX_REMOTE_URLS) return null
        return prefs.getString(remoteLabelKey(index), null)
    }

    /** Return all configured remote URLs with their labels. */
    fun loadAllRemoteUrls(): List<RemoteEntry> {
        migrateRemoteUrl()
        val result = mutableListOf<RemoteEntry>()
        for (i in 0 until MAX_REMOTE_URLS) {
            val url = prefs.getString(remoteUrlKey(i), null)
            if (url != null) {
                result.add(RemoteEntry(index = i, url = url, label = prefs.getString(remoteLabelKey(i), null)))
            }
        }
        return result
    }

    /** Find the first empty slot index, or null if all slots are full. */
    fun firstEmptySlot(): Int? {
        for (i in 0 until MAX_REMOTE_URLS) {
            if (prefs.getString(remoteUrlKey(i), null) == null) return i
        }
        return null
    }

    /** Delete a remote URL slot (clears both URL and label). */
    fun deleteRemoteUrl(index: Int) {
        if (index !in 0 until MAX_REMOTE_URLS) return
        prefs.edit().apply {
            remove(remoteUrlKey(index))
            remove(remoteLabelKey(index))
        }.apply()
        // If the active mode pointed at this slot, reset to home.
        if (getActiveMode() == remoteMode(index)) {
            setActiveMode(MODE_HOME)
        }
    }

    /** Migrate legacy single remote_url to slot 0 on first access. */
    private fun migrateRemoteUrl() {
        val legacy = prefs.getString(K_REMOTE_URL_LEGACY, null) ?: return
        if (prefs.getString(remoteUrlKey(0), null) == null) {
            prefs.edit().putString(remoteUrlKey(0), legacy).apply()
        }
        // Migrate active mode "remote" → "remote_0"
        if (prefs.getString(K_ACTIVE_MODE, null) == "remote") {
            prefs.edit().putString(K_ACTIVE_MODE, remoteMode(0)).apply()
        }
        prefs.edit().remove(K_REMOTE_URL_LEGACY).apply()
    }

    fun updateServerUrl(url: String) {
        prefs.edit().putString(K_SERVER_URL, url).apply()
    }

    fun setActiveMode(mode: String) {
        prefs.edit().putString(K_ACTIVE_MODE, mode).apply()
    }

    fun getActiveMode(): String {
        migrateRemoteUrl()
        return prefs.getString(K_ACTIVE_MODE, MODE_HOME) ?: MODE_HOME
    }

    /** Extract the slot index from a remote mode string, or null if home. */
    fun activeRemoteIndex(): Int? {
        val mode = getActiveMode()
        if (!mode.startsWith("remote_")) return null
        return mode.removePrefix("remote_").toIntOrNull()
    }

    fun getActiveUrl(): String? {
        val homeUrl = prefs.getString(K_SERVER_URL, null)
        val mode = getActiveMode()
        val idx = activeRemoteIndex()
        return if (idx != null) {
            loadRemoteUrl(idx) ?: homeUrl
        } else {
            homeUrl
        }
    }

    private fun remoteUrlKey(index: Int) = "remote_url_$index"
    private fun remoteLabelKey(index: Int) = "remote_label_$index"

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
        private const val K_REMOTE_URL_LEGACY = "remote_url" // v0.7.0 single URL; migrated to slot 0
        private const val K_ACTIVE_MODE = "active_mode"
        const val MAX_REMOTE_URLS = 3
        const val MODE_HOME = "home"
        /** Build the mode string for a remote slot index. */
        fun remoteMode(index: Int): String = "remote_$index"

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

/** A single remote URL entry with its slot index and optional label. */
data class RemoteEntry(
    val index: Int,
    val url: String,
    val label: String? = null,
)
