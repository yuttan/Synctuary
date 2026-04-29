package io.synctuary.android.crypto

import java.security.MessageDigest

/**
 * High-level wrappers binding the building blocks (BIP-39, HKDF,
 * Ed25519) to the exact constants pinned by PROTOCOL v0.2.3 §3 + §4.1.
 *
 * Any change to a constant here is a wire-incompatible spec change —
 * MUST coincide with a protocol version bump and a server-side update
 * to internal/adapter/infrastructure/crypto/crypto.go.
 */
object KeyDerivation {

    // PROTOCOL §3.2 — domain-separation labels for HKDF.
    private const val SALT_MASTER_V1 = "synctuary-v1"
    private const val INFO_MASTER = "master"
    private const val INFO_DEVICE_ED25519 = "device-ed25519"

    // PROTOCOL §4.1 — magic prefix for the pairing payload.
    private const val PAIR_MAGIC = "synctuary-pair-v1"

    // Fixed sizes (PROTOCOL §3 / §4).
    const val SEED_LEN = 64
    const val MASTER_KEY_LEN = 32
    const val DEVICE_ID_LEN = 16
    const val FINGERPRINT_LEN = 32
    const val NONCE_LEN = 32
    const val DEVICE_TOKEN_LEN = 32

    /** payload = magic(17) || device_id(16) || device_pub(32)
     *           || fingerprint(32) || nonce(32) = 129 bytes. */
    const val PAIR_PAYLOAD_LEN: Int =
        17 + DEVICE_ID_LEN + Ed25519.PUBLIC_KEY_SIZE + FINGERPRINT_LEN + NONCE_LEN

    /**
     * `master_key = HKDF-SHA256(seed, salt="synctuary-v1",
     *                            info="master", L=32)`.
     *
     * `seed` MUST be the 64-byte BIP-39 seed (i.e., output of
     * [Bip39.mnemonicToSeed] with empty passphrase).
     */
    fun deriveMasterKey(seed: ByteArray): ByteArray {
        require(seed.size == SEED_LEN) {
            "deriveMasterKey: seed length ${seed.size}, expected $SEED_LEN"
        }
        return Hkdf.derive(
            ikm = seed,
            salt = SALT_MASTER_V1.toByteArray(Charsets.US_ASCII),
            info = INFO_MASTER.toByteArray(Charsets.US_ASCII),
            length = MASTER_KEY_LEN,
        )
    }

    /**
     * `device_seed = HKDF-SHA256(master_key, salt=device_id,
     *                             info="device-ed25519", L=32)`
     * then derive an Ed25519 keypair from `device_seed`.
     */
    fun deriveDeviceKeypair(
        masterKey: ByteArray,
        deviceId: ByteArray,
    ): Ed25519.KeyPair {
        require(masterKey.size == MASTER_KEY_LEN) {
            "deriveDeviceKeypair: master_key length ${masterKey.size}, expected $MASTER_KEY_LEN"
        }
        require(deviceId.size == DEVICE_ID_LEN) {
            "deriveDeviceKeypair: device_id length ${deviceId.size}, expected $DEVICE_ID_LEN"
        }
        val seed = Hkdf.derive(
            ikm = masterKey,
            salt = deviceId,
            info = INFO_DEVICE_ED25519.toByteArray(Charsets.US_ASCII),
            length = Ed25519.PRIVATE_SEED_SIZE,
        )
        return Ed25519.keypairFromSeed(seed)
    }

    /**
     * Build the 129-byte pairing payload (PROTOCOL §4.1).
     *
     * No length prefixes, no separators — exact concatenation. All
     * variable-length inputs are validated against the spec sizes.
     */
    fun buildPairPayload(
        deviceId: ByteArray,
        devicePub: ByteArray,
        serverFingerprint: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        require(deviceId.size == DEVICE_ID_LEN) {
            "buildPairPayload: device_id length ${deviceId.size}, expected $DEVICE_ID_LEN"
        }
        require(devicePub.size == Ed25519.PUBLIC_KEY_SIZE) {
            "buildPairPayload: device_pub length ${devicePub.size}, expected ${Ed25519.PUBLIC_KEY_SIZE}"
        }
        require(serverFingerprint.size == FINGERPRINT_LEN) {
            "buildPairPayload: fingerprint length ${serverFingerprint.size}, expected $FINGERPRINT_LEN"
        }
        require(nonce.size == NONCE_LEN) {
            "buildPairPayload: nonce length ${nonce.size}, expected $NONCE_LEN"
        }
        val magic = PAIR_MAGIC.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(PAIR_PAYLOAD_LEN)
        var off = 0
        magic.copyInto(out, off); off += magic.size
        deviceId.copyInto(out, off); off += deviceId.size
        devicePub.copyInto(out, off); off += devicePub.size
        serverFingerprint.copyInto(out, off); off += serverFingerprint.size
        nonce.copyInto(out, off); off += nonce.size
        check(off == PAIR_PAYLOAD_LEN) {
            "buildPairPayload: assembled $off bytes, expected $PAIR_PAYLOAD_LEN"
        }
        return out
    }

    /** SHA-256(token). The server stores only this digest, never the
     *  raw token (PROTOCOL §4.3). Used by the client for self-checks
     *  and could serve a future "rotate token" flow. */
    fun hashToken(token: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token)
}
