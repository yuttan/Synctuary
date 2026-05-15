package io.synctuary.android.data

import android.content.ContentResolver
import android.net.Uri
import io.synctuary.android.data.api.AuthInterceptor
import io.synctuary.android.data.api.NetworkModule
import io.synctuary.android.data.api.SynctuaryApi
import io.synctuary.android.data.api.dto.FileEntry
import io.synctuary.android.data.api.dto.MoveRequest
import io.synctuary.android.data.api.dto.ShareEntry
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileRepository(private val secretStore: SecretStore) {

    private var api: SynctuaryApi? = null
    private var cachedUrl: String? = null

    private fun authenticatedApi(): SynctuaryApi {
        val paired = secretStore.loadPairedDevice()
            ?: throw IllegalStateException("not paired")
        if (api != null && cachedUrl == paired.serverUrl) return api!!
        val fp = paired.serverFingerprint
        val interceptor = AuthInterceptor(secretStore)
        cachedUrl = paired.serverUrl
        return NetworkModule.create(paired.serverUrl, fp, interceptor).also { api = it }
    }

    fun resetApiCache() {
        api = null
        cachedUrl = null
    }

    suspend fun listFiles(path: String, shareId: String? = null): List<FileEntry> = withContext(Dispatchers.IO) {
        authenticatedApi().filesList(path, share = shareId).entries
    }

    suspend fun listShares(): List<ShareEntry> = withContext(Dispatchers.IO) {
        authenticatedApi().sharesList().shares
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

    suspend fun downloadFile(
        remotePath: String,
        destFile: File,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): File = DownloadManager(authenticatedApi()).download(remotePath, destFile, onProgress)

    /** Download to a SAF URI (user-chosen folder or Save As target). */
    suspend fun downloadFileToUri(
        remotePath: String,
        resolver: ContentResolver,
        destUri: Uri,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): Uri = DownloadManager(authenticatedApi()).downloadToUri(remotePath, resolver, destUri, onProgress)

    suspend fun uploadFile(
        contentResolver: ContentResolver,
        uri: Uri,
        remotePath: String,
        overwrite: Boolean = false,
        onProgress: (uploaded: Long, total: Long) -> Unit,
    ) = UploadManager(authenticatedApi()).upload(contentResolver, uri, remotePath, overwrite, onProgress)
}

class FileOperationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
