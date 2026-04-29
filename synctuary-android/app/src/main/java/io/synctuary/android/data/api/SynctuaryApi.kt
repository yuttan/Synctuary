package io.synctuary.android.data.api

import io.synctuary.android.data.api.dto.InfoDto
import io.synctuary.android.data.api.dto.NonceDto
import io.synctuary.android.data.api.dto.RegisterRequest
import io.synctuary.android.data.api.dto.RegisterResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit interface for the §4 + §5 endpoint set used during
 * pairing. File / device / favorites endpoints land in subsequent
 * phases — kept out of here so a Phase-2 PR doesn't drag in the whole
 * API surface.
 *
 * Authenticated routes (§6+) will require an OkHttp interceptor that
 * adds the `Authorization: Bearer <token>` header from SecretStore;
 * see PairingRepository for where the token is acquired.
 */
interface SynctuaryApi {

    /** Server identity / capability advertisement. Unauthenticated;
     *  used during onboarding to confirm the user typed the right URL
     *  and to read the TLS fingerprint for §3.3 pinning. */
    @GET("api/v1/info")
    suspend fun info(): InfoDto

    /** Issue a pairing nonce (PROTOCOL §4.2). Body is empty. */
    @POST("api/v1/pair/nonce")
    suspend fun pairNonce(): NonceDto

    /** Register a new device with the signed challenge response
     *  (PROTOCOL §4.3). Returns the server-issued device_token. */
    @POST("api/v1/pair/register")
    suspend fun pairRegister(@Body body: RegisterRequest): RegisterResponse
}
