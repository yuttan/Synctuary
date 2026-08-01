package io.synctuary.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pairing-time protocol_version check. The original bug was
 * an exact match against "0.2.3", which broke re-pairing against every
 * server from 0.3.0 onward.
 */
class ProtocolCompatTest {

    @Test
    fun acceptsCurrentServerVersions() {
        // Every minor the server has shipped since the client's floor.
        for (v in listOf("0.3.0", "0.3.1", "0.3.2", "0.3.3")) {
            assertTrue("should accept $v", isProtocolCompatible(v))
        }
    }

    @Test
    fun acceptsFutureAdditiveMinors() {
        // Additions are capability-gated, so a newer minor must not
        // block pairing — that is exactly how this broke before.
        assertTrue(isProtocolCompatible("0.4.0"))
        assertTrue(isProtocolCompatible("0.10.2"))
    }

    @Test
    fun ignoresPatchComponent() {
        assertTrue(isProtocolCompatible("0.3"))
        assertTrue(isProtocolCompatible("0.3.99"))
    }

    @Test
    fun rejectsTooOldMinor() {
        assertFalse(isProtocolCompatible("0.2.3"))
        assertFalse(isProtocolCompatible("0.1.0"))
    }

    @Test
    fun rejectsDifferentMajor() {
        assertFalse(isProtocolCompatible("1.0.0"))
        assertFalse(isProtocolCompatible("1.3.0"))
    }

    @Test
    fun rejectsMalformed() {
        assertFalse(isProtocolCompatible(null))
        assertFalse(isProtocolCompatible(""))
        assertFalse(isProtocolCompatible("   "))
        assertFalse(isProtocolCompatible("0"))
        assertFalse(isProtocolCompatible("x.y.z"))
        assertFalse(isProtocolCompatible("0.x"))
    }
}
