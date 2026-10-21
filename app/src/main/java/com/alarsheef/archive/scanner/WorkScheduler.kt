package com.alarsheef.archive.scanner

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object WorkScheduler {

    private const val CONTENT_TRIGGER_WORK_NAME = "arsheef_content_trigger"

    /** أقصر فاصل يسمح به WorkManager. شبكة أمان لو لم يصل إشعار MediaStore. */
    private const val PERIODIC_MINUTES = 15L

    fun scheduleDailyScan(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val request = PeriodicWorkRequestBuilder<FileScannerWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        // UPDATE: الجدولة القديمة كل 24 ساعة تُستبدل بالفاصل الجديد عند أول فتح.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FileScannerWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        armContentTrigger(context)
    }

    fun cancelDailyScan(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(FileScannerWorker.UNIQUE_WORK_NAME)
        manager.cancelUniqueWork(CONTENT_TRIGGER_WORK_NAME)
    }

    /**
     * يوقظ الفحص حين يتغيّر MediaStore (وصول ملف جديد من واتساب أعمال)، فلا ينتظر
     * الملف الفاصل الدوري. APPEND_OR_REPLACE يُبقي عملًا واحدًا معلّقًا دائمًا،
     * حتى لو استُدعي ونحن داخل عامل المشغّل نفسه.
     */
    fun armContentTrigger(context: Context) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<ContentTriggerWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONTENT_TRIGGER_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    /** يشغّل فحصًا فوريًا لمرة واحدة (يُستخدم عند تفعيل الإعداد أول مرة، بدل انتظار الدورة التالية) */
    fun runOnce(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val request = OneTimeWorkRequestBuilder<FileScannerWorker>().build()
        Log.i("WorkScheduler", "طلب فحص يدوي فوري")
        // REPLACE بدل KEEP: زر "فحص الآن" يجب أن يستجيب دائمًا حتى لو كان
        // هناك طلب قديم عالقًا في تأخير إعادة المحاولة (backoff) لا يُشغَّل.
        WorkManager.getInstance(context).enqueueUniqueWork(
            FileScannerWorker.ONE_TIME_WORK_NAME,
            policy,
            request
        )
    }
}

/** يستيقظ عند تغيّر MediaStore، يطلب فحصًا، ثم يعيد تسليح نفسه. */
class ContentTriggerWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.i("WorkScheduler", "تغيّر في الوسائط — فحص")
        WorkScheduler.runOnce(applicationContext, ExistingWorkPolicy.KEEP)
        WorkScheduler.armContentTrigger(applicationContext)
        return Result.success()
    }
}
