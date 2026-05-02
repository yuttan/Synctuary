package io.synctuary.android.data.api.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DeviceDto(
    val device_id: String,
    val device_name: String,
    val platform: String,
    val created_at: Long,
    val last_seen_at: Long,
    val current: Boolean,
    val revoked: Boolean,
)

@JsonClass(generateAdapter = true)
data class DevicesResponse(
    val devices: List<DeviceDto>,
)
