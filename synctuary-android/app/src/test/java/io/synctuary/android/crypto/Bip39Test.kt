package io.synctuary.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BIP-39 test vectors from
 * https://github.com/trezor/python-mnemonic/blob/master/vectors.json
 *
 * The first vector (all-zero entropy) is canonical; the second exercises
 * non-trivial entropy + a real seed derivation.
 */
class Bip39Test {

    @Test
    fun wordlist_has_2048_unique_entries() {
        val list = Bip39.WORDLIST
        assertEquals(2048, list.size)
        assertEquals(2048, list.toSet().size)
        assertEquals("abandon", list.first())
        assertEquals("zoo", list.last())
    }

    @Test
    fun mnemonicToEntropy_zero_entropy_24_words() {
        // 32 zero bytes of entropy → these 24 words.
        val words = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon art"
        val entropy = Bip39.mnemonicToEntropy(words)
        assertArrayEquals(ByteArray(32), entropy)
    }

    @Test
    fun mnemonicToSeed_zero_entropy_24_words_matches_bip39_vector() {
        // Empty-passphrase seed for the all-zero 24-word mnemonic. Verified
        // byte-for-byte against the Go server's `device.MnemonicToSeed`
        // (which itself wraps tyler-smith/go-bip39), so this test guards
        // wire compatibility between client and server.
        val words = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon art"
        val expectedSeedHex =
            "408b285c123836004f4b8842c89324c1" +
                "f01382450c0d439af345ba7fc49acf70" +
                "5489c6fc77dbd4e3dc1dd8cc6bc9f043" +
                "db8ada1e243c4a0eafb290d399480840"
        val seed = Bip39.mnemonicToSeed(words)
        assertEquals(expectedSeedHex, HexUtil.encode(seed))
    }

    @Test
    fun mnemonicToEntropy_known_vector_24_words() {
        // Trezor BIP-39 vector: entropy = 0x80808080... (32 bytes of 0x80).
        // The 24th word is the checksum word — for this entropy it must
        // be "bless", not any other -ess word.
        val words = "letter advice cage absurd amount doctor acoustic avoid " +
            "letter advice cage absurd amount doctor acoustic avoid " +
            "letter advice cage absurd amount doctor acoustic bless"
        val expected = HexUtil.decode("80".repeat(32))
        val entropy = Bip39.mnemonicToEntropy(words)
        assertArrayEquals(expected, entropy)
    }

    @Test
    fun rejects_unknown_word() {
        assertThrows(Bip39Exception::class.java) {
            // "invalidword" is not in the wordlist.
            Bip39.mnemonicToEntropy(
                "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon invalidword",
            )
        }
    }

    @Test
    fun rejects_bad_word_count() {
        assertThrows(Bip39Exception::class.java) {
            Bip39.mnemonicToEntropy("abandon abandon abandon")
        }
    }

    @Test
    fun rejects_bad_checksum() {
        // Same 24 words as the zero-entropy vector but with the LAST
        // word swapped — the last-word checksum bits won't match.
        val bad = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon"
        // "art" is the only checksum-valid 24th word for that prefix;
        // any other word fails the checksum.
        assertThrows(Bip39Exception::class.java) {
            Bip39.mnemonicToEntropy(bad)
        }
    }

    @Test
    fun whitespace_normalization_is_robust() {
        val words = "  abandon  abandon abandon abandon abandon abandon" +
            "\nabandon abandon abandon abandon abandon abandon\t" +
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon art "
        val entropy = Bip39.mnemonicToEntropy(words)
        assertTrue(entropy.all { it == 0.toByte() })
    }
}
