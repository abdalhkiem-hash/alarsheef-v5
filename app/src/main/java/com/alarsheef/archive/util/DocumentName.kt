package com.alarsheef.archive.util

import com.alarsheef.archive.ai.DocumentContentGate

/**
 * اسم المستند بعد الأرشفة، من نص الورقة لا من اسم الملف.
 *
 * الترتيب ثابت، والحقل الغائب يُسقط:
 * `النوع_الجهة_التاريخ_المبلغ_رقم-الورقة`
 *
 * رقم الورقة (المستند، الإشعار، السند، الفاتورة، ثم «الرقم») يسبق رقم الحوالة.
 * رقم الحوالة يدخل الاسم فقط إذا لم يوجد رقم ورقة، ويبقى في النص للبحث دائماً.
 * مبلغ الحساب يدخل الاسم، والعمولة لا تدخل. مدين ودائن نوعان مختلفان.
 */
object DocumentName {

    /** نوع يضيفه المستخدم: الاسم كما يريده في أول الملف، وكلمات تظهر على الورقة. */
    data class UserType(val name: String, val keywords: List<String>)

    data class Fields(
        val type: String,
        val party: String?,
        val date: String?,
        val amount: String?,
        val currency: String?,
        /** الرقم الذي يدخل الاسم. */
        val nameReference: String?,
        /** رقم الحوالة إن وُجد، للبحث. قد يطابق [nameReference] إذا كان هو رقم الورقة الوحيد. */
        val transferNumber: String?,
    ) {
        /** جهة أو مبلغ أو رقم: يستحق اسماً ذكياً لا بادئة مؤقتة فقط. */
        fun isRich(): Boolean = party != null || amount != null || nameReference != null
    }

    private val PARTY_LABELS = listOf("عميلنا", "اسم العميل", "العميل", "المستفيد", "المرسل", "الجهة")
    private val ORG_PREFIXES = listOf("شركة", "بنك", "مكتب", "مؤسسة", "المخزن")
    private val PARTY_STOP = setOf(
        "سند", "اشعار", "دائن", "مدين", "فاتوره", "حواله", "حوالات", "تحويل", "ايصال",
        "تاريخ", "مبلغ", "ريال", "حساب", "عموله", "اجمالي", "دولار", "سعودي", "يمني",
        "رقم", "الصافي", "صافي", "الرسوم", "الحاله", "معلقه", "مقبوله", "ضريبيه",
        "ارسال", "استلام", "الجوال", "لمشترك",
        "امر", "صرف", "شراء", "تسويه", "علاجات",
    ).map { DocumentContentGate.normalizeWords(it) }.toSet()

    /** أرقام الورقة بالأولوية. رقم الحوالة ومرجع المصدر آخر الاحتياط. */
    private val PAPER_LABELS = listOf(
        "رقم المستند",
        "رقم الإشعار",
        "رقم السند",
        "رقم الفاتورة",
        "الرقم",
        "رقم الحركة",
        "كود العملية",
        "رقم العملية",
        "رقم المرجع",
        "المرجع",
    )
    private val TRANSFER_LABELS = listOf("رقم الحوالة", "رقم التحويل")
    private val SOURCE_REF_LABELS = listOf("مرجع المصدر")

    /** «إشعار دائن رقم 45» بعد تطبيع الهمزة: اشعار داين رقم 45. */
    private val TITLE_NUMBER = Regex(
        "(?<![\\p{L}\\p{N}])(" +
            listOf("إشعار دائن", "إشعار مدين", "فاتورة", "سند").joinToString("|") {
                Regex.escape(DocumentContentGate.normalizeLoose(it))
            } +
            ")\\s+رقم\\s*[:#\\-]?\\s*([\\p{L}\\p{N}][\\p{L}\\p{N}|\\-]*)"
    )

    private val ACCOUNT_LABELS = listOf("مبلغ الحساب", "المبلغ المحول", "صافي المبلغ", "الصافي", "المبلغ")
    private val COMMISSION_LABELS = listOf("العمولة", "الرسوم", "رسوم الخدمة")
    private val TOTAL_LABELS = listOf("الإجمالي", "المجموع", "اجمالي")

    private val MONEY = Regex("(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)")
    private val VALUE_TOKEN = Regex("([\\p{L}\\p{N}][\\p{L}\\p{N}|\\-]*)")

    fun splitKeywords(raw: String): List<String> =
        raw.split('\n', ',', '،', ';', '؛')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun read(text: String, userTypes: List<UserType> = emptyList()): Fields {
        val loose = DocumentContentGate.normalizeLoose(
            text.replace('\u2212', '-').replace('\u2013', '-'),
            keepLatinCase = true,
        )
        val words = DocumentContentGate.normalizeWords(text)
        val transfer = firstLabeled(loose, TRANSFER_LABELS)
        val paper = paperNumber(loose) ?: transfer ?: firstLabeled(loose, SOURCE_REF_LABELS)
        val money = accountMoney(loose)
        return Fields(
            type = resolveType(words, userTypes),
            party = partyOf(text),
            date = dateOf(loose),
            amount = money?.first,
            currency = money?.second,
            nameReference = paper,
            transferNumber = transfer,
        )
    }

    /**
     * يبني الاسم. حقول السحابة تملأ الفراغ فقط: لا تستبدل مبلغ الحساب ولا رقم الورقة ولا الجهة المقروءة.
     * @return null إذا لم يوجد ما يُحسّن الاسم المؤقت (لا جهة ولا مبلغ ولا رقم، والنوع ما زال «مستند»).
     */
    fun compose(
        fields: Fields,
        fallbackDate: String,
        pageNumber: Int = 1,
        gapVendor: String? = null,
        gapDate: String? = null,
        gapAmount: String? = null,
        gapCurrency: String? = null,
    ): String? {
        if (!fields.isRich() && fields.type == "مستند" && gapVendor.isNullOrBlank() && gapAmount.isNullOrBlank()) {
            return null
        }
        val type = ArchiveNaming.part(fields.type, 32).ifBlank { "مستند" }
        val party = ArchiveNaming.part(fields.party ?: gapVendor.orEmpty(), 40).ifBlank { null }
        val date = fields.date ?: ArchiveNaming.normalizeDate(gapDate) ?: fallbackDate
        val amount = fields.amount ?: gapAmount?.let { ArchiveNaming.cleanAmount(it) }?.ifBlank { null }
        val currency = when {
            fields.amount != null -> fields.currency
            amount != null -> gapCurrency ?: fields.currency
            else -> null
        }?.let { ArchiveNaming.part(it, 8) }?.ifBlank { null }
        val amountPart = amount?.let { it + currency.orEmpty() }
        val reference = fields.nameReference?.let { ArchiveNaming.part(it, 40) }?.ifBlank { null }
        val parts = listOfNotNull(type, party, date, amountPart, reference)
        var base = parts.joinToString("_")
        if (pageNumber > 1) base += "_ص$pageNumber"
        return ArchiveNaming.sanitize(base, maxLen = 140).ifBlank { null }
    }

    private fun resolveType(words: String, userTypes: List<UserType>): String {
        val user = userTypes
            .flatMap { type -> type.keywords.map { keyword -> type to DocumentContentGate.normalizeWords(keyword) } }
            .filter { (_, keyword) -> keyword.isNotBlank() && words.contains(keyword) }
            .maxByOrNull { (_, keyword) -> keyword.length }
            ?.first
        if (user != null) {
            val name = ArchiveNaming.part(user.name, 32)
            if (name.isNotBlank()) return name
        }
        return when {
            containsPhrase(words, "اشعار دائن") -> "إشعار-دائن"
            containsPhrase(words, "اشعار مدين") -> "إشعار-مدين"
            containsPhrase(words, "فاتوره") -> "فاتورة"
            containsPhrase(words, "حواله") || containsPhrase(words, "حوالات") ||
                containsPhrase(words, "تحويل") || containsPhrase(words, "ارسال حواله") -> "حوالة"
            containsPhrase(words, "ايصال") || containsPhrase(words, "سند قبض") || containsPhrase(words, "سند استلام") -> "إيصال"
            containsPhrase(words, "حركه الصندوق") || containsPhrase(words, "حركه صندوق") || containsPhrase(words, "كشف حساب") -> "حركة-صندوق"
            containsPhrase(words, "عقد") -> "عقد"
            containsPhrase(words, "جواز") || containsPhrase(words, "رقم الهويه") -> "هوية"
            containsPhrase(words, "اشعار") -> "إشعار"
            else -> "مستند"
        }
    }

    private fun containsPhrase(words: String, phrase: String): Boolean {
        val n = DocumentContentGate.normalizeWords(phrase)
        if (n.isBlank()) return false
        return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])").containsMatchIn(words)
    }

    private fun paperNumber(loose: String): String? {
        firstLabeled(loose, PAPER_LABELS)?.let { return it }
        val title = TITLE_NUMBER.find(loose) ?: return null
        return title.groupValues[2].ifBlank { null }
    }

    private fun firstLabeled(loose: String, labels: List<String>): String? {
        for (label in labels) {
            val n = DocumentContentGate.normalizeLoose(label)
            val regex = Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])\\s*[:#\\-]?\\s*" + VALUE_TOKEN.pattern
            )
            val value = regex.find(loose)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
            return value
        }
        return null
    }

    private fun accountMoney(loose: String): Pair<String, String?>? {
        labeledMoney(loose, ACCOUNT_LABELS)?.let { return it }
        currencyAdjacent(loose)?.let { return it }
        return labeledMoney(loose, TOTAL_LABELS)
    }

    private fun labeledMoney(loose: String, labels: List<String>): Pair<String, String?>? {
        for (label in labels) {
            val n = DocumentContentGate.normalizeLoose(label)
            val regex = Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])\\s*[:#\\-]?\\s*-?" + MONEY.pattern
            )
            val match = regex.find(loose) ?: continue
            val raw = match.groupValues[1]
            val amount = ArchiveNaming.cleanAmount(raw)
            if (amount.isBlank()) continue
            val end = match.range.last
            val window = loose.substring(end, minOf(loose.length, end + 24))
            return amount to currencyIn(window, loose)
        }
        return null
    }

    /** مبلغ بجانب عملة، ويتخطى سطر العمولة. */
    private fun currencyAdjacent(loose: String): Pair<String, String?>? {
        val regex = Regex("-?" + MONEY.pattern + "\\s*(ريال\\s+سعودي|ريال\\s+يمني|دولار|ريال|ر\\.?\\s*س|ر\\.?\\s*ي)")
        for (match in regex.findAll(loose)) {
            val before = loose.substring(maxOf(0, match.range.first - 24), match.range.first)
            val beforeWords = DocumentContentGate.normalizeWords(before)
            val commission = COMMISSION_LABELS.any { label ->
                beforeWords.contains(DocumentContentGate.normalizeWords(label))
            }
            if (commission) continue
            val amount = ArchiveNaming.cleanAmount(match.groupValues[1])
            if (amount.isBlank()) continue
            return amount to currencyToken(match.groupValues[2])
        }
        return null
    }

    private fun currencyIn(near: String, whole: String): String? {
        currencyToken(near)?.let { return it }
        return currencyToken(whole)
    }

    /** «ر.ي» و«ر.س» لا تُقرآن من داخل كلمة «ريال». */
    private fun abbrev(letter: String) =
        Regex("(?<![\\p{L}\\p{N}])ر\\.?\\s*$letter(?![\\p{L}\\p{N}])")

    private fun currencyToken(text: String): String? {
        val n = DocumentContentGate.normalizeLoose(text)
        return when {
            n.contains("ريال سعودي") || abbrev("س").containsMatchIn(n) || n.contains("sar") -> "ر.س"
            n.contains("ريال يمني") || abbrev("ي").containsMatchIn(n) || n.contains("yer") -> "ر.ي"
            n.contains("دولار") || n.contains("usd") -> "دولار"
            n.contains("ريال") -> "ريال"
            else -> null
        }
    }

    private fun dateOf(loose: String): String? {
        val labeled = Regex("تاريخ\\s*[:#\\-]?\\s*(\\d{1,4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,4})").find(loose)
        ArchiveNaming.normalizeDate(labeled?.groupValues?.get(1))?.let { return it }
        val any = Regex("(?<!\\d)(\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{4})(?!\\d)")
        for (match in any.findAll(loose)) {
            ArchiveNaming.normalizeDate(match.value)?.let { return it }
        }
        return null
    }

    private fun partyOf(original: String): String? {
        val lines = original.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(12)
        for (line in lines) {
            val originalWords = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val normalized = originalWords.map { DocumentContentGate.normalizeWords(it) }
            for (label in PARTY_LABELS) {
                val n = DocumentContentGate.normalizeWords(label)
                val at = normalized.indexOfFirst { it == n }
                if (at < 0) continue
                takeName(originalWords.drop(at + 1), normalized.drop(at + 1))?.let { return it }
            }
        }
        for (line in lines) {
            val originalWords = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (originalWords.isEmpty()) continue
            val head = DocumentContentGate.normalizeWords(originalWords.first())
            val prefix = ORG_PREFIXES.firstOrNull { DocumentContentGate.normalizeWords(it) == head } ?: continue
            val restOriginal = originalWords.drop(1)
            val restNormal = restOriginal.map { DocumentContentGate.normalizeWords(it) }
            val name = takeName(restOriginal, restNormal) ?: continue
            return ArchiveNaming.part("$prefix $name", 40).ifBlank { null }
        }
        for (line in lines) {
            val originalWords = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (originalWords.size !in 1..3) continue
            val normalized = originalWords.map { DocumentContentGate.normalizeWords(it) }
            val stopped = normalized.any { word ->
                word in PARTY_STOP || word.any { ch -> ch.isDigit() } || word.length < 3
            }
            if (stopped) continue
            val foreign = normalized.any { word -> word.any { ch -> ch !in '\u0600'..'\u06FF' } }
            if (foreign) continue
            return ArchiveNaming.part(originalWords.joinToString(" "), 40).ifBlank { null }
        }
        return null
    }

    private fun takeName(original: List<String>, normalized: List<String>): String? {
        val kept = ArrayList<String>()
        for (i in original.indices) {
            val n = normalized[i]
            if (n.isBlank() || n in PARTY_STOP || n.any { it.isDigit() }) break
            if (n.length < 2) break
            kept += original[i].trim(':', '-', '،', ',')
            if (kept.size == 4) break
        }
        val joined = kept.joinToString(" ").trim()
        return joined.ifBlank { null }
    }
}
