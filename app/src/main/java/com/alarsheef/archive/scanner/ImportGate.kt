package com.alarsheef.archive.scanner

import android.util.Log
import com.alarsheef.archive.ai.DocumentShape
import com.alarsheef.archive.ai.SmartClassifier
import com.alarsheef.archive.data.entities.ContentVerified
import java.io.File

/**
 * بوابة الاستيراد: تُقرّر قبل الاستيراد ما إذا كانت الصورة تستحق الأرشفة، وبأي حالة.
 *
 * القرار على مرحلتين، وكلاهما دالة نقية تُختبر على JVM:
 * 1. [decide]: شكل الصورة + وجود وجه ← قوة الدليل ([Decision]).
 * 2. [finalStatus]: قوة الدليل + ما قاله النص (OCR) ← حالة الاستيراد أو الرفض.
 *
 * الفحص الآلي لا يُدخل الأرشيف الظاهر إلا بنص يثبت المستند ([TextEvidence.ACCEPTED]).
 * - شكل مستند بلا وجه، وتعذّرت القراءة أو قُرئ النص بلا دلائل كافية → «قيد المراجعة» فقط.
 * - نص جزئي بلا وجه شخصي → «قيد المراجعة» فقط.
 * - أي صورة فيها وجه، أو شكلها ليس مستندًا، لا تدخل المشروع إلا بنص قاطع.
 * - تأكيد أن لا نص → لا تُستورد.
 * - لا مفتاح يفتح المعرض: الشكل يُفحص دائمًا، والوجه يُستبعد دائمًا.
 *
 * كل قرار يُسجَّل في logcat (وسم `ImportGate`) حتى لا يمرّ أي ملف بصمت.
 *
 * لا تُطبَّق على الاستيراد اليدوي (اختيار المستخدم الصريح). تُطبَّق على كل
 * صورة من المكتبة العامة وعلى كل صفحة PDF محوَّلة إلى صورة.
 */
object ImportGate {

    private const val TAG = "ImportGate"

    /** الرد الذي يطلبه OCR_PROMPT السحابي حين لا يوجد نص في الصورة. */
    private const val NO_TEXT_REPLY = "لا يوجد نص"

    /** قوة الدليل على أن الصورة مستند، بعد أخذ الوجه في الحسبان. */
    enum class Decision {
        /** لا تُستورد مهما قال النص. */
        REJECT,
        /** دليل شكلي قوي بلا وجه — لا تظهر إلا بنص مستندي. */
        STRONG,
        /** دليل شكلي ضعيف بلا وجه — تحتاج النص لتظهر ضمن المستندات. */
        WEAK,
        /** شكل مستند + وجه (هوية/جواز محتملة) — لا تظهر إلا بدليل نصي. */
        FACE_STRONG,
        /** شكل لقطة شاشة + وجه (صورة شخصية على الأرجح) — تُرفض ما لم يثبت النص العكس. */
        FACE_WEAK,
    }

    /** خلاصة قراءة النص (OCR) للملف. */
    enum class TextEvidence {
        /** لا مصدر OCR متاح في هذا الفحص، أو الوضع ليس "المستندات فقط". */
        NOT_CHECKED,
        /** حاولنا ولم نحصل على جواب (حصة منتهية، شبكة، خطأ) — المحتوى مجهول. */
        UNREADABLE,
        /** OCR قرأ الصورة وأكّد أن لا نص فيها إطلاقًا. */
        NO_TEXT,
        /** نص فيه دليل جزئي لا يكفي للحسم. */
        PARTIAL,
        /** نص فيه دلائل مستند مالي/إداري كافية. */
        ACCEPTED,
        /** نص مقروء بلا أي دلائل مستندية. */
        REJECTED,
    }

    /**
     * يفحص شكل الملف ويعيد قوة الدليل، مع تسجيل القرار.
     * @param hasFace هل اكتُشف وجه في الصورة.
     * @param excludePersonalPhotos إعداد "استثناء الصور الشخصية/العائلية".
     * @param documentsOnly إعداد "استيراد المستندات فقط".
     * @param displayName الاسم الأصلي للملف (للسجل فقط).
     */
    /** نتيجة الشكل: القرار، وشكل الصورة بعد الفحص. */
    class Assessment(val decision: Decision, val shape: DocumentShape?)

    fun evaluate(
        file: File,
        hasFace: Boolean,
        excludePersonalPhotos: Boolean,
        @Suppress("UNUSED_PARAMETER") documentsOnly: Boolean,
        displayName: String = file.name,
    ): Assessment {
        val shape = SmartClassifier.documentShape(file, if (hasFace) 1 else 0)
        val decision = decide(shape, hasFace, excludePersonalPhotos, documentsOnly = true)
        Log.i(TAG, "$decision (شكل=${shape ?: "-"}، وجه=$hasFace): $displayName")
        return Assessment(decision, shape)
    }

    /**
     * @param shape شكل الصورة. يُفحص دائمًا؛ إغلاق «المستندات فقط» لم يعد يتخطى الشكل.
     * @param documentsOnly مُبقى في التوقيع حتى لا تنكسر الاستدعاءات. لا يغيّر القرار.
     */
    fun decide(
        shape: DocumentShape?,
        hasFace: Boolean,
        @Suppress("UNUSED_PARAMETER") excludePersonalPhotos: Boolean,
        @Suppress("UNUSED_PARAMETER") documentsOnly: Boolean,
    ): Decision {
        return when (shape) {
            null, DocumentShape.NOT_DOCUMENT -> Decision.REJECT
            DocumentShape.DOCUMENT -> if (hasFace) Decision.FACE_STRONG else Decision.STRONG
            DocumentShape.SCREENSHOT -> if (hasFace) Decision.FACE_WEAK else Decision.WEAK
            DocumentShape.PLAUSIBLE -> if (hasFace) Decision.REJECT else Decision.WEAK
        }
    }

    /**
     * الحالة التي يُستورد بها الملف، أو null = لا يُستورد.
     * - [ContentVerified.ACCEPTED]/[ContentVerified.UNVERIFIED] يظهران ضمن الأرشيف.
     * - [ContentVerified.AMBIGUOUS] يُحفظ مخفيًا في «قيد المراجعة» ليحسمه المستخدم.
     */
    fun finalStatus(
        decision: Decision,
        evidence: TextEvidence,
        shape: DocumentShape? = null,
    ): ContentVerified? {
        if (decision == Decision.REJECT) return null
        // وجه بلا نص قاطع: صورة عائلية أو هوية لم تُثبت. لا تدخل الأرشيف ولا المراجعة.
        if (hasFace(decision) && evidence != TextEvidence.ACCEPTED && evidence != TextEvidence.PARTIAL) {
            return null
        }
        return when (evidence) {
            TextEvidence.ACCEPTED -> ContentVerified.ACCEPTED
            // نص جزئي: ورقة محتملة تُراجع. وجه على خلفية ناعمة يبقى صورة شخصية ويُرفض.
            TextEvidence.PARTIAL -> when (decision) {
                Decision.FACE_WEAK -> null
                else -> ContentVerified.AMBIGUOUS
            }
            // شكل مستند بلا وجه، والقراءة فشلت أو النص بلا دلائل كافية: تُراجع ولا تظهر.
            // لقطة الشاشة والشكل الضعيف والوجه وتأكيد «لا نص» لا تدخل.
            TextEvidence.UNREADABLE,
            TextEvidence.REJECTED ->
                if (shape == DocumentShape.DOCUMENT && decision == Decision.STRONG) {
                    ContentVerified.AMBIGUOUS
                } else {
                    null
                }
            TextEvidence.NO_TEXT,
            TextEvidence.NOT_CHECKED -> null
        }
    }

    private fun hasFace(decision: Decision): Boolean =
        decision == Decision.FACE_STRONG || decision == Decision.FACE_WEAK

    /**
     * هل ردّ OCR السحابي يعني "الصورة بلا نص"؟ يتسامح مع نقطة ختامية أو صياغة قريبة
     * («لا يوجد نص في الصورة.») — المقارنة الحرفية كانت تعدّ هذه نصًّا مقروءًا.
     */
    fun isNoTextReply(reply: String): Boolean {
        val text = reply.trim().trim('.', '!', '"', '«', '»', '،', ' ', '\n', '\r')
        return text == NO_TEXT_REPLY || (text.length <= 30 && text.contains(NO_TEXT_REPLY))
    }
}
