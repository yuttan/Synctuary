package io.synctuary.android.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869).
 *
 * Two-step construction:
 *   - Extract: PRK = HMAC-SHA256(salt, ikm)
 *   - Expand:  T(0) = empty; T(i) = HMAC-SHA256(PRK, T(i-1) || info || byte(i))
 *              OKM = T(1) || T(2) || ... truncated to length L
 *
 * Implemented inline rather than via a third-party dep so the security
 * surface stays auditable; PROTOCOL §3.2 / §3.3 use this exclusively
 * with empty/short salt+info and tight output sizes (32 bytes), so the
 * full RFC complexity isn't needed.
 */
object Hkdf {

    private const val ALG = "HmacSHA256"
    private const val HASH_LEN = 32 // SHA-256 output, in bytes
    private const val MAX_LENGTH = 255 * HASH_LEN

    /**
     * Derive `length` bytes from `ikm` using `salt` (may be empty) and
     * `info` (may be empty), per RFC 5869.
     *
     * @throws IllegalArgumentException if length is non-positive or
     *         exceeds 255 * 32 = 8160 bytes.
     */
    fun derive(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..MAX_LENGTH) {
            "HKDF length must be in 1..$MAX_LENGTH, got $length"
        }

        // Extract: PRK = HMAC-SHA256(salt, ikm). RFC 5869 §2.2 says
        // "if not provided, it is set to a string of HashLen zeros";
        // honor that for a salt-less call site.
        val effSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val prk = hmacSha256(effSalt, ikm)

        // Expand: feed back T(i-1) || info || i into the HMAC.
        val n = (length + HASH_LEN - 1) / HASH_LEN
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var written = 0
        for (i in 1..n) {
            val mac = Mac.getInstance(ALG)
            mac.init(SecretKeySpec(prk, ALG))
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val take = minOf(HASH_LEN, length - written)
            System.arraycopy(t, 0, okm, written, take)
            written += take
        }
        return okm
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALG)
        mac.init(SecretKeySpec(key, ALG))
        return mac.doFinal(data)
    }
}
