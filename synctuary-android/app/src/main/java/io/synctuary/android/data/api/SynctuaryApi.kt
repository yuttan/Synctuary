package io.synctuary.android.data.api

import io.synctuary.android.data.api.dto.AddFavoriteItemRequest
import io.synctuary.android.data.api.dto.DevicesResponse
import io.synctuary.android.data.api.dto.CreateFavoriteRequest
import io.synctuary.android.data.api.dto.FavoriteItemDto
import io.synctuary.android.data.api.dto.FavoriteListDetailDto
import io.synctuary.android.data.api.dto.FavoriteListDto
import io.synctuary.android.data.api.dto.FavoriteListsResponse
import io.synctuary.android.data.api.dto.FileListResponse
import io.synctuary.android.data.api.dto.InfoDto
import io.synctuary.android.data.api.dto.MoveRequest
import io.synctuary.android.data.api.dto.NonceDto
import io.synctuary.android.data.api.dto.PatchFavoriteRequest
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
import retrofit2.http.PATCH
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

    // ── Devices (§7) — require Bearer auth ──────────────────────────

    @GET("api/v1/devices")
    suspend fun devicesList(): DevicesResponse

    @DELETE("api/v1/devices/{id}")
    suspend fun devicesRevoke(@Path("id") id: String): Response<Unit>

    // ── Favorites (§8) — require Bearer auth ───────────────────────

    @GET("api/v1/favorites")
    suspend fun favoritesList(
        @Query("include_hidden") includeHidden: Boolean = false,
    ): FavoriteListsResponse

    @GET("api/v1/favorites/{id}")
    suspend fun favoritesGet(@Path("id") id: String): FavoriteListDetailDto

    @POST("api/v1/favorites")
    suspend fun favoritesCreate(@Body body: CreateFavoriteRequest): FavoriteListDto

    @PATCH("api/v1/favorites/{id}")
    suspend fun favoritesPatch(
        @Path("id") id: String,
        @Body body: PatchFavoriteRequest,
    ): FavoriteListDto

    @DELETE("api/v1/favorites/{id}")
    suspend fun favoritesDelete(@Path("id") id: String): Response<Unit>

    @POST("api/v1/favorites/{id}/items")
    suspend fun favoritesItemAdd(
        @Path("id") id: String,
        @Body body: AddFavoriteItemRequest,
    ): FavoriteItemDto

    @DELETE("api/v1/favorites/{id}/items")
    suspend fun favoritesItemRemove(
        @Path("id") id: String,
        @Query("path") path: String,
    ): Response<Unit>
}
