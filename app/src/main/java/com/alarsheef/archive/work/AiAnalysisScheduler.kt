package com.alarsheef.archive.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** تشغيل التحليل الذكي كدفعة خلفية (يُستدعى بعد الفحص أو يدويًا) */
object AiAnalysisScheduler {

    fun start(context: Context) {
        val request = OneTimeWorkRequestBuilder<AiAnalysisWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AiAnalysisWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}