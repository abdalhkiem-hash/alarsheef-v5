package com.alarsheef.archive.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alarsheef.archive.ai.ClassifiedLabel
import com.alarsheef.archive.ai.FaceGroupingEngine
import com.alarsheef.archive.ai.GeminiClassifier
import com.alarsheef.archive.ai.GeminiRepository
import com.alarsheef.archive.ai.InvoiceInfo
import com.alarsheef.archive.ai.OcrEngine
import com.alarsheef.archive.ai.SmartClassifier
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.scanner.ImportGate
import com.alarsheef.archive.scanner.ImportRules
import com.alarsheef.archive.util.ArchiveNaming
import com.alarsheef.archive.util.DocumentName
import com.alarsheef.archive.settings.SettingsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * التحليل الذكي بنظام هجين:
 * - فحص سريع محلي أولًا دائمًا: تصنيف SmartClassifier + OCR نموذج النظام (Android 13+)
 *   + اكتشاف الوجوه — كلها تعمل بدون إنترنت.
 * - استدعاء سحابي (Gemini) عند الحاجة فقط لتقليل استهلاك الحد اليومي:
 *   • التصنيف: صورة بلا وسوم أو ثقة منخفضة (< 0.6) أو مستند/لقطة شاشة
 *     (يستحق وصفًا عربيًا واستخراج بيانات فاتورة).
 *   • OCR: نص محلي قصير/غائب أو جهاز دون Android 13 — النص السحابي عربي دقيق.
 * - الحصة تُخصم بعد رد ناجح فقط. خطأ 503 أو انقطاع الشبكة يوقف النداءات
 *   التالية في هذه الدفعة ولا يخصم الحصة، ويكمل المسح بالتسمية المحلية.
 *
 * يعالج الدفعات (حتى MAX_PER_RUN لكل تشغيل) ويسلّم التكملة لنفسه إن بقي
 * أرشيف غير محلل، ثم تُرمَّز الصور لذلك لا تُعاد معالجتها كل يوم.
 */
class AiAnalysisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
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

        /** إعادة محاولة التسمية تشمل ما استُورد خلال هذه المدة فقط — لا إعادة تسمية جماعية للأرشيف القديم. */
        private const val RENAME_RETRY_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        /** أقصى ما يُجلب من المرشّحين، وأقصى ما يُحاوَل في التشغيل الواحد. */
        private const val RETRY_QUERY_LIMIT = 300
        private const val MAX_RETRY_PER_RUN = 20

        /** سقف تمريرة التسمية المحلية في التشغيل الواحد. */
        private const val MAX_LOCAL_RENAME = 200

        /** إخفاقات سحابية متتالية توقف إعادة المحاولة في هذا التشغيل. */
        private const val MAX_RETRY_FAILURES = 3
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
        val userTypes = repository.documentTypeRules()
        val localRenamed = applyLocalTypeNames(repository, userTypes)
        if (localRenamed > 0) Log.i(TAG, "تسمية محلية من النص المحفوظ: $localRenamed ملف")
        if (!ocrEnabled && !labelsEnabled && !facesEnabled) return Result.success()

        fun cloudAvailable(): Boolean = cloudReady && !GeminiRepository.rateLimited

        /** هل بقي من الحصة ما يكفي لنداء، بدون خصم. */
        suspend fun canCallCloud(limit: Int = GeminiRepository.DAILY_LIMIT): Boolean =
            cloudAvailable() && prefs.hasGeminiQuota(limit)

        /** يخصم الحصة بعد رد ناجح فقط. */
        suspend fun commitQuota(limit: Int = GeminiRepository.DAILY_LIMIT) {
            prefs.commitGeminiQuota(limit)
        }

        /** هل تحتاج الصورة استدعاء سحابي للتصنيف؟ (غامضة أو مستند يستحق وصف/فاتورة) */
        fun needsCloudClassification(local: List<ClassifiedLabel>): Boolean {
            val top = local.firstOrNull() ?: return true
            if (top.confidence < LOW_CONFIDENCE) return true
            return local.any { it.label in DOCUMENT_LABELS && it.confidence >= 0.5f }
        }

        val facesRoot = File(context.filesDir, "face_crops").apply { mkdirs() }

        // ممثلو المجموعات الحالية — لاستمرارية التجميع عبر التشغيلات
        // (بصمة تالفة في القاعدة تُتجاهل بدل أن تُسقط كل دفعات التحليل)
        val groupReps = repository.faceGroupRepresentatives().mapNotNull { rep ->
            runCatching { rep.groupKey to java.lang.Long.parseUnsignedLong(rep.repHash, 16) }.getOrNull()
        }.toMap().toMutableMap()

        val pending = repository.pendingAiImages(MAX_PER_RUN)
        if (pending.isEmpty()) {
            // لا جديد للتحليل: فرصة لتسمية المستندات التي فاتتها التسمية الذكية
            if (labelsEnabled && cloudReady) retrySmartNaming(repository, prefs, usePro, userTypes)
            return Result.success()
        }

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
        var renamed = 0
        for (image in pending) {
            val file = File(image.storedPath)
            if (!file.exists()) {
                repository.markAiAnalyzed(image.id)
                continue
            }
            // وثيقة خام (docx/xlsx من مجلد SAF) أو صورة تالفة: لا شيء فيها يُحلَّل محليًا، وكانت
            // تحجز محاولتين من الحصة السحابية اليومية (تصنيف + OCR) بلا أي نداء فعلي.
            if (!isDecodableImage(file)) {
                repository.markAiAnalyzed(image.id)
                continue
            }

            // بيانات الفاتورة من السحابة + نص الصورة: مُدخلا التسمية في آخر خطوة
            var invoice: InvoiceInfo? = null
            var ocrText: String? = null
            try {
                // تمريرة واحدة لاكتشاف الوجوه — تستخدم للإشارة في التصنيف وللتجميع معًا
                val faces = if (facesEnabled) {
                    FaceGroupingEngine.detectFaces(file)
                } else emptyList()

                if (labelsEnabled) {
                    val local = SmartClassifier.classifyFile(file, faces.size)
                    var final = local
                    // مستند مثبت (قبلته البوابة النصية أو يحمل بادئة نوع) يستحق الاسم الذكي دائمًا.
                    // كان النداء السحابي يُتخطى حين يثق المصنّف المحلي بوسم غير مستندي
                    // (فاتورة مصوَّرة على طاولة = «داخلي دافئ») فلا تُسمّى أبدًا.
                    val provenDocument = image.contentVerified == ContentVerified.ACCEPTED ||
                        ArchiveNaming.labelOf(image.fileName) != null
                    if (canCallCloud() && (provenDocument || needsCloudClassification(local))) {
                        GeminiClassifier.classify(file, usePro)?.let { result ->
                            commitQuota()
                            cloudCalls++
                            // وسوم الوجوه المحلية تُدمج مع نتيجة السحابة (لا نفقدها)
                            val merged = (result.labels + local.filter { it.label in FACE_LABELS })
                                .distinctBy { it.label }
                                .sortedByDescending { it.confidence }
                                .take(6)
                            if (merged.isNotEmpty()) final = merged
                            // يُحفظ دائمًا عند نجاح النداء (ولو بلا وصف): وجود الصف هو علامة
                            // «صُنِّف سحابيًا» التي تُخرج الملف من إعادة المحاولة.
                            repository.saveCloudMeta(
                                image.id, result.description, result.invoice,
                                GeminiRepository.model(usePro)
                            )
                            invoice = result.invoice
                        }
                    }
                    if (final.isNotEmpty()) repository.saveLabels(image.id, final)
                }

                // نص قرأته بوابة الاستيراد (أو تحليل سابق) يُعاد استخدامه — كان يُطلب OCR سحابي
                // ثانٍ للصورة نفسها، فتنفد الحصة اليومية قبل الوصول إلى التسمية الذكية.
                val storedText = repository.ocrTextFor(image.id)?.takeIf { it.isNotBlank() }
                if (storedText != null) {
                    ocrText = storedText
                } else if (ocrEnabled) {
                    val localText = if (OcrEngine.isAvailable(context)) {
                        OcrEngine.recognizeFile(context, file)
                    } else null
                    val localAdequate = !localText.isNullOrBlank() && localText.length >= MIN_LOCAL_OCR_CHARS
                    var saved = false
                    if (localAdequate) {
                        repository.saveOcrText(image.id, localText!!)
                        saved = true
                        ocrText = localText
                    } else if (canCallCloud()) {
                        GeminiRepository.extractText(file, usePro)?.let { cloudText ->
                            if (cloudText.isNotBlank() && !ImportGate.isNoTextReply(cloudText)) {
                                commitQuota()
                                repository.saveOcrText(image.id, cloudText)
                                saved = true
                                cloudCalls++
                                ocrText = cloudText
                            }
                        }
                    }
                    // آخر احتياطي: نص محلي ضعيف يُحفظ على أي حال إن لم يأتِ السحابي
                    if (!saved && !localText.isNullOrBlank()) {
                        repository.saveOcrText(image.id, localText)
                        ocrText = localText
                    }
                }

                if (facesEnabled) {
                    // قبل الإدراج: تطهير أي أعضاء سابقة لهذه الصورة (حالة إعادة التحليل)
                    // حتى لا تتضاعف المجموعات ولا تتراكم ملفات القصّ على القرص —
                    // ويشمل صورة لم يعد يُرصد فيها وجه (كانت تبقى أعضاؤها القديمة).
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // صورة واحدة تالفة كانت تُفشل العامل كله وتبقى أول القائمة فيتوقف التحليل
                // عندها للأبد — تُعلَّم محلَّلة ويُكمَل بالباقي.
                Log.w(TAG, "تعذّر تحليل الصورة ${image.id}: ${e.javaClass.simpleName}: ${e.message}")
                repository.markAiAnalyzed(image.id)
                continue
            }

            // آخر خطوة للصورة: إعادة التسمية (بعد انتهاء كل استخدامات الملف بمساره القديم).
            // اسم ذكي كامل إن أعادت السحابة جهة/مبلغ، وإلا بادئة النوع من النص بدل IMG.
            if (applyPlannedName(repository, image, ocrText, invoice, userTypes)) renamed++

            repository.markAiAnalyzed(image.id)
            processed++
        }

        if (cloudCalls > 0) Log.d(TAG, "استدعاءات Gemini السحابية في هذه الدفعة: $cloudCalls")
        if (renamed > 0) Log.i(TAG, "أُعيدت تسمية $renamed ملف في هذه الدفعة")

        // لو بقي كثير غير محلل: نسلّم دفعة أخرى لنفس العامل
        if (repository.pendingAiCountOnce() > 0) {
            AiAnalysisScheduler.continueAfterBatch(context)
        } else if (labelsEnabled && cloudReady) {
            retrySmartNaming(repository, prefs, usePro, userTypes)
        }

        // كل صورة في الدفعة عُلِّمت محلَّلة (حتى المفقودة والتالفة) فالتقدّم مضمون —
        // retry هنا كان يؤخّر الدفعة الملحقة بلا فائدة.
        Log.d(TAG, "انتهت الدفعة: $processed صورة من ${pending.size}")
        return Result.success()
    }

    /**
     * يطبّق الاسم المخطَّط ([ArchiveNaming.plannedBase]) إن وُجد.
     * @return true إذا تغيّر اسم الملف فعلًا.
     */
    private suspend fun applyPlannedName(
        repository: ArchiveRepository,
        image: ArchivedImage,
        ocrText: String?,
        invoice: InvoiceInfo?,
        userTypes: List<DocumentName.UserType>,
    ): Boolean {
        val base = ArchiveNaming.plannedBase(
            fileName = image.fileName,
            nameLocked = image.nameLocked,
            ocrText = ocrText,
            vendor = invoice?.vendor,
            invoiceDate = invoice?.date,
            amount = invoice?.amount,
            currency = invoice?.currency,
            fallbackDate = String.format(
                java.util.Locale.ROOT, "%04d-%02d-%02d", image.year, image.month, image.day
            ),
            userTypes = userTypes,
            pageNumber = ImportRules.pageNumberOf(image.originalPath, image.originalName),
        ) ?: return false
        val updated = runCatching { repository.renameImage(image, base, lock = false) }.getOrNull()
        if (updated == null) Log.w(TAG, "تعذّرت إعادة تسمية ${image.fileName} إلى $base")
        return updated != null && updated.fileName != image.fileName
    }

    /**
     * إعادة محاولة التسمية الذكية لمستندات حُلِّلت ولم ينجح لها تصنيف سحابي — مصدر الاسم
     * الذكي الوحيد. كان التحليل محاولة واحدة: إن نفدت الحصة يومها أو انقطعت الشبكة أو ردّ
     * Google بـ429 عُلِّمت الصورة «محلَّلة» وبقيت باسمها المؤقت للأبد.
     *
     * محدودة عمدًا: أحدث [MAX_RETRY_PER_RUN] مستند مما استُورد خلال [RENAME_RETRY_WINDOW_MS].
     * الحصة تُخصم بعد رد ناجح فقط، وتقف الدفعة عند توقف الخدمة.
     */
    private suspend fun retrySmartNaming(
        repository: ArchiveRepository,
        prefs: SettingsPreferences,
        usePro: Boolean,
        userTypes: List<DocumentName.UserType>,
    ) {
        val since = System.currentTimeMillis() - RENAME_RETRY_WINDOW_MS
        val candidates = repository.smartNameCandidates(since, RETRY_QUERY_LIMIT).take(MAX_RETRY_PER_RUN)
        if (candidates.isEmpty()) return

        var renamed = 0
        var classified = 0
        var failures = 0
        for (image in candidates) {
            if (GeminiRepository.rateLimited) break
            val file = File(image.storedPath)
            if (!file.exists()) continue
            if (!prefs.hasGeminiQuota(GeminiRepository.DAILY_LIMIT)) break

            val result = GeminiClassifier.classify(file, usePro)
            if (result == null) {
                if (GeminiRepository.rateLimited) break
                if (++failures >= MAX_RETRY_FAILURES) break
                continue
            }
            prefs.commitGeminiQuota(GeminiRepository.DAILY_LIMIT)
            failures = 0
            classified++
            repository.saveCloudMeta(image.id, result.description, result.invoice, GeminiRepository.model(usePro))
            if (applyPlannedName(repository, image, repository.ocrTextFor(image.id), result.invoice, userTypes)) renamed++
        }
        Log.i(TAG, "إعادة محاولة التسمية: ${candidates.size} مرشّح، صُنِّف $classified، أُعيدت تسمية $renamed")
    }

    /** يبني الاسم من نص الورقة للأسماء التلقائية، ومنها `مستند_`. بلا Gemini. */
    private suspend fun applyLocalTypeNames(
        repository: ArchiveRepository,
        userTypes: List<DocumentName.UserType>,
    ): Int {
        var renamed = 0
        for (image in repository.imagesAwaitingLocalTypeName(MAX_LOCAL_RENAME)) {
            val text = repository.ocrTextFor(image.id) ?: continue
            if (applyPlannedName(repository, image, text, invoice = null, userTypes)) renamed++
        }
        return renamed
    }

    /** هل يُفكّ الملف كصورة؟ يقرأ الأبعاد فقط بلا تحميل البكسلات. */
    private fun isDecodableImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
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