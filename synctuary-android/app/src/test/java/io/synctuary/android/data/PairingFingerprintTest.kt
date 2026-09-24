package io.synctuary.android.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards PROTOCOL §4.1 step 2: the fingerprint signed into the pair
 * payload is the one the live TLS connection presented. The original bug
 * took it from /info after a system-trust-store handshake, which made
 * mnemonic pairing impossible against the default self-signed cert.
 */
class PairingFingerprintTest {

    private val live = ByteArray(32) { 0x11 }
    private val other = ByteArray(32) { 0x22 }

    @Test
    fun liveCertificateIsAuthoritative() {
        assertArrayEquals(live, resolveServerFingerprint(live, live))
    }

    @Test
    fun liveUsedWhenInfoOmitsFingerprint() {
        assertArrayEquals(live, resolveServerFingerprint(live, null))
    }

    @Test(expected = PairingException::class)
    fun mismatchWithInfoAbortsPairing() {
        resolveServerFingerprint(live, other)
    }

    @Test
    fun plaintextFallsBackToAdvertisedValue() {
        // dev-plaintext has no certificate to observe; keep what /info says.
        assertArrayEquals(other, resolveServerFingerprint(null, other))
        assertNull(resolveServerFingerprint(null, null))
    }
}
