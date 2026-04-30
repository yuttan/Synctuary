package io.synctuary.android.crypto

/** Tiny hex helpers for test-vector inputs. Lowercase, no separators. */
internal object HexUtil {
    fun decode(hex: String): ByteArray {
        val s = hex.lowercase().replace(" ", "")
        require(s.length % 2 == 0) { "odd-length hex: $s" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "non-hex char: $s" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun encode(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
