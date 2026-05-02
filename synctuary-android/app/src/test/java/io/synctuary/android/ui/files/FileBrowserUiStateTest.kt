package io.synctuary.android.ui.files

import io.synctuary.android.data.api.dto.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class FileBrowserUiStateTest {

    private fun fileEntry(name: String, type: String = "file") = FileEntry(
        name = name,
        type = type,
        modified_at = 1700000000L,
    )

    private val sampleEntries = listOf(
        fileEntry("photo.jpg"),
        fileEntry("document.pdf"),
        fileEntry("backup", "dir"),
        fileEntry("README.md"),
        fileEntry("Photo_2024.png"),
    )

    @Test
    fun `filteredEntries returns all when searchQuery is blank`() {
        val state = FileBrowserUiState(entries = sampleEntries, searchQuery = "")
        assertEquals(sampleEntries, state.filteredEntries)
    }

    @Test
    fun `filteredEntries returns all when searchQuery is whitespace`() {
        val state = FileBrowserUiState(entries = sampleEntries, searchQuery = "   ")
        assertEquals(sampleEntries, state.filteredEntries)
    }

    @Test
    fun `filteredEntries filters by case-insensitive substring`() {
        val state = FileBrowserUiState(entries = sampleEntries, searchQuery = "photo")
        val names = state.filteredEntries.map { it.name }
        assertEquals(listOf("photo.jpg", "Photo_2024.png"), names)
    }

    @Test
    fun `filteredEntries includes directories`() {
        val state = FileBrowserUiState(entries = sampleEntries, searchQuery = "back")
        val names = state.filteredEntries.map { it.name }
        assertEquals(listOf("backup"), names)
    }

    @Test
    fun `filteredEntries returns empty when no match`() {
        val state = FileBrowserUiState(entries = sampleEntries, searchQuery = "xyz")
        assertEquals(emptyList<FileEntry>(), state.filteredEntries)
    }

    @Test
    fun `filteredEntries matches file extension`() {
        val state = FileBrowserUiState(entries = sampleEntries, searchQuery = ".pdf")
        val names = state.filteredEntries.map { it.name }
        assertEquals(listOf("document.pdf"), names)
    }

    @Test
    fun `filteredEntries works with empty entries list`() {
        val state = FileBrowserUiState(entries = emptyList(), searchQuery = "test")
        assertEquals(emptyList<FileEntry>(), state.filteredEntries)
    }
}
