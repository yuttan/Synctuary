package io.synctuary.android.data

import io.synctuary.android.data.api.AuthInterceptor
import io.synctuary.android.data.api.NetworkModule
import io.synctuary.android.data.api.SynctuaryApi
import io.synctuary.android.data.api.dto.AddFavoriteItemRequest
import io.synctuary.android.data.api.dto.CreateFavoriteRequest
import io.synctuary.android.data.api.dto.FavoriteItemDto
import io.synctuary.android.data.api.dto.FavoriteListDetailDto
import io.synctuary.android.data.api.dto.FavoriteListDto
import io.synctuary.android.data.api.dto.PatchFavoriteRequest
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FavoritesRepository(private val secretStore: SecretStore) {

    private var api: SynctuaryApi? = null

    private fun authenticatedApi(): SynctuaryApi {
        api?.let { return it }
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        val interceptor = AuthInterceptor(secretStore)
        return NetworkModule.create(paired.serverUrl, paired.serverFingerprint, interceptor)
            .also { api = it }
    }

    suspend fun listAll(includeHidden: Boolean = false): List<FavoriteListDto> =
        withContext(Dispatchers.IO) {
            authenticatedApi().favoritesList(includeHidden).lists
        }

    suspend fun getList(id: String): FavoriteListDetailDto =
        withContext(Dispatchers.IO) {
            authenticatedApi().favoritesGet(id)
        }

    suspend fun createList(name: String, hidden: Boolean = false): FavoriteListDto =
        withContext(Dispatchers.IO) {
            authenticatedApi().favoritesCreate(CreateFavoriteRequest(name, hidden))
        }

    suspend fun updateList(id: String, name: String? = null, hidden: Boolean? = null): FavoriteListDto =
        withContext(Dispatchers.IO) {
            authenticatedApi().favoritesPatch(id, PatchFavoriteRequest(name, hidden))
        }

    suspend fun deleteList(id: String) = withContext(Dispatchers.IO) {
        val resp = authenticatedApi().favoritesDelete(id)
        if (!resp.isSuccessful) {
            throw FileOperationException("delete favorite failed: ${resp.code()}")
        }
    }

    suspend fun addItem(listId: String, path: String): FavoriteItemDto =
        withContext(Dispatchers.IO) {
            authenticatedApi().favoritesItemAdd(listId, AddFavoriteItemRequest(path))
        }

    suspend fun removeItem(listId: String, path: String) = withContext(Dispatchers.IO) {
        val resp = authenticatedApi().favoritesItemRemove(listId, path)
        if (!resp.isSuccessful) {
            throw FileOperationException("remove favorite item failed: ${resp.code()}")
        }
    }
}
