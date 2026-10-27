package com.alarsheef.archive.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alarsheef.archive.ai.GeminiClassifier
import com.alarsheef.archive.ai.GeminiRepository
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.settings.SettingsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * يستخرج رقم المرجع للمستندات التي حُلِّلت سحابيًا قبل إضافة الحقل.
 * نداء واحد لكل مستند من حصة Gemini اليومية؛ يقف عند نفادها أو عند خطأ تهدئة (429/503)،
 * ويُكمَل في اليوم التالي بالضغط على الزر نفسه. لا يمسّ الوصف ولا الجهة ولا الاسم.
 */
class ReferenceBackfillWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = SettingsPreferences(applicationContext)
        if (!GeminiRepository.isConfigured() || !prefs.geminiCloudEnabled.first()) return Result.success()

        GeminiRepository.resetRateLimit()
        val repository = ArchiveRepository(applicationContext)
        var updated = 0
        var failed = 0
        try {
            for (image in repository.imagesMissingReference(GeminiRepository.DAILY_LIMIT)) {
                currentCoroutineContext().ensureActive()
                if (GeminiRepository.rateLimited || !prefs.hasGeminiQuota(GeminiRepository.DAILY_LIMIT)) break
                val file = File(image.storedPath)
                if (!file.exists()) {
                    repository.setReference(image.id, "")
                    continue
                }
                val result = GeminiClassifier.classify(file, usePro = false)
                if (result == null) {
                    failed++
                    continue
                }
                prefs.commitGeminiQuota(GeminiRepository.DAILY_LIMIT)
                repository.setReference(image.id, result.invoice?.reference.orEmpty())
                updated++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "تعذّر استخراج المراجع: ${e.javaClass.simpleName}: ${e.message}")
            return Result.success()
        }
        Log.i(TAG, "استخراج المراجع: حُدِّث $updated ملفًا، تعذّر $failed")
        return Result.success()
    }

    companion object {
        private const val TAG = "ReferenceBackfill"
        const val UNIQUE_WORK_NAME = "arsheef_reference_backfill"

        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReferenceBackfillWorker>().build()
            )
        }
    }
}
