package io.synctuary.android.data

import io.synctuary.android.data.api.AuthInterceptor
import io.synctuary.android.data.api.NetworkModule
import io.synctuary.android.data.api.SynctuaryApi
import io.synctuary.android.data.api.dto.FileEntry
import io.synctuary.android.data.api.dto.MoveRequest
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileRepository(private val secretStore: SecretStore) {

    private var api: SynctuaryApi? = null

    private fun authenticatedApi(): SynctuaryApi {
        api?.let { return it }
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        val fp = paired.serverFingerprint
        val interceptor = AuthInterceptor(secretStore)
        return NetworkModule.create(paired.serverUrl, fp, interceptor).also { api = it }
    }

    suspend fun listFiles(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        authenticatedApi().filesList(path).entries
    }

    suspend fun deleteFile(path: String, recursive: Boolean = false) = withContext(Dispatchers.IO) {
        val resp = authenticatedApi().filesDelete(path, recursive)
        if (!resp.isSuccessful) {
            throw FileOperationException("delete failed: ${resp.code()}")
        }
    }

    suspend fun moveFile(from: String, to: String, overwrite: Boolean = false) =
        withContext(Dispatchers.IO) {
            val resp = authenticatedApi().filesMove(MoveRequest(from, to, overwrite))
            if (!resp.isSuccessful) {
                throw FileOperationException("move failed: ${resp.code()}")
            }
        }
}

class FileOperationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
