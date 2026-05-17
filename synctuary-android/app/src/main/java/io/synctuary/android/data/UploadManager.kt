package io.synctuary.android.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.synctuary.android.data.api.SynctuaryApi
import io.synctuary.android.data.api.dto.UploadInitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.InputStream
import java.security.MessageDigest

internal class UploadManager(private val api: SynctuaryApi) {

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val FALLBACK_CHUNK = 8 * 1024 * 1024L  // §6.3.1 server default
    }

    // §6.3: two-pass — hash whole file, then stream chunks.
    // Dedup (§6.3.1): if server already has the content, no chunks are sent.
    suspend fun upload(
        contentResolver: ContentResolver,
        uri: Uri,
        remotePath: String,
        overwrite: Boolean = false,
        onProgress: (uploaded: Long, total: Long) -> Unit,
        shareId: String? = null,
    ) = withContext(Dispatchers.IO) {
        val fileSize = resolveSize(contentResolver, uri)
        val sha256 = hashSha256(contentResolver, uri)

        val init = try {
            api.uploadInit(UploadInitRequest(remotePath, fileSize, sha256, overwrite), share = shareId)
        } catch (e: HttpException) {
            throw uploadHttpError(e)
        }

        if (init.status == "deduplicated") {
            onProgress(fileSize, fileSize)
            return@withContext
        }

        val uploadId = init.upload_id
            ?: throw FileOperationException("server returned no upload_id")
        val chunkSize = init.chunk_size ?: FALLBACK_CHUNK
        var sent = init.uploaded_bytes ?: 0L
        onProgress(sent, fileSize)

        contentResolver.openInputStream(uri)?.use { stream ->
            stream.skipFully(sent)
            val buf = ByteArray(chunkSize.toInt())

            while (sent < fileSize) {
                val toRead = minOf(chunkSize, fileSize - sent).toInt()
                val bytesRead = stream.readFully(buf, toRead)
                if (bytesRead == 0) break

                // Content-Range end is inclusive per RFC 7233 / §6.3.2
                val end = sent + bytesRead - 1
                val range = "bytes $sent-$end/$fileSize"
                val body = buf.copyOf(bytesRead).toRequestBody(OCTET_STREAM)

                val progress = try {
                    api.uploadChunk(uploadId, range, body)
                } catch (e: HttpException) {
                    throw chunkHttpError(e)
                }

                sent = progress.uploaded_bytes
                onProgress(sent, fileSize)
                if (progress.complete) break
            }
        } ?: throw FileOperationException("cannot open URI for reading: $uri")
    }

    private fun uploadHttpError(e: HttpException): FileOperationException {
        val body = e.response()?.errorBody()?.string()
        val (code, message) = DownloadManager.parseServerError(body)
        val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull()
        val msg = when (code) {
            "upload_in_progress" -> if (retryAfter != null)
                "Another device is uploading to this path (retry in ${retryAfter}s)"
            else "Another device is uploading to this path"
            "file_exists" -> "File already exists"
            else -> message ?: "upload init failed: HTTP ${e.code()}"
        }
        return FileOperationException(msg, e)
    }

    private fun chunkHttpError(e: HttpException): FileOperationException {
        val body = e.response()?.errorBody()?.string()
        val (code, message) = DownloadManager.parseServerError(body)
        val msg = when (code) {
            "upload_range_mismatch" -> "Upload position mismatch; restart upload"
            "upload_hash_mismatch" -> "File content changed during upload"
            "payload_too_large" -> "Chunk size exceeds server limit"
            "insufficient_storage" -> "Server storage is full"
            else -> message ?: "chunk upload failed: HTTP ${e.code()}"
        }
        return FileOperationException(msg, e)
    }

    private fun resolveSize(cr: ContentResolver, uri: Uri): Long =
        cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            ?: cr.openFileDescriptor(uri, "r")?.use { it.statSize }
            ?: throw FileOperationException("cannot determine file size for $uri")

    private fun hashSha256(cr: ContentResolver, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        cr.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(65536)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        } ?: throw FileOperationException("cannot open URI for hashing: $uri")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private fun InputStream.skipFully(n: Long) {
    var rem = n
    while (rem > 0) {
        val s = skip(rem)
        if (s <= 0) break
        rem -= s
    }
}

private fun InputStream.readFully(buf: ByteArray, len: Int): Int {
    var read = 0
    while (read < len) {
        val n = read(buf, read, len - read)
        if (n == -1) break
        read += n
    }
    return read
}
