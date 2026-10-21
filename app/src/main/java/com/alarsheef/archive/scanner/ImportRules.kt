package com.alarsheef.archive.scanner

import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.util.FileUtils
import java.net.URLDecoder
import java.util.Locale

/**
 * قواعد قرار الاستيراد التلقائي — دوال نقية بلا أي اعتماد على أندرويد، مستخرجة من
 * [FileScannerWorker] حتى تُختبر على JVM مباشرة (`app/src/test`).
 *
 * كل ما هنا "قرار" فقط: من أي مصدر الملف، ما هويته لمنع التكرار، من أي لحظة يبدأ
 * الفحص، وهل يستحق الاستيراد الخام. القراءة والنسخ والاستيراد تبقى في العامل.
 */
object ImportRules {

    /** أقصى رجوع للخلف عند تعويض فحص فائت — حتى لا يُسحب أرشيف قديم دفعة واحدة. */
    const val MAX_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000

    private const val PAGE_MARKER = "#page="
    private const val SAF_DOCUMENT_SEGMENT = "/document/"
    private val REPEATED_SLASHES = Regex("/{2,}")

    /**
     * امتدادات لا تُستورد خامًا من مجلدات SAF: تنزيلات لم تكتمل (تُستورد مبتورة ثم
     * تُعدّ "مؤرشفة")، ووسائط/أرشيفات/تطبيقات لا مكان لها في أرشيف مستندات.
     */
    private val RAW_BLOCKED_EXTENSIONS = setOf(
        "crdownload", "part", "partial", "tmp", "temp", "download",
        "mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "amr", "flac",
        "apk", "apks", "xapk", "zip", "rar", "7z", "tar", "gz", "iso", "exe", "bin",
    )

    /**
     * مجلد صور أو وثائق واتساب أعمال. الملصقات والفيديو والصوت والحالات وصور الملف الشخصي تُستبعد.
     * واتساب العادي والمعرض والتنزيلات ليست عملًا.
     */
    fun isBusinessWorkPath(relativePath: String): Boolean {
        if (sourceFromRelativePath(relativePath) != SourceApp.WHATSAPP_BUSINESS) return false
        val p = relativePath.lowercase(Locale.ROOT)
        if (BUSINESS_SKIPPED.any { p.contains(it) }) return false
        return p.contains("image") || p.contains("document")
    }

    /** وقت إضافة الملف إلى الجهاز (ثوانٍ كما يعيده MediaStore) يقع ضمن اليوم المحلي. */
    fun arrivedToday(dateAddedSeconds: Long, startOfTodayMillis: Long): Boolean =
        dateAddedSeconds > 0L && dateAddedSeconds * 1000L >= startOfTodayMillis

    /**
     * ما يصل اليوم إلى واتساب أعمال ويُؤرشف: صورة إشعار أو مستند، أو PDF.
     * الملف الأقدم من اليوم، والناقص، وgif، لا يدخل.
     */
    fun isBusinessArrival(
        relativePath: String,
        name: String,
        sizeBytes: Long,
        dateAddedSeconds: Long,
        startOfTodayMillis: Long,
    ): Boolean {
        if (!arrivedToday(dateAddedSeconds, startOfTodayMillis)) return false
        if (!isBusinessWorkPath(relativePath)) return false
        if (FileUtils.extensionOf(name) == "gif") return false
        val kind = inboxFileKind(name, sizeBytes)
        return kind == InboxFileKind.IMAGE || kind == InboxFileKind.PDF
    }

    private val BUSINESS_SKIPPED = listOf("sticker", "video", "audio", "voice", "status", "profile")

    /**
     * مجلد فرعي داخل شجرة وسائط واتساب أعمال.
     * المستندات فقط تُفتح من هذه الشجرة. الصور يصلها MediaStore، والفيديو والصوت والملصقات تُترك.
     */
    fun shouldVisitBusinessDirectory(name: String): Boolean =
        name.lowercase(Locale.ROOT).contains("document")

    /**
     * تحديد مصدر الملف من مساره النسبي في MediaStore (بدون أي استعلام إضافي).
     * تُطبَّق على الصور وملفات PDF معًا.
     *
     * واتساب الأعمال يشمل المسارات الشائعة كلها — يُفحص **أولًا** حتى لا
     * يسقط مسار قديم مثل "WhatsApp Business/..." في فرع واتساب العادي:
     *   - `Android/media/com.whatsapp.w4b/WhatsApp Business/Media/...` (الجيل الجديد)
     *   - `WhatsApp Business/Media/WhatsApp Business Images/...` (الجذر القديم)
     * المطابقة غير حساسة لحالة الأحرف (بعض الأجهزة تعيد المسار بأحرف مختلفة).
     */
    fun sourceFromRelativePath(path: String): SourceApp? {
        val p = path.lowercase(Locale.ROOT)
        return when {
            p.contains("com.whatsapp.w4b") || p.contains("whatsapp business") -> SourceApp.WHATSAPP_BUSINESS
            p.startsWith("whatsapp") || p.contains("/com.whatsapp/") -> SourceApp.WHATSAPP
            p.startsWith("dcim") -> SourceApp.GALLERY
            p.startsWith("pictures") -> SourceApp.GALLERY
            p.startsWith("download") -> SourceApp.DOWNLOADS
            else -> null
        }
    }

    /** هوية ملف MediaStore لمنع التكرار: المسار النسبي + الاسم بشرطة واحدة دائمًا. */
    fun originalPathOf(relativePath: String, displayName: String): String =
        relativePath.trimEnd('/') + "/" + displayName

    /**
     * هوية الصفحة برقمها داخل الملف (1 للأولى). صفحة فشل رسمها لا تأخذ رقم غيرها.
     * اكتمال الملف علامة منفصلة يكتبها الفحص، لا تُستنتج من صفحة واحدة.
     */
    fun pageIdentity(base: String, pageNumber: Int): String {
        val n = if (pageNumber < 1) 1 else pageNumber
        return "$base$PAGE_MARKER$n"
    }

    /** الصفحة الأولى باسم الملف، ومن الثانية `(صN)` برقم الصفحة الحقيقي. */
    fun pageDisplayName(name: String, pageNumber: Int): String =
        if (pageNumber <= 1) name else "$name (ص$pageNumber)"

    /** رقم الصفحة من الهوية أو من `(صN)` في الاسم الأصلي. الغائب = الصفحة الأولى. */
    fun pageNumberOf(originalPath: String?, originalName: String?): Int {
        val path = originalPath.orEmpty()
        val marker = path.lastIndexOf(PAGE_MARKER)
        if (marker >= 0) {
            path.substring(marker + PAGE_MARKER.length).toIntOrNull()?.takeIf { it >= 1 }?.let { return it }
        }
        val shown = Regex("\\(ص(\\d+)\\)\\s*$").find(originalName.orEmpty())
        return shown?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 } ?: 1
    }

    /**
     * ما يُفعل بملف داخل مجلد الإدخال.
     * [LEAVE] تنزيل ناقص أو ملف فارغ: يُترك حتى يكتمل، ولا يُعلَّم معالَجًا.
     * [IGNORE] ليس صورة ولا PDF.
     */
    enum class InboxFileKind { IMAGE, PDF, LEAVE, IGNORE }

    private val INCOMPLETE_EXTENSIONS = setOf(
        "crdownload", "part", "partial", "tmp", "temp", "download",
    )

    fun inboxFileKind(name: String, sizeBytes: Long): InboxFileKind {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(".")) return InboxFileKind.IGNORE
        if (FileUtils.extensionOf(trimmed) in INCOMPLETE_EXTENSIONS) return InboxFileKind.LEAVE
        if (sizeBytes == 0L) return InboxFileKind.LEAVE
        if (FileUtils.isImage(trimmed)) return InboxFileKind.IMAGE
        if (FileUtils.isPdf(trimmed)) return InboxFileKind.PDF
        return InboxFileKind.IGNORE
    }

    /**
     * ملف مجلد الإدخال أقدم من بداية اليوم المحلي. يُترك في المجلد ولا يُؤرشف.
     * التاريخ المجهول (0) لا يُحسب قديمًا حتى لا يُسقط مزوّد لا يُبلغ عن الوقت.
     */
    fun isBeforeToday(lastModifiedMillis: Long, startOfTodayMillis: Long): Boolean =
        lastModifiedMillis > 0L && lastModifiedMillis < startOfTodayMillis

    /**
     * يجهّز بوابة المسارات السريعة من المسارات المخزَّنة:
     * - سجلات قديمة كُتبت بشرطة مزدوجة "Download//name" تُطبَّع (مسار content:// لا يُمسّ).
     * - أي صفحة `ملف#page=N` تُضيف هوية الملف المجردة لهذه المجموعة. فحص مجلد الإدخال
     *   لا يعتبر ذلك اكتمالًا: الاكتمال صف في processed_sources يُكتب بعد السير،
     *   حتى يُستأنف ملف انقطع قبل أن تُخزَّن كل صفحاته.
     */
    fun normalizeExistingPaths(paths: Collection<String>): MutableSet<String> {
        val result = HashSet<String>(paths.size * 2)
        for (raw in paths) {
            result.add(raw)
            val normalized = if (!raw.startsWith("content:") && raw.contains("//")) {
                raw.replace(REPEATED_SLASHES, "/")
            } else raw
            result.add(normalized)
            val marker = normalized.indexOf(PAGE_MARKER)
            if (marker > 0) result.add(normalized.substring(0, marker))
        }
        return result
    }

    /**
     * مفتاح مقارنة ملف MediaStore بنفس الملف حين يظهر في شجرة SAF (التخزين الأولي):
     * معرّف المستند في مزوّد التخزين الخارجي هو `primary:المسار/الاسم`.
     */
    fun safDocumentKey(relativePath: String, displayName: String): String =
        ("primary:" + originalPathOf(relativePath, displayName)).lowercase(Locale.ROOT)

    /**
     * يستخرج المفتاح نفسه من URI مستند SAF. الشكل الفعلي تحت شجرة مختارة هو
     * `content://…/tree/<شجرة>/document/<معرّف مرمَّز>` — المعرّف هو ما بعد آخر `/document/`.
     * @return null لو لم يكن URI مستند.
     */
    fun safDocumentKeyOf(documentUri: String): String? {
        val at = documentUri.lastIndexOf(SAF_DOCUMENT_SEGMENT)
        if (at < 0) return null
        val encoded = documentUri.substring(at + SAF_DOCUMENT_SEGMENT.length)
            .substringBefore('#').substringBefore('?')
        if (encoded.isEmpty()) return null
        return try {
            // URLDecoder يحوّل "+" إلى مسافة بخلاف ترميز URI — نحميها قبل الفك
            URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8").lowercase(Locale.ROOT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** تاريخ الأرشفة: DATE_TAKEN (مللي ثانية) ثم DATE_MODIFIED (ثوانٍ)؛ 0 = غير معروف. */
    fun resolveOriginalDate(dateTakenMs: Long, dateModifiedSec: Long): Long =
        if (dateTakenMs > 0) dateTakenMs else dateModifiedSec.coerceAtLeast(0L) * 1000

    /**
     * اللحظة التي يبدأ منها الفحص.
     *
     * الأصل "اليوم بيومه" (بداية اليوم) — وأول فحص لا يسحب المكتبة القديمة أبدًا.
     * لكن ما يصل **بعد آخر فحص ناجح وقبل منتصف الليل** كان يضيع نهائيًا (فحص الغد
     * يبدأ من منتصف ليلته)، فيُمدّ البدء إلى لحظة آخر فحص ناجح بسقف [MAX_LOOKBACK_MS].
     */
    fun scanWindowStart(todayStart: Long, lastScanAt: Long): Long {
        if (lastScanAt <= 0L || lastScanAt >= todayStart) return todayStart
        return maxOf(lastScanAt, todayStart - MAX_LOOKBACK_MS)
    }

    /**
     * اسم ملف الكاش المؤقت: بادئة + طابع + الامتداد فقط. لا يُدرج الاسم الأصلي —
     * اسم عربي طويل (كل حرف بايتان) مع البادئة يتجاوز حد 255 بايت فيفشل الإنشاء
     * ويُتخطى الملف بصمت في كل فحص.
     */
    fun tempFileName(prefix: String, displayName: String, stamp: Long): String {
        val ext = FileUtils.extensionOf(displayName)
            .filter { it.isLetterOrDigit() }.take(10).ifBlank { "jpg" }
        return "${prefix}_$stamp.$ext"
    }

    /** هل يُستورد ملف غير صورة وغير PDF خامًا من مجلد SAF؟ */
    fun isImportableRawDocument(name: String): Boolean {
        if (name.isBlank() || name.startsWith(".")) return false
        return FileUtils.extensionOf(name) !in RAW_BLOCKED_EXTENSIONS
    }
}
