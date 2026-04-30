package io.synctuary.android.data.api.dto

import com.squareup.moshi.JsonClass

/** Response for `GET /api/v1/info` (PROTOCOL §5.1).
 *
 *  `commit` is optional — server omits it when build wasn't injected
 *  with a real commit (bare `go build` development case). */
@JsonClass(generateAdapter = true)
data class InfoDto(
    val protocol_version: String,
    val server_version: String,
    val server_id: String,
    val server_name: String,
    val encryption_mode: String,
    val transport_profile: String,
    val capabilities: Map<String, Boolean>,
    val tls_fingerprint: String? = null,
    val commit: String? = null,
)
