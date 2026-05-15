package io.synctuary.android.data

import android.content.ContentResolver
import android.net.Uri
import io.synctuary.android.data.api.SynctuaryApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

internal class DownloadManager(private val api: SynctuaryApi) {

    // §6.2: GET /api/v1/files/content — download to a local File.
    suspend fun download(
        remotePath: String,
        destFile: File,
        onProgress: (received: Long, total: Long?) -> Unit,
        shareId: String? = null,
    ): File = withContext(Dispatchers.IO) {
        val response = api.filesContent(remotePath, share = shareId)
        if (!response.isSuccessful) {
            throw FileOperationException("download failed: HTTP ${response.code()}")
        }
        val body = response.body()
            ?: throw FileOperationException("download failed: empty response body")
        val total = response.headers()["Content-Length"]?.toLongOrNull()

        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(65536)
                var received = 0L
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    received += n
                    onProgress(received, total)
                }
            }
        }
        destFile
    }

    // §6.2: download to a SAF URI (user-chosen folder / Save As).
    suspend fun downloadToUri(
        remotePath: String,
        resolver: ContentResolver,
        destUri: Uri,
        onProgress: (received: Long, total: Long?) -> Unit,
        shareId: String? = null,
    ): Uri = withContext(Dispatchers.IO) {
        val response = api.filesContent(remotePath, share = shareId)
        if (!response.isSuccessful) {
            throw FileOperationException("download failed: HTTP ${response.code()}")
        }
        val body = response.body()
            ?: throw FileOperationException("download failed: empty response body")
        val total = response.headers()["Content-Length"]?.toLongOrNull()

        resolver.openOutputStream(destUri)?.use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(65536)
                var received = 0L
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    received += n
                    onProgress(received, total)
                }
            }
        } ?: throw FileOperationException("download failed: cannot open output stream for URI")
        destUri
    }
}
