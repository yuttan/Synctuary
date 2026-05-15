package io.synctuary.android.data.api.dto

import com.squareup.moshi.JsonClass

// Section 10 GET /api/v1/shares

@JsonClass(generateAdapter = true)
data class SharesResponse(
    val shares: List<ShareEntry>,
)

@JsonClass(generateAdapter = true)
data class ShareEntry(
    val id: String,
    val name: String,
    val host_path: String? = null,
    val icon: String? = null,
    val read_only: Boolean = false,
)
