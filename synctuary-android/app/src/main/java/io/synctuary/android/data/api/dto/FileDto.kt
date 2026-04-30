package io.synctuary.android.data.api.dto

import com.squareup.moshi.JsonClass

// §6.1 GET /api/v1/files

@JsonClass(generateAdapter = true)
data class FileListResponse(
    val path: String,
    val entries: List<FileEntry>,
)

@JsonClass(generateAdapter = true)
data class FileEntry(
    val name: String,
    val type: String,
    val size: Long? = null,
    val modified_at: Long,
    val mime_type: String? = null,
    val sha256: String? = null,
)

// §6.3.1 POST /api/v1/files/upload/init

@JsonClass(generateAdapter = true)
data class UploadInitRequest(
    val path: String,
    val size: Long,
    val sha256: String,
    val overwrite: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class UploadInitResponse(
    val upload_id: String?,
    val chunk_size: Long? = null,
    val chunk_size_max: Long? = null,
    val uploaded_bytes: Long? = null,
    val status: String? = null,
)

// §6.3.2 PUT /api/v1/files/upload/{id}
// §6.3.3 GET /api/v1/files/upload/{id}

@JsonClass(generateAdapter = true)
data class UploadProgressResponse(
    val uploaded_bytes: Long,
    val complete: Boolean,
    val sha256_verified: Boolean? = null,
    val expires_at: Long? = null,
)

// §6.5 POST /api/v1/files/move

@JsonClass(generateAdapter = true)
data class MoveRequest(
    val from: String,
    val to: String,
    val overwrite: Boolean = false,
)
