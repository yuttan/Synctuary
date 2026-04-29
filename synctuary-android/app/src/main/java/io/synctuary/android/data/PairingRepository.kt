package io.synctuary.android.data

import android.os.Build
import io.synctuary.android.crypto.B64Url
import io.synctuary.android.crypto.Bip39
import io.synctuary.android.crypto.Bip39Exception
import io.synctuary.android.crypto.Ed25519
import io.synctuary.android.crypto.KeyDerivation
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

        // Ephemeral; we no longer need master_key after deriving the
        // device keypair. Best-effort wipe — JVM may copy it elsewhere
        // before GC, but this is the right intent.
        masterKey.fill(0)
        seed.fill(0)

        // ── 3. /info to get fingerprint for §3.3 pinning ─────────────
        // First hit is unpinned: we have nothing to pin against until
        // the response arrives. The user's choice to trust the
        // network at this moment is the trust anchor (mirrors the
        // first-pair model used by Signal, WireGuard, etc.).
        val unpinned = NetworkModule.create(serverUrl)
        val info = try {
            unpinned.info()
        } catch (e: Exception) {
            throw PairingException("server /info call failed: ${e.message}", e)
        }
        if (info.protocol_version != "0.2.3") {
            throw PairingException(
                "incompatible protocol_version: server=${info.protocol_version}, client=0.2.3",
            )
        }
        val serverId: ByteArray = try {
            B64Url.decode(info.server_id)
        } catch (e: IllegalArgumentException) {
            throw PairingException("malformed server_id: ${info.server_id}", e)
        }
        val fingerprint: ByteArray? =
            info.tls_fingerprint?.let { hex ->
                hexDecode(hex).also {
                    if (it.size != KeyDerivation.FINGERPRINT_LEN) {
                        throw PairingException(
                            "tls_fingerprint length ${it.size}, expected ${KeyDerivation.FINGERPRINT_LEN}",
                        )
                    }
                }
            }

        // ── 4. Pin subsequent calls (when a fingerprint is available) ─
        val pinned = NetworkModule.create(serverUrl, fingerprint)

        // ── 5. /pair/nonce → server-issued challenge ─────────────────
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

        // ── 6. Build pair payload + sign ─────────────────────────────
        // For dev-plaintext servers (§10.1) the fingerprint may be
        // 32 zero bytes; the protocol allows that and the server
        // verifies signatures against the same all-zero placeholder.
        val effectiveFp = fingerprint ?: ByteArray(KeyDerivation.FINGERPRINT_LEN)
        val payload = KeyDerivation.buildPairPayload(
            deviceId = deviceId,
            devicePub = keypair.publicKey,
            serverFingerprint = effectiveFp,
            nonce = nonce,
        )
        val signature = Ed25519.sign(keypair.privateSeed, payload)

        // ── 7. /pair/register → receive device_token ─────────────────
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

        // ── 8. Persist + return summary ──────────────────────────────
        secretStore.savePairedDevice(
            serverUrl = serverUrl,
            serverId = serverId,
            serverFingerprint = fingerprint,
            deviceId = deviceId,
            devicePub = keypair.publicKey,
            devicePriv = keypair.privateSeed,
            deviceToken = deviceToken,
        )

        PairedDeviceSummary(
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
