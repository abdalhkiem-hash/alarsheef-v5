package com.alarsheef.archive.work

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alarsheef.archive.ai.ClassifiedLabel
import com.alarsheef.archive.ai.FaceGroupingEngine
import com.alarsheef.archive.ai.GeminiClassifier
import com.alarsheef.archive.ai.GeminiRepository
import com.alarsheef.archive.ai.OcrEngine
import com.alarsheef.archive.ai.SmartClassifier
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.settings.SettingsPreferences
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * بوابة التحقق من محتوى المستند النصي — المرحلة 1 من نهج التحقق المرحلي.
 * تعمل بشكل مستقل عن ImportGate و SmartClassifier.
 *
 * تريد-أوف موثق: التحقق النصي يُجرى في AiAnalysisWorker (بعد الاستيراد) لا في FileScannerWorker.
 * السبب: FileScannerWorker مسح يومي سريع — إضافة OCR متزامن يرفع زمن المسح 50-100x،
 * يكسر فصل الاهتمامات، ويمنع أرشفة الملفات "المعلّقة" للمراجعة عند فشل OCR.
 * المقايضة: الملفات تدخل الأرشيف أولاً بـ contentVerified=0، ثم تُصفّى لاحقًا.
 */
sealed class ContentVerdict {
    /** محتوى نصي واضح لمستند — يُقبل. */
    data class Accepted(
        val matchedKeywords: List<String>,
        val primaryCategory: DocumentCategory
    ) : ContentVerdict()

    /** محتوى نصي ضعيف/غامض — يُحفظ للمراجعة اليدوية. */
    data class Ambiguous(
        val partialMatches: List<String>
    ) : ContentVerdict()

    /** محتوى نصي لا يدل على مستند — يُرفض. */
    data class Rejected(
        val reason: String
    ) : ContentVerdict()
}

/** فئات المستندات المستهدفة. */
enum class DocumentCategory {
    INVOICE,      // فاتورة
    RECEIPT,      // إيصال/حوالة
    CASH_BOX,     // حركة صندوق
    CONTRACT,     // عقد
    ID_DOCUMENT,  // هوية/جواز
    NOTE          // ملاحظات/نص عام
}

/**
 * يطبع النص المُستخرج ويقرر إن كان يدل على مستند مستهدف.
 * مطابقة كلمات مفتاحية مع تطبيع نص (إزالة تشكيل، توحيد مسافات) وشرط "أكثر من دليل".
 */
fun verify(ocrText: String): ContentVerdict {
    if (ocrText.isNullOrBlank()) {
        return ContentVerdict.Rejected("نص فارغ")
    }

    val normalized = normalize(ocrText)

    // كلمات مفتاحية لكل فئة — تطبيع مسبق
    val keywords = mapOf(
        DocumentCategory.INVOICE to listOf("فاتورة", "مبلغ", "إجمالي", "ضريبة", "قيمة", "مشتريات", "مبيعات", "فاتورة ضريبية", "invoice", "total", "tax"),
        DocumentCategory.RECEIPT to listOf("إيصال", "حوالة", "تحويل", "استلام", "دفع", "قبض", "مبلغ", "مرسل", "مستلم", "receipt", "transfer"),
        DocumentCategory.CASH_BOX to listOf("صندوق", "حركة", "إيداع", "سحب", "رصيد", "أمانة", "أمانات", "cash", "box"),
        DocumentCategory.CONTRACT to listOf("عقد", "إتفاق", "بند", "طرف أول", "طرف ثاني", "مدة", "إيجار", "بيع", "شراء", "contract"),
        DocumentCategory.ID_DOCUMENT to listOf("هوية", "جواز", "رقم قومي", "بطاقة", "سجل مدني", "تاريخ ميلاد", "id", "passport", "national id"),
        DocumentCategory.NOTE to listOf("ملاحظة", "ملاحظات", "تذكير", "هام", "هام جداً", "note", "memo")
    )

    val matches = mutableMapOf<DocumentCategory, MutableList<String>>()
    var totalMatches = 0

    keywords.forEach { (category, words) ->
        val found = words.filter { word ->
            normalized.contains(word, ignoreCase = true)
        }
        if (found.isNotEmpty()) {
            matches[category] = found.toMutableList()
            totalMatches += found.size
        }
    }

    return when {
        // لا توجد أي مطابقة
        totalMatches == 0 -> ContentVerdict.Rejected("لا توجد كلمات مفتاحية لمستند")

        // مطابقة واحدة فقط → غامض (قد يكون نصاً عشوائياً)
        totalMatches == 1 -> {
            val (cat, words) = matches.entries.first()
            ContentVerdict.Ambiguous(words)
        }

        // مطابقة في أكثر من فئة → غامض (نص مختلط)
        matches.size > 1 -> {
            val allWords = matches.values.flatten()
            ContentVerdict.Ambiguous(allWords)
        }

        // مطابقة قوية في فئة واحدة فقط → مقبول
        else -> {
            val (cat, words) = matches.entries.first()
            ContentVerdict.Accepted(words, cat)
        }
    }
}

/** يطبع النص: يزيل التشكيل، يوحد المسافات، يحول للأحرف الأساسية. */
private fun normalize(text: String): String {
    return text
        // إزالة التشكيل العربي (علامات التشكيل: 0x064B-0x065F)
        .replace(Regex("[\\u064B-\\u065F]"), "")
        // توحيد الألف: أ إ آ → ا
        .replace(Regex("[\\u0622\\u0623\\u0625]"), "ا")
        // توحيد الياء: ي ى → ي
        .replace(Regex("[\\u0649\\u064A]"), "ي")
        // توحيد التاء المربوطة: ة → ه
        .replace("ة", "ه")
        // إزالة علامات الترقيم والرموز
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        // توحيد المسافات
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()
}

/**
 * التحليل الذكي بنظام هجين:
 * - فحص سريع محلي أولًا دائمًا: تصنيف SmartClassifier + OCR نموذج النظام (Android 13+)
 *   + اكتشاف الوجوه — كلها تعمل بدون إنترنت.
 * - استدعاء سحابي (Gemini) عند الحاجة فقط لتقليل استهلاك الحد اليومي:
 *   • التصنيف: صورة بلا وسوم أو ثقة منخفضة (< 0.6) أو مستند/لقطة شاشة
 *     (يستحق وصفًا عربيًا واستخراج بيانات فاتورة).
 *   • OCR: نص محلي قصير/غائب أو جهاز دون Android 13 — النص السحابي عربي دقيق.
 * - كل محاولة سحابية تحجز من حصة يومية (tryConsumeGeminiQuota)؛ عند نفادها
 *   أو غياب المفتاح أو أي خطأ شبكة يكمل المسح بالمحلي بهدوء بلا استثناء.
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

        /** ثقة محلية أقل منها تُعتبر الصورة غامضة → استدعاء سحابي. */
        private const val LOW_CONFIDENCE = 0.60f

        /** أدنى طول مقبول للنص المحلي قبل اعتباره كافيًا (يمنع نداء السحابي دون داعٍ). */
        private const val MIN_LOCAL_OCR_CHARS = 20

        /** وسمَي الوجوه يلتقطهما ML Kit محليًا أدقّ، فتُدمجان مع نتيجة السحابة. */
        private val FACE_LABELS = setOf("صورة شخص / وجه", "مجموعة أشخاص")

        /** الوسوم التي تدل على مستند يستحق وصفًا سحابيًا واستخراج فاتورة. */
        private val DOCUMENT_LABELS = setOf("مستند / ملف نصي", "لقطة شاشة")
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = ArchiveRepository(context)
        val prefs = SettingsPreferences(context)

        val ocrEnabled = prefs.aiOcrEnabled.first()
        val labelsEnabled = prefs.aiLabelsEnabled.first()
        val facesEnabled = prefs.aiFacesEnabled.first()
        val usePro = prefs.geminiUsePro.first()
        // جاهزية السحابة: المفتاح موجود فعليًا + التفعيل مضمّن — وإلا فحص محلي فقط
        val cloudReady = prefs.geminiCloudEnabled.first() && GeminiRepository.isConfigured()
        GeminiRepository.resetRateLimit()
        if (!ocrEnabled && !labelsEnabled && !facesEnabled) return Result.success()

        /** يحجز محاولة واحدة من الحصة اليومية (false = نفد الحد اليومي). */
        suspend fun consumeQuota(): Boolean = prefs.tryConsumeGeminiQuota(GeminiRepository.DAILY_LIMIT)

        /** هل تحتاج الصورة استدعاء سحابي للتصنيف؟ (غامضة أو مستند يستحق وصف/فاتورة) */
        fun needsCloudClassification(local: List<ClassifiedLabel>): Boolean {
            val top = local.firstOrNull() ?: return true
            if (top.confidence < LOW_CONFIDENCE) return true
            return local.any { it.label in DOCUMENT_LABELS && it.confidence >= 0.5f }
        }

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
        var cloudCalls = 0
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
                    val local = SmartClassifier.classifyFile(file, faces.size)
                    var final = local
                    if (cloudReady && !GeminiRepository.rateLimited &&
                        needsCloudClassification(local) && consumeQuota()
                    ) {
                        GeminiClassifier.classify(file, usePro)?.let { result ->
                            cloudCalls++
                            // وسوم الوجوه المحلية تُدمج مع نتيجة السحابة (لا نفقدها)
                            val merged = (result.labels + local.filter { it.label in FACE_LABELS })
                                .distinctBy { it.label }
                                .sortedByDescending { it.confidence }
                                .take(6)
                            if (merged.isNotEmpty()) final = merged
                            if (result.description.isNotBlank() || result.invoice != null) {
                                repository.saveCloudMeta(
                                    image.id, result.description, result.invoice,
                                    GeminiRepository.model(usePro)
                                )
                            }
                        }
                    }
                    if (final.isNotEmpty()) repository.saveLabels(image.id, final)
                }

                if (ocrEnabled) {
                    val localText = if (OcrEngine.isAvailable(context)) {
                        OcrEngine.recognizeFile(context, file)
                    } else null
                    val localAdequate = !localText.isNullOrBlank() && localText.length >= MIN_LOCAL_OCR_CHARS
                    var saved = false
                    var finalText: String? = null
                    if (localAdequate) {
                        repository.saveOcrText(image.id, localText!!)
                        finalText = localText
                        saved = true
                    } else if (cloudReady && !GeminiRepository.rateLimited && consumeQuota()) {
                        GeminiRepository.extractText(file, usePro)?.let { cloudText ->
                            if (cloudText.isNotBlank() && cloudText != "لا يوجد نص") {
                                repository.saveOcrText(image.id, cloudText)
                                finalText = cloudText
                                saved = true
                                cloudCalls++
                            }
                        }
                    }
                    // آخر احتياطي: نص محلي ضعيف يُحفظ على أي حال إن لم يأتِ السحابي
                    if (!saved && !localText.isNullOrBlank()) {
                        repository.saveOcrText(image.id, localText)
                        finalText = localText
                    }

                    // المرحلة 2: التحقق النصي من محتوى المستند
                    finalText?.let { text ->
                        val verdict = verify(text)
                        val status = when (verdict) {
                            is ContentVerdict.Accepted -> ContentVerified.ACCEPTED
                            is ContentVerdict.Ambiguous -> ContentVerified.AMBIGUOUS
                            is ContentVerdict.Rejected -> ContentVerified.REJECTED
                            else -> ContentVerified.UNVERIFIED
                        }
                        repository.updateContentVerified(image.id, status)
                    }
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

        if (cloudCalls > 0) Log.d(TAG, "استدعاءات Gemini السحابية في هذه الدفعة: $cloudCalls")

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