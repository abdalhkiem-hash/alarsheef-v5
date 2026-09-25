package com.alarsheef.archive.scanner

import android.util.Log
import com.alarsheef.archive.ai.SmartClassifier
import java.io.File

/**
 * بوابة المحتوى: تُقرّر قبل الاستيراد ما إذا كانت الصورة تستحق الأرشفة.
 *
 * السياسة (وضع "المستندات فقط"، وهو الافتراضي):
 * - مناظر/طبيعة → تُرفض (سماء، ماء، حديقة) حتى لو تشابهت مع لقطة شاشة.
 * - مستندات وفواتير وإيصالات وحركات بنكية ولو لاقت صورة وجه فيها
 *   (هوية/جواز/فاتورة باسم شخص) → تُقبل، فالمستند أقوى من الوجه.
 * - أي شيء آخر فيه وجه (صورة عائلية/شخصية) → يُرفض.
 * - الحالات الغامضة → العتبة الفضفاضة داخل [SmartClassifier.isDocumentLike].
 *
 * كل قرار يُسجَّل في logcat حتى لا يمرّ أي ملف بصمت (سبب التخطي ظاهر).
 *
 * لا تُطبَّق على الاستيراد اليدوي (اختيار المستخدم الصريح) ولا على ملفات
 * PDF/الوثائق — فهي مستندات بطبيعتها.
 */
object ImportGate {

    private const val TAG = "ImportGate"

    /**
     * @param hasFace هل اكتُشف وجه في الصورة.
     * @param excludePersonalPhotos إعداد "استثناء الصور الشخصية/العائلية".
     * @param documentsOnly إعداد "استيراد المستندات فقط".
     * @param displayName الاسم الأصلي للملف (للسجل فقط).
     */
    fun shouldArchive(
        file: File,
        hasFace: Boolean,
        excludePersonalPhotos: Boolean,
        documentsOnly: Boolean,
        displayName: String = file.name,
    ): Boolean {
        val accepted = if (documentsOnly) {
            SmartClassifier.isDocumentLike(file, if (hasFace) 1 else 0)
        } else {
            !(hasFace && excludePersonalPhotos)
        }
        Log.i(TAG, (if (accepted) "قبول: " else "رفض: ") + displayName)
        return accepted
    }
}
