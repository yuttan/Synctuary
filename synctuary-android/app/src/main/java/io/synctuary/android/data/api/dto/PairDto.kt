package io.synctuary.android.data.api.dto

import com.squareup.moshi.JsonClass

/** Response for `POST /api/v1/pair/nonce` (PROTOCOL §4.2).
 *  `nonce` is base64url-without-padding, 32-byte CSPRNG. */
@JsonClass(generateAdapter = true)
data class NonceDto(
    val nonce: String,
    val expires_at: Long,
)

/** Request body for `POST /api/v1/pair/register` (PROTOCOL §4.3).
 *
 *  All binary fields are base64url-without-padding (§1). The handler
 *  consumes the nonce *before* signature verification, so a malformed
 *  payload still expires the nonce. */
@JsonClass(generateAdapter = true)
data class RegisterRequest(
    val nonce: String,
    val device_id: String,
    val device_pub: String,
    val device_name: String,
    val platform: String,
    val challenge_response: String,
)

/** Response for `POST /api/v1/pair/register`.
 *  `device_token` is base64url(32 random bytes); store as opaque bearer. */
@JsonClass(generateAdapter = true)
data class RegisterResponse(
    val device_token: String,
    val server_id: String,
    val device_token_ttl: Long,
)

/** Server error envelope (PROTOCOL §8 / §9 unified shape). Wrapped in
 *  `{"error": {"code": ..., "message": ...}}`. */
@JsonClass(generateAdapter = true)
data class ApiErrorBody(val error: ApiErrorDetail)

@JsonClass(generateAdapter = true)
data class ApiErrorDetail(
    val code: String,
    val message: String,
)
