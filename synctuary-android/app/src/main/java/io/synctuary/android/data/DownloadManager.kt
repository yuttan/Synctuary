package io.synctuary.android.data

import io.synctuary.android.data.api.SynctuaryApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

internal class DownloadManager(private val api: SynctuaryApi) {

    // §6.2: GET /api/v1/files/content — full download (no Range header).
    suspend fun download(
        remotePath: String,
        destFile: File,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val response = api.filesContent(remotePath)
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
}
