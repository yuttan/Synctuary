package io.synctuary.android.data.backup

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.synctuary.android.data.FileRepository
import io.synctuary.android.data.secret.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(K_BACKUP_ENABLED, false)
        if (!enabled) return@withContext Result.success()

        val secretStore = SecretStore.create(applicationContext)
        if (!secretStore.isPaired()) return@withContext Result.success()

        val repo = FileRepository(secretStore)
        val lastSync = prefs.getLong(K_LAST_SYNC_TIMESTAMP, 0L)
        val remotePath = prefs.getString(K_REMOTE_PATH, "/Camera Backup") ?: "/Camera Backup"

        try {
            val newPhotos = queryNewPhotos(lastSync)
            if (newPhotos.isEmpty()) return@withContext Result.success()

            var latestTimestamp = lastSync
            for (photo in newPhotos) {
                repo.uploadFile(
                    applicationContext.contentResolver,
                    photo.uri,
                    "$remotePath/${photo.displayName}",
                    overwrite = false,
                ) { _, _ -> }
                if (photo.dateAdded > latestTimestamp) {
                    latestTimestamp = photo.dateAdded
                }
            }

            prefs.edit().putLong(K_LAST_SYNC_TIMESTAMP, latestTimestamp).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun queryNewPhotos(sinceTimestamp: Long): List<MediaPhoto> {
        val photos = mutableListOf<MediaPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(sinceTimestamp.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val date = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                photos.add(MediaPhoto(uri, name, date))
            }
        }
        return photos
    }

    private data class MediaPhoto(val uri: Uri, val displayName: String, val dateAdded: Long)

    companion object {
        const val WORK_NAME = "photo_backup"
        const val PREFS_NAME = "synctuary-backup"
        const val K_BACKUP_ENABLED = "backup_enabled"
        const val K_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        const val K_REMOTE_PATH = "backup_remote_path"
    }
}
