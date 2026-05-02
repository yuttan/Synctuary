package io.synctuary.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStateTest {

    @Test
    fun `Running progressFraction returns determinate value when total known`() {
        val state = TransferState.Running("file.txt", 50L, 100L)
        assertEquals(0.5f, state.progressFraction, 0.001f)
    }

    @Test
    fun `Running progressFraction returns 0 at start`() {
        val state = TransferState.Running("file.txt", 0L, 200L)
        assertEquals(0f, state.progressFraction, 0.001f)
    }

    @Test
    fun `Running progressFraction returns 1 when complete`() {
        val state = TransferState.Running("file.txt", 200L, 200L)
        assertEquals(1f, state.progressFraction, 0.001f)
    }

    @Test
    fun `Running progressFraction returns indeterminate when total is null`() {
        val state = TransferState.Running("file.txt", 50L, null)
        assertEquals(-1f, state.progressFraction, 0.001f)
    }

    @Test
    fun `Running progressFraction returns indeterminate when total is zero`() {
        val state = TransferState.Running("file.txt", 0L, 0L)
        assertEquals(-1f, state.progressFraction, 0.001f)
    }

    @Test
    fun `Idle is singleton`() {
        assertTrue(TransferState.Idle === TransferState.Idle)
    }

    @Test
    fun `Done preserves fileName and location`() {
        val state = TransferState.Done("photo.jpg", "/downloads/photo.jpg")
        assertEquals("photo.jpg", state.fileName)
        assertEquals("/downloads/photo.jpg", state.location)
    }

    @Test
    fun `Failed preserves fileName and message`() {
        val state = TransferState.Failed("doc.pdf", "network error")
        assertEquals("doc.pdf", state.fileName)
        assertEquals("network error", state.message)
    }
}
