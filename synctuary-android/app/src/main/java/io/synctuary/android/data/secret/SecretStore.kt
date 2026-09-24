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
 * Holds one *profile* per paired server. Pairing is per server (each
 * server has its own master key, so it issues its own device identity
 * and bearer), so every profile carries a full, independent set of:
 *   - serverUrl     — the home (LAN) URL the user paired with
 *   - serverId      — server's 16-byte identifier, base64url
 *   - serverFingerprint — 32-byte SHA-256 of the TLS leaf cert (§3.3)
 *   - deviceId      — our 16-byte client identifier on that server
 *   - devicePub     — our 32-byte Ed25519 public key
 *   - devicePriv    — our 32-byte Ed25519 private seed
 *   - deviceToken   — server-issued bearer (32 raw bytes)
 *   - remote URLs (VPN / Tailscale / IPv6), a display label, and the
 *     home/remote active mode
 *
 * Profile keys are namespaced as `p:<profileId>:<key>` where the id is
 * base64url(serverId) — stable per server master key, so re-pairing the
 * same server updates its profile instead of creating a duplicate.
 *
 * Every per-server accessor ([loadPairedDevice], remote URLs, active
 * mode, ...) acts on the *active* profile, so the many call sites that
 * predate multi-server support keep working unchanged. A store obtained
 * via [forProfile] is pinned to one profile instead (used by background
 * work that must not follow the user's server switches).
 *
 * Encryption: AES-256-GCM, key wrapped by Android Keystore (the master
 * key is non-exportable hardware-backed on devices that have a TEE).
 */
class SecretStore private constructor(
    private val prefs: SharedPreferences,
    private val pinnedProfileId: String? = null,
) {

    // ── Profiles ────────────────────────────────────────────────────

    /** The profile this store currently reads/writes, or null when
     *  nothing is paired. */
    fun profileId(): String? = pinnedProfileId ?: activeProfileId()

    // Not re-validated against the profile list: this runs on every
    // profile-scoped read, and savePairedDevice/removeProfile keep
    // active_profile consistent with the list.
    fun activeProfileId(): String? = prefs.getString(K_ACTIVE_PROFILE, null)

    private fun profileIds(): List<String> =
        prefs.getString(K_PROFILES, null)
            ?.split(',')
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun saveProfileIds(ids: List<String>) {
        prefs.edit().putString(K_PROFILES, ids.joinToString(",")).apply()
    }

    /** All paired servers in pairing order. */
    fun listProfiles(): List<ServerProfile> = profileIds().map { id ->
        val homeUrl = prefs.getString(pk(id, K_SERVER_URL), "").orEmpty()
        ServerProfile(
            id = id,
            label = prefs.getString(pk(id, K_LABEL), null) ?: defaultLabel(homeUrl),
            homeUrl = homeUrl,
        )
    }

    /** Make [id] the active profile. No-op for unknown ids. */
    fun setActiveProfile(id: String) {
        if (id !in profileIds()) return
        prefs.edit().putString(K_ACTIVE_PROFILE, id).apply()
    }

    fun renameProfile(id: String, label: String?) {
        if (id !in profileIds()) return
        prefs.edit().apply {
            if (label.isNullOrBlank()) remove(pk(id, K_LABEL))
            else putString(pk(id, K_LABEL), label.trim())
        }.apply()
    }

    /** A view of this store pinned to [id] regardless of which profile
     *  is active. Returns a store whose [isPaired] is false if [id] is
     *  no longer paired. */
    fun forProfile(id: String): SecretStore = SecretStore(prefs, id)

    /**
     * Remove this store's profile (the active one, unless pinned) and all
     * its keys. If it was active, the first remaining profile becomes
     * active. Returns true if any profile remains paired.
     */
    fun removeProfile(): Boolean {
        val id = profileId() ?: return profileIds().isNotEmpty()
        val prefix = "p:$id:"
        val remaining = profileIds() - id
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
            putString(K_PROFILES, remaining.joinToString(","))
            if (prefs.getString(K_ACTIVE_PROFILE, null) == id) {
                val next = remaining.firstOrNull()
                if (next != null) putString(K_ACTIVE_PROFILE, next) else remove(K_ACTIVE_PROFILE)
            }
        }.apply()
        return remaining.isNotEmpty()
    }

    // ── Pairing material ────────────────────────────────────────────

    /**
     * Persist a successful pairing. Adds a new profile, or — when the same
     * server (same serverId) is re-paired — replaces that profile's device
     * identity while keeping its remote URLs and label. Either way the
     * paired server becomes the active profile.
     */
    fun savePairedDevice(
        serverUrl: String,
        serverId: ByteArray,
        serverFingerprint: ByteArray?,
        deviceId: ByteArray,
        devicePub: ByteArray,
        devicePriv: ByteArray,
        deviceToken: ByteArray,
    ) {
        val id = B64Url.encode(serverId)
        val ids = profileIds()
        if (id !in ids) saveProfileIds(ids + id)
        prefs.edit().apply {
            putString(pk(id, K_SERVER_URL), serverUrl)
            putString(pk(id, K_SERVER_ID), id)
            putString(pk(id, K_SERVER_FP), serverFingerprint?.let { B64Url.encode(it) })
            putString(pk(id, K_DEVICE_ID), B64Url.encode(deviceId))
            putString(pk(id, K_DEVICE_PUB), B64Url.encode(devicePub))
            putString(pk(id, K_DEVICE_PRIV), B64Url.encode(devicePriv))
            putString(pk(id, K_DEVICE_TOKEN), B64Url.encode(deviceToken))
            // A fresh pairing always starts on the URL it was paired with.
            putString(pk(id, K_ACTIVE_MODE), MODE_HOME)
            putString(K_ACTIVE_PROFILE, id)
            apply()
        }
    }

    /** True once a paired device has been persisted for this store's
     *  profile. UI uses this to decide between onboarding and the main
     *  screen. */
    fun isPaired(): Boolean {
        val id = profileId() ?: return false
        return prefs.contains(pk(id, K_DEVICE_TOKEN))
    }

    fun loadPairedDevice(): PairedDevice? {
        if (!isPaired()) return null
        val homeUrl = get(K_SERVER_URL).orEmpty()
        val activeUrl = getActiveUrl() ?: homeUrl
        return PairedDevice(
            serverUrl = activeUrl,
            serverId = B64Url.decode(get(K_SERVER_ID).orEmpty()),
            serverFingerprint = get(K_SERVER_FP)?.let { B64Url.decode(it) },
            deviceId = B64Url.decode(get(K_DEVICE_ID).orEmpty()),
            devicePub = B64Url.decode(get(K_DEVICE_PUB).orEmpty()),
            devicePriv = B64Url.decode(get(K_DEVICE_PRIV).orEmpty()),
            deviceToken = B64Url.decode(get(K_DEVICE_TOKEN).orEmpty()),
        )
    }

    fun loadHomeUrl(): String = get(K_SERVER_URL).orEmpty()

    // ── Remote URL storage (up to MAX_REMOTE_URLS slots) ────────────

    /** Save a remote URL at the given slot index. */
    fun saveRemoteUrl(url: String?, index: Int = 0) {
        if (index !in 0 until MAX_REMOTE_URLS) return
        put(remoteUrlKey(index), url)
    }

    /** Load a remote URL from the given slot. */
    fun loadRemoteUrl(index: Int = 0): String? {
        if (index !in 0 until MAX_REMOTE_URLS) return null
        return get(remoteUrlKey(index))
    }

    /** Save an optional label for a remote URL slot. */
    fun saveRemoteLabel(label: String?, index: Int) {
        if (index !in 0 until MAX_REMOTE_URLS) return
        put(remoteLabelKey(index), label)
    }

    /** Load the label for a remote URL slot. */
    fun loadRemoteLabel(index: Int): String? {
        if (index !in 0 until MAX_REMOTE_URLS) return null
        return get(remoteLabelKey(index))
    }

    /** Return all configured remote URLs with their labels. */
    fun loadAllRemoteUrls(): List<RemoteEntry> {
        val result = mutableListOf<RemoteEntry>()
        for (i in 0 until MAX_REMOTE_URLS) {
            val url = get(remoteUrlKey(i))
            if (url != null) {
                result.add(RemoteEntry(index = i, url = url, label = get(remoteLabelKey(i))))
            }
        }
        return result
    }

    /** Find the first empty slot index, or null if all slots are full. */
    fun firstEmptySlot(): Int? {
        for (i in 0 until MAX_REMOTE_URLS) {
            if (get(remoteUrlKey(i)) == null) return i
        }
        return null
    }

    /** Delete a remote URL slot (clears both URL and label). */
    fun deleteRemoteUrl(index: Int) {
        if (index !in 0 until MAX_REMOTE_URLS) return
        put(remoteUrlKey(index), null)
        put(remoteLabelKey(index), null)
        // If the active mode pointed at this slot, reset to home.
        if (getActiveMode() == remoteMode(index)) {
            setActiveMode(MODE_HOME)
        }
    }

    fun updateServerUrl(url: String) {
        put(K_SERVER_URL, url)
    }

    fun setActiveMode(mode: String) {
        put(K_ACTIVE_MODE, mode)
    }

    fun getActiveMode(): String = get(K_ACTIVE_MODE) ?: MODE_HOME

    /** Extract the slot index from a remote mode string, or null if home. */
    fun activeRemoteIndex(): Int? {
        val mode = getActiveMode()
        if (!mode.startsWith("remote_")) return null
        return mode.removePrefix("remote_").toIntOrNull()
    }

    fun getActiveUrl(): String? {
        val homeUrl = get(K_SERVER_URL)
        val idx = activeRemoteIndex()
        return if (idx != null) {
            loadRemoteUrl(idx) ?: homeUrl
        } else {
            homeUrl
        }
    }

    private fun remoteUrlKey(index: Int) = "remote_url_$index"
    private fun remoteLabelKey(index: Int) = "remote_label_$index"

    // ── Profile-scoped key helpers ──────────────────────────────────

    private fun get(key: String): String? {
        val id = profileId() ?: return null
        return prefs.getString(pk(id, key), null)
    }

    private fun put(key: String, value: String?) {
        val id = profileId() ?: return
        prefs.edit().apply {
            if (value != null) putString(pk(id, key), value) else remove(pk(id, key))
        }.apply()
    }

    /** Wipe everything, all profiles included. After this the app falls
     *  back to onboarding on next launch. */
    fun wipe() {
        prefs.edit().clear().apply()
    }

    /**
     * One-shot migration from the pre-profile layout (a single pairing
     * stored under bare keys, v0.4-v0.7.23) into profile `p:<serverId>:`.
     * Also folds in the even older single `remote_url` key (v0.7.0).
     * Runs on every [create]; a no-op once the bare device_token is gone.
     */
    private fun migrateLegacySinglePairing() {
        if (!prefs.contains(K_DEVICE_TOKEN)) return
        val id = prefs.getString(K_SERVER_ID, null)
        val edit = prefs.edit()
        if (!id.isNullOrEmpty()) {
            val copy = mutableMapOf<String, String>()
            for (key in LEGACY_KEYS) prefs.getString(key, null)?.let { copy[key] = it }
            prefs.getString(K_REMOTE_URL_LEGACY, null)?.let { legacy ->
                if (copy[remoteUrlKey(0)] == null) copy[remoteUrlKey(0)] = legacy
            }
            if (copy[K_ACTIVE_MODE] == "remote") copy[K_ACTIVE_MODE] = remoteMode(0)
            copy.forEach { (k, v) -> edit.putString(pk(id, k), v) }
            val ids = profileIds()
            if (id !in ids) edit.putString(K_PROFILES, (ids + id).joinToString(","))
            edit.putString(K_ACTIVE_PROFILE, id)
        }
        (LEGACY_KEYS + K_REMOTE_URL_LEGACY).forEach { edit.remove(it) }
        edit.apply()
    }

    companion object {
        private const val PREFS_NAME = "synctuary-secrets"
        private const val K_PROFILES = "profiles"             // comma-separated profile ids
        private const val K_ACTIVE_PROFILE = "active_profile"
        private const val K_LABEL = "label"
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

        private val LEGACY_KEYS = listOf(
            K_SERVER_URL, K_SERVER_ID, K_SERVER_FP, K_DEVICE_ID, K_DEVICE_PUB,
            K_DEVICE_PRIV, K_DEVICE_TOKEN, K_ACTIVE_MODE,
        ) + (0 until MAX_REMOTE_URLS).flatMap { listOf("remote_url_$it", "remote_label_$it") }

        private fun pk(profileId: String, key: String) = "p:$profileId:$key"

        /** Build the mode string for a remote slot index. */
        fun remoteMode(index: Int): String = "remote_$index"

        /** Fallback display name for a profile: the home URL's host. */
        fun defaultLabel(homeUrl: String): String =
            runCatching { java.net.URI(homeUrl).host }.getOrNull()
                ?.removePrefix("[")?.removeSuffix("]")
                ?.takeIf { it.isNotEmpty() }
                ?: homeUrl.ifEmpty { "Server" }

        /** Build the singleton-ish store for an Application context.
         *  EncryptedSharedPreferences is internally cached, so calling
         *  this multiple times is cheap; we don't enforce singleton
         *  semantics here to keep the surface minimal. */
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
            return fromPrefs(prefs)
        }

        /** Wrap an already-open prefs file. Separate from [create] so JVM
         *  unit tests can drive the profile logic with an in-memory fake. */
        internal fun fromPrefs(prefs: SharedPreferences): SecretStore =
            SecretStore(prefs).also { it.migrateLegacySinglePairing() }
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

/** One paired server as shown in the server switcher. */
data class ServerProfile(
    val id: String,
    val label: String,
    val homeUrl: String,
)
