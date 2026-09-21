package com.alarsheef.archive.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceApp { WHATSAPP, GALLERY, DOWNLOADS, MANUAL_CAMERA, MANUAL_IMPORT }

/**
 * كل عنصر بالأرشيف صورة — حتى لو أصله PDF (يتحوّل لصورة/صور وقت الاستيراد).
 * contentHash: بصمة رقمية (SHA-256) لمحتوى الصورة الفعلي، تُستخدم لاكتشاف التكرار
 * عبر كامل الأرشيف بدل الاعتماد على اسم أو مسار الملف فقط.
 * receivedCount: يُزاد فقط عند وصول نفس الصورة يدويًا مرة ثانية (لا يزيد من الفحص التلقائي).
 */
@Entity(
    tableName = "archived_images",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index(value = ["year", "month", "day"]),
        Index(value = ["originalPath"])
    ]
)
data class ArchivedImage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val fileName: String,
    val storedPath: String,
    val contentHash: String,
    val sourceApp: SourceApp,
    val receivedCount: Int = 1,

    val year: Int,
    val month: Int,
    val day: Int,

    val importedAt: Long,
    val capturedAt: Long,

    /**
     * المسار الأصلي للملف في مصدره (واتساب/استديو/تنزيلات/مجلد مخصص).
     * يُعبَّأ فقط لملفات الفحص التلقائي الثابتة؛ يبقى فارغًا للإضافة اليدوية
     * وصفحات PDF المؤقتة وملفات الاستعادة من ZIP.
     * يمنع تضخّم عدّاد "مرات الاستلام": أي ملف بنفس المسار يُتجاهل كليةً
     * في الفحص اليومي دون إعادة حساب بصمته أو زيادة العدّاد.
     */
    val originalPath: String? = null,

    /**
     * وقت اكتمال التحليل الذكي (OCR + تصنيف + وجوه) لهذه الصورة.
     * null يعني أنه لم يُحلَّل بعد؛ يُستخدم لمعالجة الواردات الجديدة فقط
     * دون إعادة تحليل الأرشيف كله كل يوم.
     */
    val aiAnalyzedAt: Long? = null
)
