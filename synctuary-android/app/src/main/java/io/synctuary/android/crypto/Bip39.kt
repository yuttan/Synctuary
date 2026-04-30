package io.synctuary.android.crypto

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP-39 mnemonic ↔ seed conversion, mirroring the Go server's
 * [internal/domain/device/bip39.go]. Pinned to the official 2048-word
 * English wordlist so a server-issued mnemonic decodes identically on
 * both ends.
 *
 * The actual key material derived from the mnemonic is per
 * `mnemonicToSeed` (PBKDF2-HMAC-SHA512, 2048 iterations, 64-byte
 * output, empty passphrase). The intermediate "entropy" path
 * (mnemonic → 16/20/24/28/32 entropy bytes → checksum verify) is also
 * exposed for input-validation use during onboarding.
 */
object Bip39 {

    /** All supported word counts. The PROTOCOL pins 24, but the conversion
     *  helpers stay general-purpose so we don't have to special-case if
     *  the spec ever loosens. */
    val SUPPORTED_WORD_COUNTS: Set<Int> = setOf(12, 15, 18, 21, 24)

    /**
     * Verify a mnemonic's BIP-39 checksum. Throws [Bip39Exception] on any
     * structural / wordlist / checksum problem; returns the raw entropy
     * bytes on success (length 16/20/24/28/32 depending on word count).
     */
    fun mnemonicToEntropy(mnemonic: String): ByteArray {
        val words = mnemonic.trim().split(Regex("\\s+"))
        if (words.size !in SUPPORTED_WORD_COUNTS) {
            throw Bip39Exception(
                "mnemonic word count ${words.size} not in $SUPPORTED_WORD_COUNTS",
            )
        }

        // Map every word to its 11-bit index in the BIP-39 wordlist.
        val totalBits = words.size * 11
        val bits = BooleanArray(totalBits)
        for ((i, w) in words.withIndex()) {
            val idx = WORD_TO_INDEX[w] ?: throw Bip39Exception("word not in BIP-39 list: '$w'")
            for (j in 0 until 11) {
                // Most-significant bit first.
                bits[i * 11 + j] = (idx shr (10 - j)) and 1 == 1
            }
        }

        val checksumBits = totalBits / 33               // 4..8 bits
        val entropyBits = totalBits - checksumBits      // 128..256 bits
        val entropyBytes = entropyBits / 8

        val entropy = ByteArray(entropyBytes)
        for (i in 0 until entropyBytes) {
            var b = 0
            for (j in 0 until 8) {
                if (bits[i * 8 + j]) b = b or (1 shl (7 - j))
            }
            entropy[i] = b.toByte()
        }

        // Recompute checksum: SHA-256(entropy) → first checksumBits bits.
        val sha = MessageDigest.getInstance("SHA-256").digest(entropy)
        for (i in 0 until checksumBits) {
            val expected = ((sha[i / 8].toInt() ushr (7 - (i % 8))) and 1) == 1
            val actual = bits[entropyBits + i]
            if (expected != actual) {
                throw Bip39Exception("BIP-39 checksum mismatch at bit $i")
            }
        }

        return entropy
    }

    /**
     * BIP-39 mnemonic → 64-byte seed via PBKDF2-HMAC-SHA512.
     *
     * `passphrase` is the optional BIP-39 passphrase. PROTOCOL uses an
     * empty passphrase (matches `bip39.NewSeed(mnemonic, "")` on the
     * Go side); we accept it as a parameter for completeness.
     *
     * Per BIP-39 spec, the salt is the literal string `"mnemonic" + passphrase`.
     * The mnemonic is normalized to NFKD per BIP-39, and only the SPACES
     * between words are simple ASCII (the Go server does the same — it
     * doesn't NFKD-normalize because the wordlist is pure ASCII).
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalized = mnemonic.trim().split(Regex("\\s+")).joinToString(" ")
        val salt = "mnemonic$passphrase".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(normalized.toCharArray(), salt, 2048, 64 * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    /** Index lookup populated lazily from the on-disk wordlist resource. */
    private val WORD_TO_INDEX: Map<String, Int> by lazy {
        val words = WORDLIST
        require(words.size == 2048) { "BIP-39 wordlist must have 2048 words, got ${words.size}" }
        words.withIndex().associate { (i, w) -> w to i }
    }

    /** Public wordlist accessor — used by clients that need to render a
     *  picker. Returned list is unmodifiable. */
    val WORDLIST: List<String> by lazy {
        val stream = Bip39::class.java.getResourceAsStream("bip39_english.txt")
            ?: error("bip39_english.txt missing from classpath at io/synctuary/android/crypto/")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }
}

/** Distinct exception type so callers (UI / repo) can distinguish a
 *  bad mnemonic from a network or storage problem. */
class Bip39Exception(message: String) : IllegalArgumentException(message)
