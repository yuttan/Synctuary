package io.synctuary.android.data.api.dto

import com.squareup.moshi.JsonClass

// PROTOCOL §6.9-§6.11 — archive browsing / streaming / extraction.

@JsonClass(generateAdapter = true)
data class ArchiveListResponse(
    val entries: List<ArchiveEntryDto>,
)

@JsonClass(generateAdapter = true)
data class ArchiveEntryDto(
    // Archive-internal path: forward-slash separated, cleaned, no leading slash.
    val path: String,
    val size: Long? = null,
    val dir: Boolean = false,
)

// §6.11 POST /api/v1/files/archive/extract — share travels in the body.

@JsonClass(generateAdapter = true)
data class ArchiveExtractRequest(
    val path: String,
    val share: String? = null,
)

@JsonClass(generateAdapter = true)
data class ArchiveExtractResponse(
    val dest: String,
)
