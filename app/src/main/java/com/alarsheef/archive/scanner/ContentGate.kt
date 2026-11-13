package com.alarsheef.archive.scanner

import android.content.Context
import com.alarsheef.archive.ai.ContentVerdict
import com.alarsheef.archive.ai.DocumentCategory
import com.alarsheef.archive.ai.DocumentContentGate
import com.alarsheef.archive.ai.GeminiRepository
import com.alarsheef.archive.ai.OcrEngine
import com.alarsheef.archive.ai.OcrQuality
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.util.ArchiveNaming
import java.io.File

/**
 * بوابة المحتوى **قبل الاستيراد**: تحصل على نص الملف (أو أول صفحات PDF) ثم تطبّق
 * [DocumentContentGate] عليه، فيُقرَّر القبول/التعليق/الرفض قبل أن يدخل أي شيء الأرشيف.
 *
 * مصدر النص بالترتيب:
 * 1. OCR المحلي على الجهاز (Android 13+ وإن توفّر النموذج) — بلا تكلفة.
 * 2. OCR سحابي عبر Gemini — يستهلك محاولة من الحصة اليومية ([GeminiRepository.DAILY_LIMIT]).
 *
 * تُستدعى فقط بعد نجاح الفلتر الشكلي المجاني — فلا يُشغَّل OCR إلا على القلة الناجية منه.
 */
object ContentGate {

    /** أقصى عدد صفحات PDF يُقرأ نصّها لاتخاذ قرار واحد للمستند كله. */
    const val MAX_PDF_PAGES = 3

    /** سقف استدعاءات Gemini اليومية لتصعيد القراءة الفاشلة قبل الاستيراد؛ الباقي للتحليل بعده. */
    const val GATE_CLOUD_LIMIT = 30

    sealed class Outcome {
        /**
         * [label] بادئة الاسم المؤقت (فاتورة/حوالة/إيصال/...) للملف المستورد.
         * [text] النص المقروء — يُحفظ مع الملف حتى لا يُعاد OCR عليه عند التحليل.
         */
        data class Accepted(val category: DocumentCategory, val label: String, val text: String) : Outcome()
        /** نص فيه دليل جزئي لا يكفي للحسم. */
        data class Ambiguous(val text: String) : Outcome()
        data class Rejected(val reason: String, val clear: Boolean = false) : Outcome()

        /** OCR قرأ كل الصفحات المفحوصة وأكّد أن لا نص فيها إطلاقًا. */
        data object NoText : Outcome()

        /** تعذّرت القراءة (لا مصدر نص، حصة منتهية، خطأ شبكة) — المحتوى مجهول. */
        data object Unavailable : Outcome()
    }

    /** هل توجد وسيلة لاستخراج النص الآن؟ (محلي، أو سحابي مُهيّأ ولم يُستنفد حده) */
    fun isOcrAvailable(context: Context): Boolean =
        OcrEngine.isAvailable(context) || (GeminiRepository.isConfigured() && !GeminiRepository.rateLimited)

    /**
     * يقرأ نص الملف أو أول [MAX_PDF_PAGES] صفحات بلا حكم قبول أو رفض.
     * مجلد الإدخال يؤرشف الملف على كل حال، والنص يُحفظ للتسمية والبحث.
     */
    suspend fun readText(context: Context, files: List<File>): String? {
        if (files.isEmpty() || !OcrEngine.isAvailable(context)) return null
        val texts = files.take(MAX_PDF_PAGES).mapNotNull { file ->
            OcrEngine.recognizeFile(context, file)?.takeIf { it.isNotBlank() }
        }
        return texts.joinToString("\n").ifBlank { null }
    }

    /**
     * @param allowCloud يسمح بـ Gemini لكل ملف (الحصة اليومية كاملة).
     * @param escalateWhenPoor يسمح بـ Gemini فقط حين تفشل القراءة المحلية (فارغة أو عشوائية)، وبسقف
     *        [GATE_CLOUD_LIMIT] يترك بقية الحصة للتحليل. تُستعمل للصور الواضحة الشكل كمستند.
     */
    suspend fun evaluate(
        context: Context,
        files: List<File>,
        allowCloud: Boolean = true,
        escalateWhenPoor: Boolean = false,
    ): Outcome {
        if (files.isEmpty()) return Outcome.Unavailable
        val reads = files.take(MAX_PDF_PAGES).map { textOf(context, it, allowCloud, escalateWhenPoor) }
        val texts = reads.mapNotNull { it.text }
        // لم يُقرأ نص: يُفرَّق بين «لا نص فعلًا» (جواب صريح من OCR) و«تعذّرت القراءة»
        // (حصة/شبكة). كان كلاهما «غامض»، فتدخل كل صورة شخصية بلا نص قائمة المراجعة.
        // القرار النهائي (مع الشكل والوجه) في ImportGate.finalStatus.
        if (texts.isEmpty()) {
            return if (reads.all { it.confirmedNoText }) Outcome.NoText else Outcome.Unavailable
        }
        val joined = texts.joinToString("\n")
        return when (val verdict = DocumentContentGate.verify(joined)) {
            is ContentVerdict.Accepted -> Outcome.Accepted(
                verdict.primaryCategory,
                ArchiveNaming.typeLabel(verdict.primaryCategory, verdict.matchedKeywords),
                joined
            )
            is ContentVerdict.Ambiguous -> Outcome.Ambiguous(joined)
            is ContentVerdict.Rejected -> Outcome.Rejected(verdict.reason, verdict.clear)
        }
    }

    /** نتيجة قراءة ملف واحد: نص، أو تأكيد صريح بأن لا نص، أو لا شيء (تعذّرت القراءة). */
    private class PageRead(val text: String? = null, val confirmedNoText: Boolean = false)

    /**
     * نص ملف واحد: محلي أولًا. السحابة اختيارية؛ بوابة الاستيراد تغلقها حتى لا تُحرق
     * الحصة على كل صورة معرض قبل رفضها.
     */
    private suspend fun textOf(context: Context, file: File, allowCloud: Boolean, escalateWhenPoor: Boolean): PageRead {
        val local = OcrEngine.recognizeFile(context, file)?.takeIf { it.isNotBlank() }
        // قراءة محلية موثوقة: تكفي ولا تُستهلك الحصة
        if (local != null && OcrQuality.isUsable(local)) return PageRead(text = local)
        // فارغة أو عشوائية (غالبًا صورة مائلة أو مشوّشة): Gemini قرأ في القياس كل الحالات الصعبة بلا خطأ
        val limit = when {
            allowCloud -> GeminiRepository.DAILY_LIMIT
            escalateWhenPoor -> GATE_CLOUD_LIMIT
            else -> 0
        }
        if (limit > 0 && GeminiRepository.isConfigured() && !GeminiRepository.rateLimited) {
            val prefs = SettingsPreferences(context)
            if (prefs.hasGeminiQuota(limit)) {
                val reply = GeminiRepository.extractText(file, usePro = false)?.takeIf { it.isNotBlank() }
                if (reply != null) {
                    prefs.commitGeminiQuota(GeminiRepository.DAILY_LIMIT)
                    return if (ImportGate.isNoTextReply(reply)) PageRead(confirmedNoText = true) else PageRead(text = reply)
                }
            }
        }
        // لا سحابة متاحة: يبقى النص المحلي الرديء كما كان (لا تراجع عن السلوك السابق)
        return if (local != null) PageRead(text = local) else PageRead()
    }
}
