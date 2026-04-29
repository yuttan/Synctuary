package io.synctuary.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * High-level checks on [KeyDerivation] — the assertions we actually
 * care about for protocol compatibility:
 *
 *   1. Reproducibility: same (mnemonic, deviceId) ⇒ same keypair across runs.
 *   2. device_id sensitivity: changing one byte changes the keypair.
 *   3. Pair-payload round-trip: build → sign → verify.
 *   4. Layout: payload is exactly 129 bytes, prefix matches the magic.
 *
 * Cross-language byte-for-byte parity with the Go server is verified
 * indirectly: the underlying primitives (HKDF + Ed25519) match RFC
 * vectors via [HkdfTest] and [Ed25519Test]; the constants used here
 * (saltMasterV1, infoMaster, infoDeviceEd25519) are pinned in source
 * and reviewed against `crypto.go` in the same PR.
 */
class KeyDerivationTest {

    private val zeroEntropyMnemonic = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon art"

    private val deviceIdA = ByteArray(16) { 0x01 }
    private val deviceIdB = ByteArray(16) { 0x02 }

    @Test
    fun deriveDeviceKeypair_is_reproducible() {
        val seed = Bip39.mnemonicToSeed(zeroEntropyMnemonic)
        val mk = KeyDerivation.deriveMasterKey(seed)
        val kp1 = KeyDerivation.deriveDeviceKeypair(mk, deviceIdA)
        val kp2 = KeyDerivation.deriveDeviceKeypair(mk, deviceIdA)
        assertEquals(HexUtil.encode(kp1.publicKey), HexUtil.encode(kp2.publicKey))
        assertEquals(HexUtil.encode(kp1.privateSeed), HexUtil.encode(kp2.privateSeed))
    }

    @Test
    fun device_id_changes_keypair() {
        val seed = Bip39.mnemonicToSeed(zeroEntropyMnemonic)
        val mk = KeyDerivation.deriveMasterKey(seed)
        val kpA = KeyDerivation.deriveDeviceKeypair(mk, deviceIdA)
        val kpB = KeyDerivation.deriveDeviceKeypair(mk, deviceIdB)
        assertNotEquals(HexUtil.encode(kpA.publicKey), HexUtil.encode(kpB.publicKey))
    }

    @Test
    fun pair_payload_is_exactly_129_bytes_with_correct_prefix() {
        val payload = KeyDerivation.buildPairPayload(
            deviceId = ByteArray(16),
            devicePub = ByteArray(32),
            serverFingerprint = ByteArray(32),
            nonce = ByteArray(32),
        )
        assertEquals(129, payload.size)
        val magic = "synctuary-pair-v1".toByteArray(Charsets.US_ASCII)
        assertEquals(17, magic.size)
        for (i in magic.indices) {
            assertEquals(magic[i], payload[i])
        }
    }

    @Test
    fun pair_payload_round_trip_signs_and_verifies() {
        val seed = Bip39.mnemonicToSeed(zeroEntropyMnemonic)
        val mk = KeyDerivation.deriveMasterKey(seed)
        val kp = KeyDerivation.deriveDeviceKeypair(mk, deviceIdA)
        val payload = KeyDerivation.buildPairPayload(
            deviceId = deviceIdA,
            devicePub = kp.publicKey,
            serverFingerprint = ByteArray(32) { 0x55 },
            nonce = ByteArray(32) { 0xAA.toByte() },
        )
        val sig = Ed25519.sign(kp.privateSeed, payload)
        assertTrue(Ed25519.verify(kp.publicKey, payload, sig))
    }

    @Test
    fun deriveMasterKey_rejects_wrong_seed_size() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.deriveMasterKey(ByteArray(63))
        }
    }

    @Test
    fun deriveDeviceKeypair_rejects_wrong_sizes() {
        val mk = ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.deriveDeviceKeypair(ByteArray(31), ByteArray(16))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.deriveDeviceKeypair(mk, ByteArray(15))
        }
    }

    @Test
    fun buildPairPayload_rejects_wrong_sizes() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.buildPairPayload(ByteArray(15), ByteArray(32), ByteArray(32), ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.buildPairPayload(ByteArray(16), ByteArray(31), ByteArray(32), ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.buildPairPayload(ByteArray(16), ByteArray(32), ByteArray(31), ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.buildPairPayload(ByteArray(16), ByteArray(32), ByteArray(32), ByteArray(31))
        }
    }

    @Test
    fun hashToken_returns_32_bytes() {
        val h = KeyDerivation.hashToken(ByteArray(32))
        assertEquals(32, h.size)
    }
}
