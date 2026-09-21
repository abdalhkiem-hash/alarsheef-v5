package com.alarsheef.archive.scanner

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkScheduler {

    fun scheduleDailyScan(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val request = PeriodicWorkRequestBuilder<FileScannerWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FileScannerWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelDailyScan(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FileScannerWorker.UNIQUE_WORK_NAME)
    }

    /** يشغّل فحصًا فوريًا لمرة واحدة (يُستخدم عند تفعيل الإعداد أول مرة، بدل انتظار 24 ساعة) */
    fun runOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<FileScannerWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FileScannerWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
