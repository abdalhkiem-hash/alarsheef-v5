package com.alarsheef.archive.ai

/**
 * حكم بوابة المحتوى النصي على نص مُستخرج بـ OCR.
 */
sealed class ContentVerdict {
    /** أدلة كافية ومتوافقة على أن الصورة مستند مالي/إداري — تُؤرشف مباشرة. */
    data class Accepted(
        val matchedKeywords: List<String>,
        val primaryCategory: DocumentCategory
    ) : ContentVerdict()

    /** دليل جزئي لا يكفي للقبول ولا للرفض — تُحفظ "معلّقة" للمراجعة اليدوية. */
    data class Ambiguous(val partialMatches: List<String>) : ContentVerdict()

    /** نص مقروء لكن بلا أدلة مستندية — لا تُؤرشف. */
    data class Rejected(val reason: String) : ContentVerdict()
}

/** فئات المستندات المستهدفة (فواتير، حوالات/إيصالات، حركة صندوق، وثائق). */
enum class DocumentCategory {
    INVOICE,      // فاتورة
    RECEIPT,      // إيصال / حوالة / سند
    CASH_BOX,     // حركة صندوق / كشف حساب
    CONTRACT,     // عقد
    ID_DOCUMENT   // هوية / جواز
}

/**
 * بوابة المحتوى النصي: تقرر هل النص المُستخرج من الصورة يخص مستندًا مستهدفًا.
 *
 * المنهج: "اجتماع أدلة" لا كلمة واحدة ولا طول نص:
 *  - مراسي (anchors): كلمات تحدد نوع المستند (فاتورة، حوالة، إيصال، حركة الصندوق...).
 *  - داعمات (support): كلمات مالية عامة (المبلغ، الإجمالي، المستفيد، رصيد...).
 *  - أدلة رقمية: مبلغ (مع عملة أو كسر عشري)، تاريخ كامل، رقم مرجعي طويل.
 * القبول يتطلب مرساة + دليلين آخرين (داعم أو رقمي)، أو مرساتين + دليلًا رقميًا،
 * أو ثلاث داعمات + دليلين رقميين. أقل من ذلك مع وجود إشارة ما = غامض.
 *
 * التطبيع يُطبَّق على النص وعلى الكلمات المفتاحية بنفس الدالة تمامًا
 * (وإلا لن تتطابق "فاتورة" مع "فاتوره" بعد توحيد التاء المربوطة).
 */
object DocumentContentGate {

    // ---------- أدوات التطبيع (تُعرَّف أولًا لأن القوائم أدناه تستخدمها وقت التهيئة) ----------

    private val WHITESPACE = Regex("\\s+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s]")

    /**
     * تطبيع "مرِن": يحتفظ بعلامات الترقيم (لازمة لكشف المبالغ والتواريخ).
     * يزيل التشكيل والتطويل، يوحّد الألف والياء والتاء المربوطة، ويحوّل الأرقام
     * الهندية/الفارسية إلى لاتينية.
     */
    internal fun normalizeLoose(text: String, keepLatinCase: Boolean = false): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in '\u064B'..'\u065F' || ch == '\u0640' || ch == '\u0670' -> Unit
                ch in '\u0660'..'\u0669' -> sb.append('0' + (ch - '\u0660'))
                ch in '\u06F0'..'\u06F9' -> sb.append('0' + (ch - '\u06F0'))
                ch == '\u066B' -> sb.append('.')
                ch == '\u066C' -> sb.append(',')
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> sb.append('ا')
                ch == 'ى' -> sb.append('ي')
                ch == 'ة' -> sb.append('ه')
                ch == 'ؤ' -> sb.append('و')
                ch == 'ئ' -> sb.append('ي')
                keepLatinCase && ch in 'A'..'Z' -> sb.append(ch)
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return WHITESPACE.replace(sb, " ").trim()
    }

    /** تطبيع "كلمات": مرِن + استبدال الرموز بمسافات (للمطابقة على حدود الكلمات). */
    internal fun normalizeWords(text: String): String =
        WHITESPACE.replace(NON_WORD.replace(normalizeLoose(text), " "), " ").trim()

    // ---------- المصطلحات ----------

    private class Term(val text: String, val regex: Regex, val category: DocumentCategory?)

    /**
     * يبني مطابِقًا بحدود كلمات. للعربية يسمح بسوابق شائعة (و/ب/ل/ك/ف/ال/وال/بال...)
     * فتتطابق "الفاتورة" و"بالفاتورة" و"للمبلغ" مع الكلمة المجرّدة.
     * الكلمات القصيرة اللاتينية (tax, ref...) لا تتطابق داخل كلمات أطول.
     */
    private fun term(raw: String, category: DocumentCategory? = null): Term {
        val n = normalizeWords(raw)
        val arabic = n.firstOrNull()?.let { it in '\u0600'..'\u06FF' } == true
        val prefix = if (arabic) "(?:وال|بال|كال|فال|لل|ال|و|ب|ل|ك|ف)?" else ""
        val pattern = "(?<![\\p{L}\\p{N}])" + prefix + Regex.escape(n) + "(?![\\p{L}\\p{N}])"
        return Term(n, Regex(pattern), category)
    }

    /** مراسي: تحدد نوع المستند. (بدون "ال" التعريف — تُعالَج بالسوابق.) */
    private val ANCHORS: List<Term> = listOf(
        DocumentCategory.INVOICE to listOf(
            "فاتورة", "فاتورة ضريبية", "فاتورة مبيعات", "فاتورة مشتريات",
            "invoice", "tax invoice"
        ),
        DocumentCategory.RECEIPT to listOf(
            "إيصال", "سند قبض", "سند صرف", "سند استلام", "حوالة", "تحويل", "تحويل بنكي",
            "إشعار دائن", "إشعار مدين", "receipt", "transfer", "remittance"
        ),
        DocumentCategory.CASH_BOX to listOf(
            "حركة الصندوق", "حركة صندوق", "صندوق", "كشف حساب", "كشف الحساب",
            "مقبوضات", "مدفوعات", "account statement", "statement of account"
        ),
        DocumentCategory.CONTRACT to listOf(
            "عقد إيجار", "عقد بيع", "عقد عمل", "طرف أول", "طرف ثاني", "contract"
        ),
        DocumentCategory.ID_DOCUMENT to listOf(
            "رقم الهوية", "رقم الإقامة", "جواز سفر", "passport", "national id"
        )
    ).flatMap { (category, words) -> words.map { term(it, category) } }

    /** داعمات: كلمات مالية عامة لا تكفي وحدها. */
    private val SUPPORT: List<Term> = listOf(
        "مبلغ", "إجمالي", "إجمالي كلي", "مجموع", "total", "subtotal", "amount", "amount due",
        "ضريبة", "ضريبة القيمة المضافة", "vat", "tax", "رقم ضريبي",
        "رقم الفاتورة", "رقم العملية", "رقم المرجع", "مرجع", "reference",
        "مستفيد", "beneficiary", "مرسل", "sender", "مستلم", "receiver",
        "رصيد", "وارد", "منصرف", "مدين", "دائن", "debit", "credit", "balance",
        "كمية", "qty", "quantity", "سعر الوحدة", "unit price",
        "عميل", "customer", "مورد", "supplier", "مدفوع", "متبقي", "remaining",
        "توقيع", "signature", "جنسية", "nationality"
    ).map { term(it) }

    /** عبارات تُحذف قبل المطابقة لأنها تخدع كلمات مثل "صندوق" و"وارد" (واجهات البريد). */
    private val NEGATIVES: List<String> = listOf(
        "صندوق الوارد", "صندوق البريد", "صندوق الصادر", "صندوق الرسائل"
    ).map { normalizeWords(it) }

    // ---------- أدلة رقمية ----------

    private val CURRENCY = Regex(
        "(?<![\\p{L}\\p{N}])(?:ريال|ر\\.? ?س|ر\\.? ?ي|sar|sr|yer|usd|aed|egp|kwd|qar|eur|دولار|درهم|جنيه|دينار)(?![\\p{L}\\p{N}])" +
            "|[\\u0024\\u20AC\\u00A3]"
    )
    private val TWO_DIGITS = Regex("\\d{2,}")
    private val DECIMAL_MONEY = Regex("(?<![\\d.,])\\d{1,9}(?:,\\d{3})*\\.\\d{2}(?!\\d|\\.\\d)")
    private val DATE = Regex(
        "(?<!\\d)(?:\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4})(?!\\d)"
    )
    private val LONG_NUMBER = Regex("(?<!\\d)\\d{8,}(?!\\d)")
    private val LABELED_REF = Regex("(?:رقم|no|ref|reference|مرجع|عمليه)\\s*[:#\\-]?\\s*\\d{4,}")

    // ---------- الحكم ----------

    fun verify(ocrText: String): ContentVerdict {
        val loose = normalizeLoose(ocrText)
        if (loose.length < 3) return ContentVerdict.Rejected("نص فارغ")

        var words = normalizeWords(ocrText)
        NEGATIVES.forEach { words = words.replace(it, " ") }
        val tokenCount = words.split(' ').count { it.isNotEmpty() }

        val anchors = dedupe(ANCHORS.filter { it.regex.containsMatchIn(words) })
        val supports = dedupe(SUPPORT.filter { it.regex.containsMatchIn(words) })

        val hasAmount = DECIMAL_MONEY.containsMatchIn(loose) ||
            (CURRENCY.containsMatchIn(loose) && TWO_DIGITS.containsMatchIn(loose))
        val hasDate = DATE.containsMatchIn(loose)
        val hasRef = LONG_NUMBER.containsMatchIn(loose) || LABELED_REF.containsMatchIn(loose)
        val numeric = listOf(hasAmount, hasDate, hasRef).count { it }

        val a = anchors.size
        val s = supports.size

        val evidence = buildList {
            anchors.forEach { add(it.text) }
            supports.forEach { add(it.text) }
            if (hasAmount) add("#مبلغ")
            if (hasDate) add("#تاريخ")
            if (hasRef) add("#مرجع")
        }

        val accepted = (a >= 1 && s + numeric >= 2) ||
            (a >= 2 && numeric >= 1) ||
            (s >= 3 && numeric >= 2)
        if (accepted) {
            val category = anchors.mapNotNull { it.category }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key
                ?: DocumentCategory.CASH_BOX
            return ContentVerdict.Accepted(evidence, category)
        }

        val ambiguous = a >= 1 || s >= 2 || (numeric >= 2 && tokenCount >= 8)
        return if (ambiguous) {
            ContentVerdict.Ambiguous(evidence)
        } else {
            ContentVerdict.Rejected("لا دلائل مالية/مستندية كافية")
        }
    }

    /** يحذف الكلمة المتداخلة داخل أطول منها ("فاتورة" داخل "فاتورة ضريبية") حتى لا تُعدّ مرتين. */
    private fun dedupe(hits: List<Term>): List<Term> =
        hits.filter { h -> hits.none { g -> g !== h && g.text.length > h.text.length && g.text.contains(h.text) } }
}
