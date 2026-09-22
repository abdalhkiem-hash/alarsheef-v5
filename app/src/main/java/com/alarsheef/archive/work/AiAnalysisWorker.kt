package com.alarsheef.archive.work

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alarsheef.archive.ai.FaceGroupingEngine
import com.alarsheef.archive.ai.OcrEngine
import com.alarsheef.archive.ai.SmartClassifier
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.settings.SettingsPreferences
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * التحليل الذكي الذي يعمل كليًا على الجهاز:
 * - تصنيف المحتوى (مستند/شاشة/طبيعة...) عبر SmartClassifier.
 * - استخراج النصوص (OCR) عبر نموذج النظام (Android 13+).
 * - اكتشاف الوجوه وتجميعها في مجموعات تُعرض في شاشة الوجوه.
 *
 * يعالج الدفعات (حتى MAX_PER_RUN لكل تشغيل) ويسلّم التكملة لنفسه إن بقي
 * أرشيف غير محلل، ثم تُرمَّز الصور لذلك لا تُعاد معالجتها كل يوم.
 */
class AiAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "ai_image_analysis"
        const val ONE_TIME_WORK_NAME = "ai_image_analysis_onetime"
        private const val MAX_PER_RUN = 120
        private const val TAG = "AiAnalysisWorker"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = ArchiveRepository(context)
        val prefs = SettingsPreferences(context)

        val ocrEnabled = prefs.aiOcrEnabled.first()
        val labelsEnabled = prefs.aiLabelsEnabled.first()
        val facesEnabled = prefs.aiFacesEnabled.first()
        if (!ocrEnabled && !labelsEnabled && !facesEnabled) return Result.success()

        val facesRoot = File(context.filesDir, "face_crops").apply { mkdirs() }

        // ممثلو المجموعات الحالية — لاستمرارية التجميع عبر التشغيلات
        val groupReps = repository.faceGroupRepresentatives().associate {
            it.groupKey to java.lang.Long.parseUnsignedLong(it.repHash, 16)
        }.toMutableMap()

        val pending = repository.pendingAiImages(MAX_PER_RUN)
        if (pending.isEmpty()) return Result.success()

        /** مربع نص: يقرر إدراج وجه في مجموعة قائمة أو إنشاء مجموعة جديدة */
        fun resolveGroup(hash: Long): String {
            groupReps.entries.firstOrNull {
                FaceGroupingEngine.hammingDistance(it.value, hash) <= FaceGroupingEngine.MATCH_THRESHOLD
            }?.let { return it.key }

            val newKey = "face_${System.currentTimeMillis()}_${groupReps.size}"
            groupReps[newKey] = hash
            return newKey
        }

        var processed = 0
        for (image in pending) {
            val file = File(image.storedPath)
            if (!file.exists()) {
                repository.markAiAnalyzed(image.id)
                continue
            }

            try {
                // تمريرة واحدة لاكتشاف الوجوه — تستخدم للإشارة في التصنيف وللتجميع معًا
                val faces = if (facesEnabled) {
                    FaceGroupingEngine.detectFaces(file)
                } else emptyList()

                if (labelsEnabled) {
                    val classified = SmartClassifier.classifyFile(file, faces.size)
                    if (classified.isNotEmpty()) repository.saveLabels(image.id, classified)
                }

                if (ocrEnabled && OcrEngine.isAvailable(context)) {
                    val text = OcrEngine.recognizeFile(context, file)
                    if (!text.isNullOrBlank()) repository.saveOcrText(image.id, text)
                }

                if (facesEnabled && faces.isNotEmpty()) {
                    // قبل الإدراج: تطهير أي أعضاء سابقة لهذه الصورة (حالة إعادة التحليل)
                    // حتى لا تتضاعف المجموعات ولا تتراكم ملفات القصّ على القرص
                    repository.purgeFaceDataForImage(image.id)
                    for (face in faces) {
                        val crop = FaceGroupingEngine.extractCrop(file, face) ?: continue
                        try {
                            val hash = FaceGroupingEngine.dHash(crop)
                            val groupKey = resolveGroup(hash)
                            val cropFile = File(
                                facesRoot,
                                "${groupKey}_${image.id}_${System.nanoTime()}.jpg"
                            )
                            saveBitmap(crop, cropFile)

                            repository.upsertFaceGroup(groupKey, null)
                            repository.addFaceMember(groupKey, image.id, FaceGroupingEngine.hashToHex(hash), cropFile.absolutePath)
                        } finally {
                            if (!crop.isRecycled) crop.recycle()
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "OOM لمعالجة الصورة ${image.id}: ${e.message}")
                repository.markAiAnalyzed(image.id)
                continue
            }

            repository.markAiAnalyzed(image.id)
            processed++
        }

        // لو بقي كثير غير محلل: نسلّم دفعة أخرى لنفس العامل
        if (repository.pendingAiCountOnce() > 0) {
            AiAnalysisScheduler.start(context)
        }

        return if (processed > 0) Result.success() else Result.retry()
    }

    private fun saveBitmap(bitmap: Bitmap, target: File) {
        try {
            target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            target.delete()
        }
    }
}