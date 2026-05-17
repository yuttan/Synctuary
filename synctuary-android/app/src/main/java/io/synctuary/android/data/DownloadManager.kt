package io.synctuary.android.data

import android.content.ContentResolver
import android.net.Uri
import com.squareup.moshi.Moshi
import io.synctuary.android.data.api.SynctuaryApi
import io.synctuary.android.data.api.dto.ApiErrorBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream

internal class DownloadManager(private val api: SynctuaryApi) {

    suspend fun download(
        remotePath: String,
        destFile: File,
        onProgress: (received: Long, total: Long?) -> Unit,
        shareId: String? = null,
    ): File = withContext(Dispatchers.IO) {
        val existingLength = if (destFile.exists()) destFile.length() else 0L
        val rangeHeader = if (existingLength > 0) "bytes=$existingLength-" else null

        val response = api.filesContent(remotePath, range = rangeHeader, share = shareId)
        if (!response.isSuccessful) {
            throw httpError(response.code(), response.errorBody()?.string())
        }
        val body = response.body()
            ?: throw FileOperationException("download failed: empty response body")

        val isPartial = response.code() == 206
        val total = if (isPartial) {
            parseContentRangeTotal(response.headers()["Content-Range"])
        } else {
            response.headers()["Content-Length"]?.toLongOrNull()
        }

        destFile.parentFile?.mkdirs()
        val append = isPartial && existingLength > 0
        val startOffset = if (append) existingLength else 0L
        if (!append && destFile.exists()) destFile.delete()

        FileOutputStream(destFile, append).use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(65536)
                var received = startOffset
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

    suspend fun downloadToUri(
        remotePath: String,
        resolver: ContentResolver,
        destUri: Uri,
        onProgress: (received: Long, total: Long?) -> Unit,
        shareId: String? = null,
    ): Uri = withContext(Dispatchers.IO) {
        val response = api.filesContent(remotePath, share = shareId)
        if (!response.isSuccessful) {
            throw httpError(response.code(), response.errorBody()?.string())
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

    private fun parseContentRangeTotal(header: String?): Long? {
        // Content-Range: bytes 1024-2047/10240
        val slash = header?.lastIndexOf('/') ?: return null
        return header.substring(slash + 1).toLongOrNull()
    }

    companion object {
        internal fun httpError(code: Int, errorBody: String?): FileOperationException {
            val (errCode, errMsg) = parseServerError(errorBody)
            val msg = when (errCode) {
                "not_found" -> "File not found"
                "range_not_satisfiable" -> "Invalid download range"
                else -> errMsg ?: "download failed: HTTP $code"
            }
            return FileOperationException(msg)
        }

        internal fun parseServerError(body: String?): Pair<String?, String?> {
            if (body.isNullOrEmpty()) return null to null
            return try {
                val adapter = Moshi.Builder().build().adapter(ApiErrorBody::class.java)
                adapter.fromJson(body)?.let { it.error.code to it.error.message }
                    ?: (null to null)
            } catch (_: Exception) {
                null to null
            }
        }
    }
}
