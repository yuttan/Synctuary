package io.synctuary.android.ui.archive

import io.synctuary.android.data.api.dto.ArchiveEntryDto
import io.synctuary.android.ui.files.NaturalOrderComparator

/** MIME types the file browser treats as browsable archives (§6.9). */
val ARCHIVE_MIMES: Set<String> = setOf(
    "application/zip",
    "application/vnd.rar",
    "application/x-7z-compressed",
    "application/vnd.comicbook+zip",
    "application/vnd.comicbook-rar",
)

fun isArchiveMime(mime: String?): Boolean = mime != null && mime in ARCHIVE_MIMES

/** Coarse media category of an archive entry, derived from its extension. */
enum class EntryKind { IMAGE, VIDEO, AUDIO, OTHER }

private val IMAGE_EXTS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tiff", "tif", "jxl",
)
private val VIDEO_EXTS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "3gp", "mpg", "mpeg", "ogv",
    "ts", "m2ts", "mts", "vob",
)
private val AUDIO_EXTS = setOf(
    "mp3", "flac", "aac", "ogg", "opus", "wav", "m4a", "wma", "aiff", "alac", "ape",
)

fun entryKind(path: String): EntryKind {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXTS -> EntryKind.IMAGE
        in VIDEO_EXTS -> EntryKind.VIDEO
        in AUDIO_EXTS -> EntryKind.AUDIO
        else -> EntryKind.OTHER
    }
}

fun isImageEntry(path: String): Boolean = entryKind(path) == EntryKind.IMAGE

/** One immediate child (folder or file) of a directory within an archive. */
data class ArchiveItem(
    val name: String,   // last path segment (display name)
    val path: String,   // full archive-internal path
    val isDir: Boolean,
    val size: Long?,
)

/**
 * Builds a client-side directory tree from the server's FLAT archive
 * listing. Intermediate directories that have no explicit entry of their
 * own are synthesized from the paths of their descendants, so a listing of
 * only "a/b/c.jpg" still yields a browsable "a" → "b" → "c.jpg" hierarchy.
 *
 * Extracted as a pure object so [childrenOf] is unit-testable without any
 * Android or network dependency.
 */
object ArchiveTreeBuilder {

    /**
     * Returns the immediate children of [dir] (archive-internal path,
     * "" = archive root), folders first then files, each natural-sorted.
     */
    fun childrenOf(entries: List<ArchiveEntryDto>, dir: String): List<ArchiveItem> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        // LinkedHashMap keeps discovery order stable before the final sort.
        val dirs = LinkedHashMap<String, ArchiveItem>()
        val files = LinkedHashMap<String, ArchiveItem>()

        for (e in entries) {
            val p = e.path
            if (prefix.isNotEmpty() && !p.startsWith(prefix)) continue
            val rest = p.substring(prefix.length)
            if (rest.isEmpty()) continue

            val slash = rest.indexOf('/')
            if (slash >= 0) {
                // Something lives deeper → the immediate child is a directory.
                val name = rest.substring(0, slash)
                if (name.isEmpty()) continue
                dirs.getOrPut(name) { ArchiveItem(name, prefix + name, isDir = true, size = null) }
            } else if (e.dir) {
                dirs.getOrPut(rest) { ArchiveItem(rest, prefix + rest, isDir = true, size = null) }
            } else {
                files.getOrPut(rest) { ArchiveItem(rest, prefix + rest, isDir = false, size = e.size) }
            }
        }

        val sortedDirs = dirs.values.sortedWith(compareBy(NaturalOrderComparator) { it.name })
        val sortedFiles = files.values.sortedWith(compareBy(NaturalOrderComparator) { it.name })
        return sortedDirs + sortedFiles
    }

    /**
     * All image entries in the whole archive, natural-sorted by full path —
     * the ordered page list for the comic-reader image pager.
     */
    fun imageEntryPaths(entries: List<ArchiveEntryDto>): List<String> =
        entries.asSequence()
            .filter { !it.dir && isImageEntry(it.path) }
            .map { it.path }
            .sortedWith(NaturalOrderComparator)
            .toList()
}
