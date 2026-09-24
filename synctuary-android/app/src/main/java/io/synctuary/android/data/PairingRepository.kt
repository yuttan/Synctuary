package io.synctuary.android.data

import android.os.Build
import io.synctuary.android.crypto.B64Url
import io.synctuary.android.crypto.Bip39
import io.synctuary.android.crypto.Bip39Exception
import io.synctuary.android.crypto.Ed25519
import io.synctuary.android.crypto.KeyDerivation
import io.synctuary.android.data.api.CapturingTrustManager
import io.synctuary.android.data.api.NetworkModule
import io.synctuary.android.data.api.dto.RegisterRequest
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Orchestrates the PROTOCOL §4 pairing flow end-to-end:
 *
 *   1. Resolve the server (`/api/v1/info`) → grab server_id +
 *      tls_fingerprint to use for §3.3 pinning of subsequent calls.
 *   2. Derive the device's Ed25519 keypair from
 *      (mnemonic → seed → master_key → HKDF(master_key, device_id)).
 *   3. POST `/api/v1/pair/nonce` to receive a 32-byte server nonce.
 *   4. Build the 129-byte pair payload (§4.1) and sign it.
 *   5. POST `/api/v1/pair/register` with the signature; receive the
 *      device_token.
 *   6. Persist everything in [SecretStore].
 *
 * All network and PBKDF2 work runs on Dispatchers.IO; the call site
 * can switch to it via `pair()` being a `suspend` fun.
 */
class PairingRepository(
    private val secretStore: SecretStore,
    private val rng: SecureRandom = SecureRandom(),
) {

    /**
     * Run the full pair sequence. Returns the persisted [PairedDevice]
     * snapshot on success; throws [PairingException] on any
     * deterministic failure (bad mnemonic, server reject, signature
     * mismatch). Network/IO errors propagate as their original types.
     */
    suspend fun pairWithMasterKey(
        serverUrl: String,
        masterKey: ByteArray,
        deviceName: String = defaultDeviceName(),
        platform: String = "android",
        qrFingerprint: ByteArray? = null,
    ): PairedDeviceSummary = withContext(Dispatchers.IO) {
        val deviceId = ByteArray(KeyDerivation.DEVICE_ID_LEN).also { rng.nextBytes(it) }
        val keypair: Ed25519.KeyPair = KeyDerivation.deriveDeviceKeypair(masterKey, deviceId)
        masterKey.fill(0)
        completePairing(serverUrl, deviceId, keypair, deviceName, platform, qrFingerprint)
    }

    suspend fun pair(
        serverUrl: String,
        mnemonic: String,
        deviceName: String = defaultDeviceName(),
        platform: String = "android",
    ): PairedDeviceSummary = withContext(Dispatchers.IO) {

        // ── 1. Validate mnemonic + derive master_key ─────────────────
        val seed: ByteArray = try {
            // mnemonicToEntropy verifies the BIP-39 checksum BEFORE
            // PBKDF2 — a typo'd word fails fast (~ms) instead of after
            // the 2048-iteration PBKDF (~100ms+).
            Bip39.mnemonicToEntropy(mnemonic)
            Bip39.mnemonicToSeed(mnemonic, passphrase = "")
        } catch (e: Bip39Exception) {
            throw PairingException("invalid mnemonic: ${e.message}", e)
        }

        val masterKey = KeyDerivation.deriveMasterKey(seed)

        // ── 2. Generate device_id (16 bytes) + derive keypair ────────
        val deviceId = ByteArray(KeyDerivation.DEVICE_ID_LEN).also { rng.nextBytes(it) }
        val keypair: Ed25519.KeyPair = KeyDerivation.deriveDeviceKeypair(masterKey, deviceId)

        masterKey.fill(0)
        seed.fill(0)

        completePairing(serverUrl, deviceId, keypair, deviceName, platform)
    }

    private suspend fun completePairing(
        serverUrl: String,
        deviceId: ByteArray,
        keypair: Ed25519.KeyPair,
        deviceName: String,
        platform: String,
        qrFingerprint: ByteArray? = null,
    ): PairedDeviceSummary {
        // First contact. PROTOCOL §4.1 step 2: server_fingerprint is the
        // fingerprint of the certificate actually presented, not what /info
        // claims. A QR code carries it out-of-band, so pin it from the first
        // request. Otherwise (mnemonic entry) accept the server's
        // self-signed cert once and record it — the recorded value is signed
        // into the pair payload, which the server checks against its own
        // cert, so an interceptor can't complete the pairing.
        var capture: CapturingTrustManager? = null
        val firstContact = when {
            qrFingerprint != null -> NetworkModule.create(serverUrl, qrFingerprint)
            serverUrl.startsWith("https://", ignoreCase = true) ->
                NetworkModule.createFirstContact(serverUrl).let { (api, tm) ->
                    capture = tm
                    api
                }
            else -> NetworkModule.create(serverUrl) // dev-plaintext (§10.1)
        }
        val info = try {
            firstContact.info()
        } catch (e: Exception) {
            throw PairingException("server /info call failed: ${e.message}", e)
        }
        val liveFingerprint = qrFingerprint ?: capture?.capturedFingerprint
        if (!isProtocolCompatible(info.protocol_version)) {
            throw PairingException(
                "incompatible protocol_version: server=${info.protocol_version}, " +
                    "client requires $PROTOCOL_MAJOR.$PROTOCOL_MIN_MINOR or newer within " +
                    "major $PROTOCOL_MAJOR",
            )
        }
        val serverId: ByteArray = try {
            B64Url.decode(info.server_id)
        } catch (e: IllegalArgumentException) {
            throw PairingException("malformed server_id: ${info.server_id}", e)
        }
        val advertisedFingerprint: ByteArray? =
            info.tls_fingerprint?.let { hex ->
                hexDecode(hex).also {
                    if (it.size != KeyDerivation.FINGERPRINT_LEN) {
                        throw PairingException(
                            "tls_fingerprint length ${it.size}, expected ${KeyDerivation.FINGERPRINT_LEN}",
                        )
                    }
                }
            }
        val fingerprint = resolveServerFingerprint(liveFingerprint, advertisedFingerprint)

        val pinned = NetworkModule.create(serverUrl, fingerprint)

        val nonceResp = try {
            pinned.pairNonce()
        } catch (e: Exception) {
            throw PairingException("/pair/nonce failed: ${e.message}", e)
        }
        val nonce = try {
            B64Url.decode(nonceResp.nonce)
        } catch (e: IllegalArgumentException) {
            throw PairingException("malformed nonce: ${nonceResp.nonce}", e)
        }
        if (nonce.size != KeyDerivation.NONCE_LEN) {
            throw PairingException("nonce length ${nonce.size}, expected ${KeyDerivation.NONCE_LEN}")
        }

        val effectiveFp = fingerprint ?: ByteArray(KeyDerivation.FINGERPRINT_LEN)
        val payload = KeyDerivation.buildPairPayload(
            deviceId = deviceId,
            devicePub = keypair.publicKey,
            serverFingerprint = effectiveFp,
            nonce = nonce,
        )
        val signature = Ed25519.sign(keypair.privateSeed, payload)

        val regResp = try {
            pinned.pairRegister(
                RegisterRequest(
                    nonce = nonceResp.nonce,
                    device_id = B64Url.encode(deviceId),
                    device_pub = B64Url.encode(keypair.publicKey),
                    device_name = deviceName,
                    platform = platform,
                    challenge_response = B64Url.encode(signature),
                ),
            )
        } catch (e: Exception) {
            throw PairingException("/pair/register failed: ${e.message}", e)
        }
        val deviceToken = try {
            B64Url.decode(regResp.device_token)
        } catch (e: IllegalArgumentException) {
            throw PairingException("malformed device_token: ${regResp.device_token}", e)
        }
        if (deviceToken.size != KeyDerivation.DEVICE_TOKEN_LEN) {
            throw PairingException(
                "device_token length ${deviceToken.size}, expected ${KeyDerivation.DEVICE_TOKEN_LEN}",
            )
        }

        secretStore.savePairedDevice(
            serverUrl = serverUrl,
            serverId = serverId,
            serverFingerprint = fingerprint,
            deviceId = deviceId,
            devicePub = keypair.publicKey,
            devicePriv = keypair.privateSeed,
            deviceToken = deviceToken,
        )

        // Auto-populate remote URL slot 0 from server's IPv6 GUA selection
        // so the user doesn't need to manually enter an IPv6 address.
        if (!info.ipv6_urls.isNullOrEmpty()) {
            secretStore.saveRemoteUrl(info.ipv6_urls.first(), 0)
            secretStore.saveRemoteLabel("IPv6", 0)
        }

        return PairedDeviceSummary(
            serverName = info.server_name,
            serverUrl = serverUrl,
            deviceName = deviceName,
            tokenTtlSeconds = regResp.device_token_ttl,
            fingerprintPresent = fingerprint != null,
        )
    }

    private fun defaultDeviceName(): String =
        "${Build.MANUFACTURER ?: "android"}-${Build.MODEL ?: "device"}"

    private fun hexDecode(hex: String): ByteArray {
        val s = hex.lowercase()
        require(s.length % 2 == 0) { "hex string must have even length: $s" }
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val hi = Character.digit(s[i], 16)
            val lo = Character.digit(s[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "non-hex character in: $s" }
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}

/** Caller-friendly failure type. Wraps the underlying exception so
 *  callers can `cause` for diagnostics without re-throwing. */
class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pick the server_fingerprint to sign into the pair payload and pin for
 * every later request. [live] is what the TLS connection actually
 * presented (null over dev-plaintext, where there is nothing to observe);
 * [advertised] is /info.tls_fingerprint.
 *
 * Per PROTOCOL §4.1 the live certificate is authoritative and /info is
 * only a cross-check. If both exist and disagree, something between us
 * and the server re-terminated TLS (a proxy or an interceptor): fail
 * loudly rather than pair against the wrong identity.
 */
internal fun resolveServerFingerprint(live: ByteArray?, advertised: ByteArray?): ByteArray? {
    if (live == null) return advertised
    if (advertised != null && !advertised.contentEquals(live)) {
        throw PairingException(
            "TLS certificate does not match the fingerprint the server reports in /info " +
                "(a proxy or interception between this device and the server?)",
        )
    }
    return live
}

// ── protocol_version compatibility (PROTOCOL §13) ───────────────────
//
// The pairing handshake only needs a COARSE guard here: per-feature
// differences are negotiated through /info.capabilities, not the
// version string. Everything this client relies on landed by 0.3.0
// (§10 shares, §11 pins); later minors (0.3.1 transcode, 0.3.2
// archive, 0.3.3 upload takeover) are additive and capability-gated.
//
// This used to be an exact string match against "0.2.3", which silently
// broke re-pairing the moment the server advertised 0.3.0 — already-
// paired devices kept working, so it only surfaced when a user tried to
// pair again months later.

internal const val PROTOCOL_MAJOR = 0
internal const val PROTOCOL_MIN_MINOR = 3

/**
 * Returns true when a server advertising [version] can be paired with.
 * Accepts the same major with a minor at or above what this client
 * needs; the patch component is ignored entirely. Malformed values are
 * rejected rather than assumed compatible.
 */
internal fun isProtocolCompatible(version: String?): Boolean {
    if (version.isNullOrBlank()) return false
    val parts = version.trim().split(".")
    if (parts.size < 2) return false
    val major = parts[0].toIntOrNull() ?: return false
    val minor = parts[1].toIntOrNull() ?: return false
    return major == PROTOCOL_MAJOR && minor >= PROTOCOL_MIN_MINOR
}

/** Successful-pair summary returned to the UI. The persisted device
 *  state lives in [SecretStore]; this is just enough to render a
 *  confirmation. */
data class PairedDeviceSummary(
    val serverName: String,
    val serverUrl: String,
    val deviceName: String,
    val tokenTtlSeconds: Long,
    val fingerprintPresent: Boolean,
)
