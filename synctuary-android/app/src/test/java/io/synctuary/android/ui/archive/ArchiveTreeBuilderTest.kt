package io.synctuary.android.ui.archive

import io.synctuary.android.data.api.dto.ArchiveEntryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the client-side archive directory-tree builder.
 * No Android or network dependency — this exercises only path splitting,
 * intermediate-directory synthesis, and natural sorting.
 */
class ArchiveTreeBuilderTest {

    private fun entry(path: String, dir: Boolean = false, size: Long? = if (dir) null else 1L) =
        ArchiveEntryDto(path = path, size = size, dir = dir)

    @Test
    fun root_lists_top_level_dirs_and_files() {
        val entries = listOf(
            entry("readme.txt", size = 5),
            entry("images/001.jpg", size = 6),
            entry("images/002.jpg", size = 6),
        )
        val root = ArchiveTreeBuilder.childrenOf(entries, "")
        assertEquals(2, root.size)
        // Directories are listed before files.
        assertEquals("images", root[0].name)
        assertTrue(root[0].isDir)
        assertEquals("images", root[0].path)
        assertEquals("readme.txt", root[1].name)
        assertFalse(root[1].isDir)
        assertEquals(5L, root[1].size)
    }

    @Test
    fun intermediate_directories_are_synthesized() {
        // Only a deeply nested file exists — every ancestor dir must appear.
        val entries = listOf(entry("a/b/c/deep.jpg"))
        val root = ArchiveTreeBuilder.childrenOf(entries, "")
        assertEquals(1, root.size)
        assertEquals("a", root[0].name)
        assertTrue(root[0].isDir)

        val a = ArchiveTreeBuilder.childrenOf(entries, "a")
        assertEquals(listOf("b"), a.map { it.name })
        assertTrue(a[0].isDir)
        assertEquals("a/b", a[0].path)

        val b = ArchiveTreeBuilder.childrenOf(entries, "a/b")
        assertEquals(listOf("c"), b.map { it.name })

        val c = ArchiveTreeBuilder.childrenOf(entries, "a/b/c")
        assertEquals(listOf("deep.jpg"), c.map { it.name })
        assertFalse(c[0].isDir)
        assertEquals("a/b/c/deep.jpg", c[0].path)
    }

    @Test
    fun explicit_directory_entry_and_files_do_not_duplicate() {
        val entries = listOf(
            entry("images/", dir = true),
            entry("images/001.jpg"),
        )
        val root = ArchiveTreeBuilder.childrenOf(entries, "")
        assertEquals(1, root.size)
        assertEquals("images", root[0].name)
        assertTrue(root[0].isDir)
    }

    @Test
    fun children_are_natural_sorted_dirs_first() {
        val entries = listOf(
            entry("file10.txt"),
            entry("file2.txt"),
            entry("file1.txt"),
            entry("zdir/x"),
            entry("adir/y"),
        )
        val root = ArchiveTreeBuilder.childrenOf(entries, "")
        // Dirs (natural-sorted) then files (natural-sorted: 1,2,10).
        assertEquals(
            listOf("adir", "zdir", "file1.txt", "file2.txt", "file10.txt"),
            root.map { it.name },
        )
    }

    @Test
    fun image_entry_paths_filters_and_sorts_all_images() {
        val entries = listOf(
            entry("ch1/010.jpg"),
            entry("ch1/002.png"),
            entry("ch1/notes.txt"),
            entry("ch2/001.webp"),
            entry("cover.gif"),
            entry("ch1/", dir = true),
        )
        val images = ArchiveTreeBuilder.imageEntryPaths(entries)
        assertEquals(
            listOf("ch1/002.png", "ch1/010.jpg", "ch2/001.webp", "cover.gif"),
            images,
        )
    }

    @Test
    fun empty_archive_yields_no_children() {
        assertTrue(ArchiveTreeBuilder.childrenOf(emptyList(), "").isEmpty())
    }

    @Test
    fun entry_kind_classifies_by_extension() {
        assertEquals(EntryKind.IMAGE, entryKind("a/b/c.JPG"))
        assertEquals(EntryKind.VIDEO, entryKind("clip.mkv"))
        assertEquals(EntryKind.AUDIO, entryKind("song.flac"))
        assertEquals(EntryKind.OTHER, entryKind("doc.pdf"))
        assertEquals(EntryKind.OTHER, entryKind("noext"))
    }

    @Test
    fun archive_mime_detection() {
        assertTrue(isArchiveMime("application/zip"))
        assertTrue(isArchiveMime("application/vnd.comicbook+zip"))
        assertFalse(isArchiveMime("image/jpeg"))
        assertFalse(isArchiveMime(null))
    }
}
