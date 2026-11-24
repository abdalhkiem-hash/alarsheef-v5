package com.alarsheef.archive.util

import com.alarsheef.archive.ai.ContentVerdict
import com.alarsheef.archive.ai.DocumentCategory
import com.alarsheef.archive.ai.DocumentContentGate

/**
 * قواعد تسمية الملفات المؤرشفة.
 *
 * اسم مؤقت يحمل نوع المستند عند الاستيراد: `فاتورة_20260925_48213.jpg`
 * ثم، إن حلّل Gemini المستند وأعاد جهة/مبلغ: `فاتورة_شركة-الأمل_2026-09-25_345.00ر.س.jpg`.
 *
 * لا يُعاد تسمية أي ملف إلا لو كان اسمه "تلقائيًا" ([isAutoName]) وغير مقفول
 * بتسمية المستخدم — فلا يُمحى اسم اختاره المستخدم بنفسه أبدًا.
 */
object ArchiveNaming {

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}\\u061F\\u060C\\u061B]")

    /** محارف اتجاه وعرض صفري ينقلها OCR من النص (U+200F…): لا تُرى لكنها تفسد الاسم والبحث. */
    private val INVISIBLE = Regex("[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")
    private val SPACES = Regex("\\s+")
    private val UNDERSCORES = Regex("_{2,}")

    /**
     * بادئات الأسماء التلقائية. الأطول أولاً حتى لا تُؤخذ «إشعار» من «إشعار-دائن».
     * «حركة_صندوق» تبقى للأسماء المؤقتة القديمة.
     */
    private val TYPE_LABELS = listOf(
        "إشعار-دائن", "إشعار-مدين", "حركة-صندوق", "حركة_صندوق",
        "فاتورة", "حوالة", "إيصال", "عقد", "هوية", "إشعار", "مستند",
    )
    private val AUTO_NAME = Regex(
        "^(?:IMG|" + TYPE_LABELS.joinToString("|") + ")_\\d{8}_\\d{1,5}(?:_\\d+)?$"
    )
    private val DATE_YMD = Regex("(?<!\\d)(\\d{4})[/\\-.](\\d{1,2})[/\\-.](\\d{1,2})(?!\\d)")
    private val DATE_DMY = Regex("(?<!\\d)(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})(?!\\d)")

    /**
     * جزء واحد من الاسم (الجهة أو النوع): المسافات والشرطات السفلية تصبح «-»
     * حتى يبقى «_» فاصلاً بين الحقول.
     */
    fun part(raw: String, maxLen: Int = 40): String {
        val cleaned = ILLEGAL.replace(INVISIBLE.replace(raw, "").replace('_', ' '), " ")
        val hyphened = SPACES.replace(cleaned.trim(), "-")
        val collapsed = Regex("-{2,}").replace(hyphened, "-")
        return collapsed.trim('-', '.', ' ').take(maxLen).trim('-', '.', ' ')
    }

    /** ينظّف نصًا ليصلح اسم ملف: بلا محارف ممنوعة، المسافات → "_"، وطول محدود. */
    fun sanitize(raw: String, maxLen: Int = 80): String {
        // استبدال لا حذف: "الأمل/للتجارة" ← "الأمل للتجارة" لا "الأملللتجارة"
        val cleaned = ILLEGAL.replace(INVISIBLE.replace(raw, ""), " ")
        val joined = SPACES.replace(cleaned.trim(), "_")
        val collapsed = UNDERSCORES.replace(joined, "_")
        return collapsed.trim('_', '.', ' ').take(maxLen).trim('_', '.', ' ')
    }

    /** هل الاسم (بلا امتداد) من توليد التطبيق نفسه؟ */
    fun isAutoName(fileName: String): Boolean =
        AUTO_NAME.matches(fileName.substringBeforeLast('.'))

    private val SMART_DATE = Regex("_\\d{4}-\\d{2}-\\d{2}(?:_|$)")

    /**
     * اسم ذكي بناه التطبيق (`النوع_..._yyyy-MM-dd_...`). يجوز إعادة بنائه حين تصل بيانات أدق
     * (رد Gemini أو قارئ أصلح)؛ الاسم الذي كتبه المستخدم لا يحمل هذا الشكل ويُقفل عادةً.
     */
    fun isSmartName(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        return !isAutoName(fileName) && labelOf(fileName) != null && SMART_DATE.containsMatchIn(base)
    }

    /** اسم مؤقت بالنوع نفسه: يجعل [plannedBase] يبني الاسم من جديد كأن الملف لم يُسمَّ بعد. */
    private fun autoPlaceholder(fileName: String): String {
        val label = labelOf(fileName) ?: "مستند"
        return label + "_00000000_0." + fileName.substringAfterLast('.', "jpg")
    }

    /** نفس الاسم، أو هو مع لاحقة تمييز `_N` أضافها منع التكرار: لا إعادة تسمية (وإلا تتأرجح بين _1 و_2). */
    private fun sameBase(fileName: String, planned: String): Boolean {
        val current = fileName.substringBeforeLast('.')
        // لاحقة منع التكرار قصيرة (_1.._999)؛ «_777700239» رقم قديم في الاسم لا لاحقة
        return current == planned || Regex(Regex.escape(planned) + "_\\d{1,3}").matches(current)
    }

    /** بادئة النوع المحفوظة في اسم تلقائي، أو null لو الاسم IMG_ أو غير تلقائي. */
    fun labelOf(fileName: String): String? {
        val base = fileName.substringBeforeLast('.')
        return TYPE_LABELS.firstOrNull { base.startsWith(it + "_") }
    }

    /** بادئة الاسم المؤقت من حكم بوابة المحتوى. */
    fun typeLabel(category: DocumentCategory, evidence: List<String>): String = when (category) {
        DocumentCategory.INVOICE -> "فاتورة"
        DocumentCategory.RECEIPT -> {
            val transfer = evidence.any {
                it.contains("حواله") || it.contains("تحويل") ||
                    it.contains("transfer") || it.contains("remittance")
            }
            if (transfer) "حوالة" else "إيصال"
        }
        DocumentCategory.CASH_BOX -> "حركة_صندوق"
        DocumentCategory.CONTRACT -> "عقد"
        DocumentCategory.ID_DOCUMENT -> "هوية"
    }

    /**
     * اسم ذكي من بيانات Gemini، أو null إن لم تتوفر جهة ولا مبلغ (لا فائدة من إعادة التسمية).
     * @param fallbackDate تاريخ الملف في الأرشيف بصيغة yyyy-MM-dd حين لا يُعاد تاريخ مفهوم.
     */
    fun smartBase(
        label: String,
        vendor: String?,
        invoiceDate: String?,
        amount: String?,
        currency: String?,
        fallbackDate: String
    ): String? {
        val v = vendor?.let { part(it, 24) }?.takeIf { it.isNotBlank() }
        val a = amount?.let { cleanAmount(it) }?.takeIf { it.isNotBlank() }
        if (v == null && a == null) return null
        val date = normalizeDate(invoiceDate) ?: fallbackDate
        val cur = currency?.let { part(it, 8) }.orEmpty()
        val type = part(label, 32).ifBlank { "مستند" }
        val parts = listOfNotNull(type, v, date, a?.let { it + cur })
        return parts.joinToString("_").takeIf { it.isNotBlank() }
    }

    /** بادئة النوع من نص OCR إن كانت دلائله المستندية كافية (حكم [DocumentContentGate])، وإلا null. */
    fun typeLabelFromText(ocrText: String?): String? {
        if (ocrText.isNullOrBlank()) return null
        val verdict = DocumentContentGate.verify(ocrText) as? ContentVerdict.Accepted ?: return null
        return typeLabel(verdict.primaryCategory, verdict.matchedKeywords)
    }

    /**
     * يستبدل بادئة `IMG` في اسم تلقائي ببادئة النوع مع إبقاء التاريخ والرقم:
     * `IMG_20260925_48213.jpg` + "فاتورة" ← `فاتورة_20260925_48213` (بلا امتداد).
     * @return null لو الاسم ليس تلقائيًا ببادئة IMG. الناتج يبقى اسمًا تلقائيًا، فيقبل
     *         الاسم الذكي لاحقًا.
     */
    fun withTypeLabel(fileName: String, label: String): String? {
        if (!isAutoName(fileName)) return null
        val base = fileName.substringBeforeLast('.')
        if (!base.startsWith("IMG_")) return null
        return label + base.removePrefix("IMG")
    }

    /**
     * الاسم الجديد (بلا امتداد) لملف بعد تحليله، أو null = يبقى اسمه كما هو.
     *
     * 1. اسم ذكي كامل إن أعاد التحليل السحابي جهة و/أو مبلغ ([smartBase]).
     * 2. وإلا بادئة النوع بدل `IMG` إن دلّ نص OCR على نوع المستند — للملفات التي
     *    استُوردت دون أن تقرأ البوابة نصّها (لا OCR لحظتها، استيراد يدوي، قبول من المراجعة).
     *
     * لا يُمسّ اسم مقفول ولا اسم غير تلقائي (كتبه المستخدم أو سُمّي ذكيًا من قبل).
     * بادئة النوع التي أُعطيت عند الاستيراد تُحترم ولا يُعاد استنتاجها.
     */
    fun plannedBase(
        fileName: String,
        nameLocked: Boolean,
        ocrText: String?,
        vendor: String?,
        invoiceDate: String?,
        amount: String?,
        currency: String?,
        fallbackDate: String,
        userTypes: List<DocumentName.UserType> = emptyList(),
        pageNumber: Int = 1,
        refreshSmartName: Boolean = false,
    ): String? {
        if (nameLocked) return null
        val refreshing = refreshSmartName && isSmartName(fileName)
        if (!isAutoName(fileName) && !refreshing) return null
        if (refreshing) {
            val planned = plannedBase(
                autoPlaceholder(fileName), false, ocrText, vendor, invoiceDate, amount, currency,
                fallbackDate, userTypes, pageNumber,
            ) ?: run {
                // لا شيء موثوق يُبنى منه اسم كامل: النوع والتاريخ فقط، بدل إبقاء جهة/رقم خاطئين
                // مثل «حوالة_إلى_..._الي» قرأهما القارئ القديم من عناوين الحقول.
                val read = ocrText?.takeIf { it.isNotBlank() }?.let { DocumentName.read(it, userTypes) }
                val label = read?.type?.takeIf { it != "مستند" } ?: labelOf(fileName) ?: "مستند"
                val date = read?.date ?: normalizeDate(invoiceDate) ?: fallbackDate
                withPage(sanitize(part(label, 32) + "_" + date), pageNumber)
            }
            return planned.takeUnless { sameBase(fileName, it) }
        }
        val currentLabel = labelOf(fileName)
        val fields = ocrText?.takeIf { it.isNotBlank() }?.let { DocumentName.read(it, userTypes) }
            // الورقة لم تكشف نوعها (OCR ضعيف) لكن الملف يحمل نوعًا معروفًا: لا يُخفَّض إلى «مستند»
            ?.let { if (it.type == "مستند" && currentLabel != null && currentLabel != "مستند") it.copy(type = currentLabel) else it }
        if (fields != null && (fields.isRich() || !vendor.isNullOrBlank() || !amount.isNullOrBlank())) {
            DocumentName.compose(
                fields, fallbackDate, pageNumber, vendor, invoiceDate, amount, currency
            )?.let { return it }
        }
        val label = currentLabel ?: fields?.type?.takeIf { it != "مستند" } ?: typeLabelFromText(ocrText)
        smartBase(label ?: "مستند", vendor, invoiceDate, amount, currency, fallbackDate)
            ?.let { return withPage(it, pageNumber) }
        return if (currentLabel == null && label != null) withTypeLabel(fileName, label) else null
    }

    /**
     * بعد إدراج نوع جديد: يُعاد اسم الملفات غير المقفولة التي تحمل كلمته.
     * الاسم المقفول لا يُمس. الملف الذي لا تحتوي ورقته على الكلمة يبقى.
     */
    fun retitleBase(
        fileName: String,
        nameLocked: Boolean,
        ocrText: String?,
        fallbackDate: String,
        userTypes: List<DocumentName.UserType>,
        addedType: String,
        pageNumber: Int = 1,
    ): String? {
        if (nameLocked || ocrText.isNullOrBlank()) return null
        val fields = DocumentName.read(ocrText, userTypes)
        if (fields.type != addedType) return null
        val base = DocumentName.compose(fields, fallbackDate, pageNumber) ?: return null
        return base.takeIf { it != fileName.substringBeforeLast('.') }
    }

    private fun withPage(base: String, pageNumber: Int): String =
        if (pageNumber > 1) sanitize("${base}_ص$pageNumber", 140) else base

    internal fun cleanAmount(raw: String): String {
        val n = DocumentContentGate.normalizeLoose(raw)
        val digits = n.filter { it.isDigit() || it == '.' }.trim('.')
        if (digits.isEmpty()) return ""
        val cut = digits.indexOf('.')
        if (cut < 0) return digits.trimStart('0').ifEmpty { "0" }
        val whole = digits.substring(0, cut).trimStart('0').ifEmpty { "0" }
        val frac = digits.substring(cut + 1).trimEnd('0')
        return if (frac.isEmpty()) whole else "$whole.$frac"
    }

    /** يستخرج تاريخًا مفهومًا (سنة/شهر/يوم أو يوم/شهر/سنة) بصيغة yyyy-MM-dd، أو null. */
    internal fun normalizeDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val n = DocumentContentGate.normalizeLoose(raw)
        DATE_YMD.find(n)?.let { m ->
            val (y, mo, d) = m.destructured
            return format(y.toInt(), mo.toInt(), d.toInt())
        }
        DATE_DMY.find(n)?.let { m ->
            val (d, mo, y) = m.destructured
            return format(y.toInt(), mo.toInt(), d.toInt())
        }
        return null
    }

    private fun format(y: Int, m: Int, d: Int): String? =
        if (m in 1..12 && d in 1..31) String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m, d) else null
}
