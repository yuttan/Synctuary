package io.synctuary.android.data.api

import io.synctuary.android.data.api.dto.FileListResponse
import io.synctuary.android.data.api.dto.InfoDto
import io.synctuary.android.data.api.dto.MoveRequest
import io.synctuary.android.data.api.dto.NonceDto
import io.synctuary.android.data.api.dto.RegisterRequest
import io.synctuary.android.data.api.dto.RegisterResponse
import io.synctuary.android.data.api.dto.UploadInitRequest
import io.synctuary.android.data.api.dto.UploadInitResponse
import io.synctuary.android.data.api.dto.UploadProgressResponse
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface SynctuaryApi {

    // ── Unauthenticated (§4-§5) ─────────────────────────────────────

    @GET("api/v1/info")
    suspend fun info(): InfoDto

    @POST("api/v1/pair/nonce")
    suspend fun pairNonce(): NonceDto

    @POST("api/v1/pair/register")
    suspend fun pairRegister(@Body body: RegisterRequest): RegisterResponse

    // ── File operations (§6) — require Bearer auth ──────────────────

    @GET("api/v1/files")
    suspend fun filesList(
        @Query("path") path: String,
        @Query("hash") hash: Boolean = false,
    ): FileListResponse

    @GET("api/v1/files/content")
    suspend fun filesContent(
        @Query("path") path: String,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>

    @DELETE("api/v1/files")
    suspend fun filesDelete(
        @Query("path") path: String,
        @Query("recursive") recursive: Boolean = false,
    ): Response<Unit>

    @POST("api/v1/files/move")
    suspend fun filesMove(@Body body: MoveRequest): Response<Unit>

    @POST("api/v1/files/upload/init")
    suspend fun uploadInit(@Body body: UploadInitRequest): UploadInitResponse

    @PUT("api/v1/files/upload/{id}")
    suspend fun uploadChunk(
        @Path("id") uploadId: String,
        @Header("Content-Range") contentRange: String,
        @Body body: RequestBody,
    ): UploadProgressResponse

    @GET("api/v1/files/upload/{id}")
    suspend fun uploadProgress(@Path("id") uploadId: String): UploadProgressResponse

    @DELETE("api/v1/files/upload/{id}")
    suspend fun uploadAbort(@Path("id") uploadId: String): Response<Unit>
}
