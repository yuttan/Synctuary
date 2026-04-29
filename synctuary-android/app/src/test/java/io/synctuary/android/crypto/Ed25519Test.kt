package io.synctuary.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 8032 §7.1 test vector "Test 1": the canonical empty-message
 * Ed25519 example. Pinning this here means a future BC upgrade /
 * Android-platform Ed25519 swap can never silently produce different
 * keys or signatures.
 */
class Ed25519Test {

    private val seed = HexUtil.decode(
        "9d61b19deffd5a60ba844af492ec2cc4" +
            "4449c5697b326919703bac031cae7f60",
    )
    private val expectedPub = HexUtil.decode(
        "d75a980182b10ab7d54bfed3c964073a" +
            "0ee172f3daa62325af021a68f707511a",
    )
    // Signature over an empty message under the above key.
    private val expectedSig = HexUtil.decode(
        "e5564300c360ac729086e2cc806e828a" +
            "84877f1eb8e5d974d873e06522490155" +
            "5fb8821590a33bacc61e39701cf9b46b" +
            "d25bf5f0595bbe24655141438e7a100b",
    )

    @Test
    fun keypairFromSeed_matches_rfc8032_test1() {
        val kp = Ed25519.keypairFromSeed(seed)
        assertEquals(Ed25519.PUBLIC_KEY_SIZE, kp.publicKey.size)
        assertEquals(Ed25519.PRIVATE_SEED_SIZE, kp.privateSeed.size)
        assertArrayEquals(expectedPub, kp.publicKey)
        assertArrayEquals(seed, kp.privateSeed)
    }

    @Test
    fun sign_matches_rfc8032_test1() {
        val sig = Ed25519.sign(seed, message = ByteArray(0))
        assertEquals(Ed25519.SIGNATURE_SIZE, sig.size)
        assertArrayEquals(expectedSig, sig)
    }

    @Test
    fun verify_accepts_correct_signature() {
        assertTrue(Ed25519.verify(expectedPub, ByteArray(0), expectedSig))
    }

    @Test
    fun verify_rejects_tampered_message() {
        val msg = byteArrayOf(0x01)  // any non-empty message
        assertFalse(Ed25519.verify(expectedPub, msg, expectedSig))
    }

    @Test
    fun verify_rejects_tampered_signature() {
        val tampered = expectedSig.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(Ed25519.verify(expectedPub, ByteArray(0), tampered))
    }

    @Test
    fun verify_rejects_wrong_size_inputs() {
        assertFalse(Ed25519.verify(ByteArray(31), ByteArray(0), expectedSig))
        assertFalse(Ed25519.verify(expectedPub, ByteArray(0), ByteArray(63)))
    }

    @Test
    fun keypairFromSeed_rejects_wrong_size() {
        assertThrows(IllegalArgumentException::class.java) {
            Ed25519.keypairFromSeed(ByteArray(31))
        }
    }

    @Test
    fun sign_then_verify_roundtrip() {
        val kp = Ed25519.keypairFromSeed(ByteArray(32) { it.toByte() })
        val msg = "synctuary-pair-v1".toByteArray()
        val sig = Ed25519.sign(kp.privateSeed, msg)
        assertTrue(Ed25519.verify(kp.publicKey, msg, sig))
    }
}
