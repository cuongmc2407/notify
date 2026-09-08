package com.notifybridge.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.notifybridge.net.Uploader
import java.util.concurrent.TimeUnit

/**
 * Luoi an toan: gui lai hang doi khi lan gui truc tiep that bai, va quet dinh ky
 * phong truong hop tien trinh bi giet giua chung.
 */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Uploader.sendHeartbeat(applicationContext)
        return if (Uploader.drain(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        private const val RETRY_WORK = "notify_upload_retry"
        private const val PERIODIC_WORK = "notify_upload_periodic"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Thu lai sau khi gui truc tiep that bai (10 giay, tang dan). */
        fun enqueueRetry(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.KEEP, request)
        }

        /** Quet hang doi 15 phut mot lan (khoang ngan nhat WorkManager cho phep). */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Chay ngay mot lan (dung cho nut "Gui thu" va sau khi ghep doi). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(networkConstraint)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
