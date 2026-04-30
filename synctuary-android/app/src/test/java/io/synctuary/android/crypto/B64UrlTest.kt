package io.synctuary.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sanity checks on the URL-safe, no-padding base64 helpers used for
 *  every binary field on the wire. Server-side parity is implied by
 *  the Go side using the same RFC 4648 §5 alphabet. */
class B64UrlTest {

    @Test
    fun roundtrip_random_lengths() {
        for (len in 0..40) {
            val input = ByteArray(len) { (it * 13 + 7).toByte() }
            val s = B64Url.encode(input)
            // No '=' padding, ever.
            assertEquals(false, s.contains('='))
            val out = B64Url.decode(s)
            assertArrayEquals(input, out)
        }
    }

    @Test
    fun standard_alphabet_used() {
        // 32 bytes whose stdlib-base64 form contains '+', '/', '='. The
        // url-safe encoder must replace + with - and / with _, and drop
        // padding.
        val input = ByteArray(32) { (it * 17 + 3).toByte() }
        val s = B64Url.encode(input)
        assertEquals(false, s.contains('+'))
        assertEquals(false, s.contains('/'))
        assertEquals(false, s.contains('='))
    }

    @Test
    fun encode_known_value_matches_protocol_expectation() {
        // 16 zero bytes → "AAAAAAAAAAAAAAAAAAAAAA" (22 chars).
        val s = B64Url.encode(ByteArray(16))
        assertEquals(22, s.length)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", s)
    }
}
