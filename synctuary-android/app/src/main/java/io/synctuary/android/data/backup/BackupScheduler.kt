package io.synctuary.android.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackupScheduler {

    fun schedule(context: Context, wifiOnly: Boolean = true) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()

        val request = PeriodicWorkRequestBuilder<PhotoBackupWorker>(
            1, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PhotoBackupWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(PhotoBackupWorker.WORK_NAME)
    }
}
