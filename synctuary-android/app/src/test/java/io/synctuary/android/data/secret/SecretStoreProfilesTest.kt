package io.synctuary.android.data.secret

import android.content.SharedPreferences
import io.synctuary.android.crypto.B64Url
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStoreProfilesTest {

    private val serverA = ByteArray(16) { 0x0A }
    private val serverB = ByteArray(16) { 0x0B }

    private fun SecretStore.pair(serverId: ByteArray, url: String, token: Byte) =
        savePairedDevice(
            serverUrl = url,
            serverId = serverId,
            serverFingerprint = ByteArray(32) { token },
            deviceId = ByteArray(16) { token },
            devicePub = ByteArray(32) { token },
            devicePriv = ByteArray(32) { token },
            deviceToken = ByteArray(32) { token },
        )

    @Test
    fun `legacy single pairing migrates into a profile`() {
        val prefs = FakePrefs()
        prefs.edit()
            .putString("server_url", "https://192.168.1.10:8443")
            .putString("server_id", B64Url.encode(serverA))
            .putString("device_id", B64Url.encode(ByteArray(16) { 1 }))
            .putString("device_pub", B64Url.encode(ByteArray(32) { 1 }))
            .putString("device_priv", B64Url.encode(ByteArray(32) { 1 }))
            .putString("device_token", B64Url.encode(ByteArray(32) { 7 }))
            .putString("remote_url", "https://ts.example:8443") // v0.7.0 key
            .putString("active_mode", "remote")
            .apply()

        val store = SecretStore.fromPrefs(prefs)

        assertTrue(store.isPaired())
        assertEquals(B64Url.encode(serverA), store.activeProfileId())
        assertEquals("https://192.168.1.10:8443", store.loadHomeUrl())
        assertEquals("https://ts.example:8443", store.loadRemoteUrl(0))
        assertEquals(SecretStore.remoteMode(0), store.getActiveMode())
        assertEquals("https://ts.example:8443", store.loadPairedDevice()!!.serverUrl)
        assertArrayEquals(ByteArray(32) { 7 }, store.loadPairedDevice()!!.deviceToken)
        assertFalse("bare legacy keys removed", prefs.contains("device_token"))

        // Idempotent: a second create() leaves the profile alone.
        val again = SecretStore.fromPrefs(prefs)
        assertEquals(1, again.listProfiles().size)
        assertTrue(again.isPaired())
    }

    @Test
    fun `second server adds a profile and each keeps its own identity`() {
        val store = SecretStore.fromPrefs(FakePrefs())
        store.pair(serverA, "https://a.lan:8443", 1)
        store.saveRemoteUrl("https://a.vpn:8443", 0)
        store.pair(serverB, "https://b.lan:8443", 2)

        assertEquals(listOf("a.lan", "b.lan"), store.listProfiles().map { it.label })
        assertEquals(B64Url.encode(serverB), store.activeProfileId())
        assertNull("B has no remote URLs of its own", store.loadRemoteUrl(0))
        assertArrayEquals(ByteArray(32) { 2 }, store.loadPairedDevice()!!.deviceToken)

        store.setActiveProfile(B64Url.encode(serverA))
        assertEquals("https://a.vpn:8443", store.loadRemoteUrl(0))
        assertArrayEquals(ByteArray(32) { 1 }, store.loadPairedDevice()!!.deviceToken)
    }

    @Test
    fun `re-pairing the same server updates rather than duplicates`() {
        val store = SecretStore.fromPrefs(FakePrefs())
        store.pair(serverA, "https://a.lan:8443", 1)
        store.saveRemoteUrl("https://a.vpn:8443", 0)
        store.renameProfile(B64Url.encode(serverA), "Home NAS")
        store.setActiveMode(SecretStore.remoteMode(0))

        store.pair(serverA, "https://a-new.lan:8443", 9)

        val profiles = store.listProfiles()
        assertEquals(1, profiles.size)
        assertEquals("Home NAS", profiles[0].label)
        assertEquals("https://a-new.lan:8443", store.loadHomeUrl())
        assertEquals("remote URLs survive a re-pair", "https://a.vpn:8443", store.loadRemoteUrl(0))
        assertEquals(SecretStore.MODE_HOME, store.getActiveMode())
        assertArrayEquals(ByteArray(32) { 9 }, store.loadPairedDevice()!!.deviceToken)
    }

    @Test
    fun `removing the active profile falls back to the next one`() {
        val store = SecretStore.fromPrefs(FakePrefs())
        store.pair(serverA, "https://a.lan:8443", 1)
        store.pair(serverB, "https://b.lan:8443", 2)

        assertTrue(store.removeProfile())
        assertEquals(B64Url.encode(serverA), store.activeProfileId())
        assertEquals(listOf(B64Url.encode(serverA)), store.listProfiles().map { it.id })

        assertFalse(store.removeProfile())
        assertFalse(store.isPaired())
        assertNull(store.loadPairedDevice())
    }

    @Test
    fun `pinned store ignores active profile switches`() {
        val store = SecretStore.fromPrefs(FakePrefs())
        store.pair(serverA, "https://a.lan:8443", 1)
        store.pair(serverB, "https://b.lan:8443", 2)

        val pinnedA = store.forProfile(B64Url.encode(serverA))
        assertEquals("https://a.lan:8443", pinnedA.loadPairedDevice()!!.serverUrl)

        store.setActiveProfile(B64Url.encode(serverB))
        assertEquals("https://a.lan:8443", pinnedA.loadPairedDevice()!!.serverUrl)

        store.setActiveProfile(B64Url.encode(serverA))
        store.removeProfile()
        assertFalse("pinned store sees its profile removed", pinnedA.isPaired())
    }

    @Test
    fun `default label strips IPv6 brackets`() {
        assertEquals("fd00::1", SecretStore.defaultLabel("https://[fd00::1]:8443"))
        assertEquals("nas.local", SecretStore.defaultLabel("https://nas.local:8443/"))
    }
}

/** Minimal in-memory SharedPreferences: string-only, synchronous apply. */
private class FakePrefs : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, String?>()
        private val removes = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }

        // Android semantics: clear() first, then removes, then puts
        // (a put of null is a remove).
        override fun apply() {
            if (clear) map.clear()
            removes.forEach { map.remove(it) }
            puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}
