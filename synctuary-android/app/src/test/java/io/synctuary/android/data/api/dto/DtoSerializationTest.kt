package io.synctuary.android.data.api.dto

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtoSerializationTest {

    private val moshi = Moshi.Builder().build()

    // ── FileEntry ──────────────────────────────────────────────────────

    @Test
    fun `FileEntry parses full JSON`() {
        val json = """
            {"name":"photo.jpg","type":"file","size":12345,"modified_at":1700000000,"mime_type":"image/jpeg","sha256":"abcdef"}
        """.trimIndent()
        val entry = moshi.adapter(FileEntry::class.java).fromJson(json)!!
        assertEquals("photo.jpg", entry.name)
        assertEquals("file", entry.type)
        assertEquals(12345L, entry.size)
        assertEquals(1700000000L, entry.modified_at)
        assertEquals("image/jpeg", entry.mime_type)
        assertEquals("abcdef", entry.sha256)
    }

    @Test
    fun `FileEntry parses minimal JSON with defaults`() {
        val json = """{"name":"docs","type":"dir","modified_at":1700000000}"""
        val entry = moshi.adapter(FileEntry::class.java).fromJson(json)!!
        assertEquals("docs", entry.name)
        assertEquals("dir", entry.type)
        assertNull(entry.size)
        assertNull(entry.mime_type)
        assertNull(entry.sha256)
    }

    @Test
    fun `FileListResponse parses correctly`() {
        val json = """
            {"path":"/photos","entries":[{"name":"a.jpg","type":"file","modified_at":1}]}
        """.trimIndent()
        val resp = moshi.adapter(FileListResponse::class.java).fromJson(json)!!
        assertEquals("/photos", resp.path)
        assertEquals(1, resp.entries.size)
        assertEquals("a.jpg", resp.entries[0].name)
    }

    // ── MoveRequest ────────────────────────────────────────────────────

    @Test
    fun `MoveRequest serializes correctly`() {
        val req = MoveRequest("/a/file.txt", "/b/file.txt")
        val json = moshi.adapter(MoveRequest::class.java).toJson(req)
        val parsed = moshi.adapter(MoveRequest::class.java).fromJson(json)!!
        assertEquals("/a/file.txt", parsed.from)
        assertEquals("/b/file.txt", parsed.to)
        assertEquals(false, parsed.overwrite)
    }

    @Test
    fun `MoveRequest overwrite flag preserved`() {
        val req = MoveRequest("/a", "/b", overwrite = true)
        val json = moshi.adapter(MoveRequest::class.java).toJson(req)
        val parsed = moshi.adapter(MoveRequest::class.java).fromJson(json)!!
        assertEquals(true, parsed.overwrite)
    }

    // ── UploadInitRequest ──────────────────────────────────────────────

    @Test
    fun `UploadInitRequest roundtrip`() {
        val req = UploadInitRequest("/upload/file.bin", 999L, "deadbeef")
        val json = moshi.adapter(UploadInitRequest::class.java).toJson(req)
        val parsed = moshi.adapter(UploadInitRequest::class.java).fromJson(json)!!
        assertEquals("/upload/file.bin", parsed.path)
        assertEquals(999L, parsed.size)
        assertEquals("deadbeef", parsed.sha256)
        assertEquals(false, parsed.overwrite)
    }

    // ── UploadProgressResponse ─────────────────────────────────────────

    @Test
    fun `UploadProgressResponse parses correctly`() {
        val json = """{"uploaded_bytes":512,"complete":false}"""
        val resp = moshi.adapter(UploadProgressResponse::class.java).fromJson(json)!!
        assertEquals(512L, resp.uploaded_bytes)
        assertEquals(false, resp.complete)
        assertNull(resp.sha256_verified)
    }

    // ── FavoriteListDto ────────────────────────────────────────────────

    @Test
    fun `FavoriteListDto parses correctly`() {
        val json = """
            {"id":"abc","name":"My List","hidden":true,"item_count":3,"created_at":100,"modified_at":200}
        """.trimIndent()
        val dto = moshi.adapter(FavoriteListDto::class.java).fromJson(json)!!
        assertEquals("abc", dto.id)
        assertEquals("My List", dto.name)
        assertEquals(true, dto.hidden)
        assertEquals(3, dto.item_count)
        assertEquals(100L, dto.created_at)
        assertEquals(200L, dto.modified_at)
    }

    @Test
    fun `FavoriteListDetailDto parses with items`() {
        val json = """
            {"id":"x","name":"Fav","hidden":false,"item_count":1,"created_at":1,"modified_at":2,"items":[{"path":"/a.jpg","added_at":10}]}
        """.trimIndent()
        val dto = moshi.adapter(FavoriteListDetailDto::class.java).fromJson(json)!!
        assertEquals(1, dto.items.size)
        assertEquals("/a.jpg", dto.items[0].path)
        assertEquals(10L, dto.items[0].added_at)
    }

    // ── CreateFavoriteRequest ──────────────────────────────────────────

    @Test
    fun `CreateFavoriteRequest serializes with defaults`() {
        val req = CreateFavoriteRequest("Test")
        val json = moshi.adapter(CreateFavoriteRequest::class.java).toJson(req)
        val parsed = moshi.adapter(CreateFavoriteRequest::class.java).fromJson(json)!!
        assertEquals("Test", parsed.name)
        assertEquals(false, parsed.hidden)
    }

    // ── AddFavoriteItemRequest ─────────────────────────────────────────

    @Test
    fun `AddFavoriteItemRequest roundtrip`() {
        val req = AddFavoriteItemRequest("/photos/sunset.jpg")
        val json = moshi.adapter(AddFavoriteItemRequest::class.java).toJson(req)
        val parsed = moshi.adapter(AddFavoriteItemRequest::class.java).fromJson(json)!!
        assertEquals("/photos/sunset.jpg", parsed.path)
    }
}
