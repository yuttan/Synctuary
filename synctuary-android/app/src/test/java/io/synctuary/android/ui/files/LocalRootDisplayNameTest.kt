package io.synctuary.android.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SAF tree URIs are the only handle we get for a granted folder, and
 * DocumentFile.name is null for some providers — so the fallback that
 * parses the document id has to hold up on its own.
 */
class LocalRootDisplayNameTest {

    @Test
    fun prefersDocumentFileName() {
        assertEquals("Camera", localRootDisplayName("Camera", "primary:Pictures/Camera"))
    }

    @Test
    fun fallsBackToLastSegmentOfDocumentId() {
        assertEquals("Camera", localRootDisplayName(null, "primary:Pictures/Camera"))
        assertEquals("Download", localRootDisplayName("", "primary:Download"))
    }

    @Test
    fun handlesWholeVolumeGrant() {
        assertEquals("Internal storage", localRootDisplayName(null, "primary:"))
    }

    @Test
    fun handlesRemovableVolume() {
        // SD cards use a UUID-ish volume id instead of "primary".
        assertEquals("DCIM", localRootDisplayName(null, "1A2B-3C4D:DCIM"))
        assertEquals("1A2B-3C4D", localRootDisplayName(null, "1A2B-3C4D:"))
    }

    @Test
    fun toleratesTrailingSlash() {
        assertEquals("Camera", localRootDisplayName(null, "primary:Pictures/Camera/"))
    }

    @Test
    fun fallsBackWhenSegmentMissing() {
        assertEquals("Folder", localRootDisplayName(null, null))
    }
}
