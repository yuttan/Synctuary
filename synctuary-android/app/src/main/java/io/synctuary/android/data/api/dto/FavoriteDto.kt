package io.synctuary.android.data.api.dto

import com.squareup.moshi.JsonClass

// §8.2 GET /api/v1/favorites

@JsonClass(generateAdapter = true)
data class FavoriteListsResponse(
    val lists: List<FavoriteListDto>,
)

@JsonClass(generateAdapter = true)
data class FavoriteListDto(
    val id: String,
    val name: String,
    val hidden: Boolean,
    val item_count: Int,
    val created_at: Long,
    val modified_at: Long,
    val created_by_device_id: String? = null,
)

// §8.3 GET /api/v1/favorites/{id}

@JsonClass(generateAdapter = true)
data class FavoriteListDetailDto(
    val id: String,
    val name: String,
    val hidden: Boolean,
    val item_count: Int,
    val created_at: Long,
    val modified_at: Long,
    val created_by_device_id: String? = null,
    val items: List<FavoriteItemDto>,
)

@JsonClass(generateAdapter = true)
data class FavoriteItemDto(
    val path: String,
    val added_at: Long,
    val added_by_device_id: String? = null,
)

// §8.4 POST /api/v1/favorites

@JsonClass(generateAdapter = true)
data class CreateFavoriteRequest(
    val name: String,
    val hidden: Boolean = false,
)

// §8.5 PATCH /api/v1/favorites/{id}

@JsonClass(generateAdapter = true)
data class PatchFavoriteRequest(
    val name: String? = null,
    val hidden: Boolean? = null,
)

// §8.7 POST /api/v1/favorites/{id}/items

@JsonClass(generateAdapter = true)
data class AddFavoriteItemRequest(
    val path: String,
)
