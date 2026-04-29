package io.synctuary.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RFC 5869 §A.1 / A.2 test vectors for HKDF-SHA-256.
 * https://datatracker.ietf.org/doc/html/rfc5869#appendix-A
 */
class HkdfTest {

    @Test
    fun rfc5869_a1_basic_sha256() {
        val ikm = HexUtil.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = HexUtil.decode("000102030405060708090a0b0c")
        val info = HexUtil.decode("f0f1f2f3f4f5f6f7f8f9")
        val expectedOkm = HexUtil.decode(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
        val out = Hkdf.derive(ikm, salt, info, length = 42)
        assertArrayEquals(expectedOkm, out)
    }

    @Test
    fun rfc5869_a2_long_input() {
        val ikm = HexUtil.decode(
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f" +
                "303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f",
        )
        val salt = HexUtil.decode(
            "606162636465666768696a6b6c6d6e6f" +
                "707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
        )
        val info = HexUtil.decode(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
        )
        val expectedOkm = HexUtil.decode(
            "b11e398dc80327a1c8e7f78c596a4934" +
                "4f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f" +
                "1d87",
        )
        val out = Hkdf.derive(ikm, salt, info, length = 82)
        assertArrayEquals(expectedOkm, out)
    }

    @Test
    fun rejects_non_positive_length() {
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.derive(byteArrayOf(1), byteArrayOf(), byteArrayOf(), 0)
        }
    }

    @Test
    fun rejects_overlong_length() {
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.derive(byteArrayOf(1), byteArrayOf(), byteArrayOf(), 255 * 32 + 1)
        }
    }

    @Test
    fun empty_salt_is_treated_as_zero_block() {
        // RFC 5869 §2.2: when no salt is provided, it MUST be set to a
        // string of HashLen zeros. We exercise that path by hand-feeding
        // both forms and asserting the same OKM.
        val ikm = HexUtil.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val info = HexUtil.decode("f0f1f2f3f4f5f6f7f8f9")
        val out1 = Hkdf.derive(ikm, salt = byteArrayOf(), info = info, length = 42)
        val out2 = Hkdf.derive(ikm, salt = ByteArray(32), info = info, length = 42)
        assertEquals(HexUtil.encode(out1), HexUtil.encode(out2))
    }
}
