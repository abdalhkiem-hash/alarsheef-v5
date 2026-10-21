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
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * يُستدعى من العامل نفسه في نهاية دفعته حين يبقى أرشيف غير محلَّل.
     * KEEP يتجاهل الطلب لأن العامل ما زال قيد التشغيل بالاسم نفسه، فكان التحليل
     * يتوقف بعد أول دفعة حتى إعادة فتح التطبيق. APPEND_OR_REPLACE يُلحق دفعة
     * واحدة بعد الحالية (استدعاء واحد لكل دفعة — لا تراكم).
     */
    fun continueAfterBatch(context: Context) {
        val request = OneTimeWorkRequestBuilder<AiAnalysisWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AiAnalysisWorker.ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}