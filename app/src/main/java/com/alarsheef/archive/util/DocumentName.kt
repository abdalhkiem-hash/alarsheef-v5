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
        /** الجهة من دليل صريح (عنوان أو بادئة جهة)؛ false = سطر قصير فقط، فتتقدّم عليه جهة السحابة. */
        val partyStrong: Boolean = true,
    ) {
        /** جهة أو مبلغ أو رقم: يستحق اسماً ذكياً لا بادئة مؤقتة فقط. */
        fun isRich(): Boolean = party != null || amount != null || nameReference != null
    }

    private val PARTY_LABELS = listOf("عميلنا", "اسم العميل", "العميل", "المستفيد", "المرسل", "الجهة")
    private val ORG_PREFIXES = listOf("شركة", "بنك", "مكتب", "مؤسسة", "المخزن", "محلات", "محل", "معرض", "مصنع")
    private val PARTY_STOP = setOf(
        "سند", "اشعار", "دائن", "مدين", "فاتوره", "حواله", "حوالات", "تحويل", "ايصال",
        "تاريخ", "مبلغ", "ريال", "حساب", "عموله", "اجمالي", "دولار", "سعودي", "يمني",
        "رقم", "الصافي", "صافي", "الرسوم", "الحاله", "معلقه", "مقبوله", "ضريبيه",
        "ارسال", "استلام", "الجوال", "لمشترك",
        "امر", "صرف", "شراء", "تسويه", "علاجات",
        // عناوين حقول تظهر وحدها في سطر حين يفصل OCR العمود عن قيمه (لقطات جوالي/المحافظ):
        // كانت تصير «الجهة» فيخرج اسم مثل «حوالة_إلى_...» أو «مستند_مرجع-المصدر_...».
        "الى", "من", "مرجع", "المصدر", "المصدريه", "المنظمه", "منظمه", "الوجهه", "وقت", "العمليه",
        "كود", "المستلم", "الاسم", "لاسم", "الاول", "الاب", "الجد", "الرباعي", "لجوال", "جوال",
        "محفظه", "المبلغ", "لمبلغ", "لعموله", "لحاله", "البيان", "ملاحظات", "الفرع", "الموظف",
        "الريال", "السعودي", "اليمني", "الدولار", "التاريخ", "الحساب", "تلفون", "هاتف",
        "المرسل", "المستفيد", "العميل", "عميلنا", "الجهه",
        "المحترم", "المحترمون", "المحترمين", "الاخ", "الاخوه", "السيد", "الساده",
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
        // التطبيع نفسه لكن سطرًا سطرًا: الرقم يُطلب في سطر عنوانه أو الذي يليه فقط
        val lined = text.lines().joinToString("\n") { line ->
            DocumentContentGate.normalizeLoose(
                line.replace('−', '-').replace('–', '-'),
                keepLatinCase = true,
            )
        }
        val transfer = firstLabeled(lined, TRANSFER_LABELS)
        val paper = paperNumber(lined) ?: transfer ?: firstLabeled(lined, SOURCE_REF_LABELS)
        val money = accountMoney(loose)
        val strong = strongPartyOf(text)
        return Fields(
            type = resolveType(words, userTypes),
            party = strong ?: weakPartyOf(text),
            partyStrong = strong != null,
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
        // الجهة: دليل الورقة الصريح أولًا، ثم جهة Gemini، ثم السطر القصير
        val cloudVendor = cleanVendor(gapVendor)
        val chosenParty = when {
            fields.party != null && fields.partyStrong -> fields.party
            cloudVendor != null -> cloudVendor
            else -> fields.party
        }
        val party = ArchiveNaming.part(chosenParty.orEmpty(), 40).ifBlank { null }
        // تاريخ الورقة يُقبل فقط إن كان معقولًا بالنسبة ليوم الأرشفة: السنة بخط اليد تُقرأ خطأ
        // كثيرًا (2021/2022/2024 لسندات وصلت في 2026). غير المعقول يُستبدل بيوم الأرشفة.
        val date = listOfNotNull(fields.date, ArchiveNaming.normalizeDate(gapDate?.let(::compactDate)))
            .firstOrNull { plausibleDate(it, fallbackDate) } ?: fallbackDate
        val amount = fields.amount ?: gapAmount?.let { ArchiveNaming.cleanAmount(it) }?.ifBlank { null }
        val currency = when {
            fields.amount != null -> fields.currency
            // عملة السحابة بالصيغة نفسها لعملة الورقة («ريال يمني» ← ر.ي)
            amount != null -> gapCurrency?.let { currencyToken(it) ?: it } ?: fields.currency
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

    /**
     * قيمة الرقم بعد عنوانه: أول رمز فيه رقم، في بقية سطر العنوان ثم السطر التالي فقط.
     * كان يأخذ أي كلمة تلي العنوان ولو بعد سطور: «كود العملية ⏎ مرجع المصدر» أعطى «مرجع»،
     * و«رقم الإشعار ⏎ 2026/11/22 612306» أعطى «2026» من التاريخ. التاريخ والوقت يُتخطيان.
     */
    private fun firstLabeled(loose: String, labels: List<String>): String? {
        for (label in labels) {
            val n = DocumentContentGate.normalizeLoose(label)
            val regex = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])")
            for (match in regex.findAll(loose)) {
                valueAfter(loose, match.range.last + 1)?.let { return it }
            }
        }
        return null
    }

    private val DATE_OR_TIME = Regex(
        "\\d{1,4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,4}|\\d{1,2}:\\d{2}(?::\\d{2})?"
    )

    /** رقم جوال يمني (77/73/71/70/78 + 7 أرقام): يظهر قرب العناوين في الإشعارات وليس رقم الورقة. */
    private val MOBILE = Regex("7[01378][0-9]{7}")

    private fun valueAfter(loose: String, start: Int): String? {
        val rest = loose.substring(start)
        val lines = rest.split('\n')
        // بقية سطر العنوان + أول سطر غير فارغ بعده
        val window = buildList {
            add(lines.first())
            lines.drop(1).firstOrNull { it.isNotBlank() }?.let { add(it) }
        }
        for (line in window) {
            val clean = DATE_OR_TIME.replace(line, " ")
            for (token in VALUE_TOKEN.findAll(clean)) {
                val value = token.groupValues[1].trim('-', '|')
                if (MOBILE.matches(value)) continue
                if (value.any { it.isDigit() }) return value
            }
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
            // بين العنوان ورقمه قد يقع عنوان آخر بلا أرقام («مبلغ الحساب ⏎ عملة الحساب ⏎ 61,764»)
            val regex = Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(n) + "(?![\\p{L}\\p{N}])[^\\d]{0,30}?-?" + MONEY.pattern +
                    "(?![/\\-]\\d)"
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

    private const val CURRENCY_WORDS = "ريال\\s+سعودي|ريال\\s+يمني|دولار|ريال|سعودي|يمني|ر\\.?\\s*س|ر\\.?\\s*ي"

    /**
     * مبلغ بجانب عملة، ويتخطى سطر العمولة ورقم الورقة. الرقم قبل العملة أولًا («61,764 سعودي»)،
     * ثم العملة قبل الرقم («ريال ⏎ ١٥,٠٠٠» في رأس السند).
     */
    private fun currencyAdjacent(loose: String): Pair<String, String?>? {
        val numberFirst = Regex("-?" + MONEY.pattern + "\\s*(" + CURRENCY_WORDS + ")(?![\\p{L}])")
        val currencyFirst = Regex("(?<![\\p{L}])(" + CURRENCY_WORDS + ")\\s*-?" + MONEY.pattern + "(?![/\\-]\\d)")
        for ((regex, moneyGroup, currencyGroup) in listOf(
            Triple(numberFirst, 1, 2),
            Triple(currencyFirst, 2, 1),
        )) {
            for (match in regex.findAll(loose)) {
                val numberStart = match.groups[moneyGroup]?.range?.first ?: continue
                val before = loose.substring(maxOf(0, numberStart - 24), numberStart)
                val beforeWords = DocumentContentGate.normalizeWords(before)
                val commission = COMMISSION_LABELS.any { label ->
                    beforeWords.contains(DocumentContentGate.normalizeWords(label))
                }
                if (commission) continue
                // «الرقم: 46230 ⏎ ريال»: رقم السند يجاور كلمة العملة لكنه ليس مبلغًا
                if (REFERENCE_BEFORE.containsMatchIn(loose.substring(maxOf(0, numberStart - 12), numberStart))) continue
                // جوال أو هاتف أرضي (يبدأ بصفر) بجوار كلمة العملة في الترويسة ليس مبلغًا
                val raw = match.groupValues[moneyGroup]
                if (!raw.contains(',') && (MOBILE.matches(raw) || (raw.startsWith("0") && raw.length >= 6))) continue
                val amount = ArchiveNaming.cleanAmount(raw)
                if (amount.isBlank()) continue
                return amount to currencyToken(match.groupValues[currencyGroup])
            }
        }
        return null
    }

    private val REFERENCE_BEFORE = Regex("(?<![\\p{L}])(?:ال)?رقم[^\\p{L}\\d]*$")

    private fun currencyIn(near: String, whole: String): String? {
        currencyToken(near)?.let { return it }
        return currencyToken(whole)
    }

    /** «ر.ي» و«ر.س» لا تُقرآن من داخل كلمة «ريال». */
    private fun abbrev(letter: String) =
        Regex("(?<![\\p{L}\\p{N}])ر\\.?\\s*$letter(?![\\p{L}\\p{N}])")

    /** «سعودي» أو «يمني» وحدها بعد المبلغ (عمود «عملة الحساب» في إشعارات الصرافة). */
    private val BARE_SAR = Regex("(?<![\\p{L}])سعودي(?![\\p{L}])")
    private val BARE_YER = Regex("(?<![\\p{L}])يمني(?![\\p{L}])")

    private fun currencyToken(text: String): String? {
        val n = DocumentContentGate.normalizeLoose(text)
        return when {
            n.contains("ريال سعودي") || abbrev("س").containsMatchIn(n) || n.contains("sar") ||
                BARE_SAR.containsMatchIn(n) -> "ر.س"
            n.contains("ريال يمني") || abbrev("ي").containsMatchIn(n) || n.contains("yer") ||
                BARE_YER.containsMatchIn(n) -> "ر.ي"
            n.contains("دولار") || n.contains("usd") -> "دولار"
            n.contains("ريال") -> "ريال"
            else -> null
        }
    }

    /**
     * جهة Gemini مختصرة: النموذج الخفيف يحشو أحيانًا نص الترويسة كله في الحقل.
     * يُؤخذ ما قبل أول فاصل («/» أو «|» أو سطر جديد)، وبحد أقصى 5 كلمات.
     */
    private fun cleanVendor(raw: String?): String? {
        fun arabic(word: String) = word.any { it in '؀'..'ۿ' }
        val segments = raw?.split('/', '|', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        // الاسم العربي أولًا إن ذُكر الاسمان («Super Feed / سوبر فيد لصناعة الأعلاف»)
        val segment = segments.firstOrNull { s -> s.split(Regex("\\s+")).any(::arabic) } ?: segments.firstOrNull()
        val words = segment.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val picked = if (words.any(::arabic)) {
            words.dropWhile { !arabic(it) }.takeWhile(::arabic)
        } else {
            words
        }
        return picked.take(5).joinToString(" ").ifBlank { null }
    }

    /** أقصى عمر لورقة تُؤرشف متأخرة؛ ما هو أقدم يُعدّ سنة مقروءة خطأ. */
    private const val MAX_PAPER_AGE_DAYS = 400L

    internal fun plausibleDate(date: String, archiveDate: String): Boolean = runCatching {
        val d = java.time.LocalDate.parse(date)
        val archive = java.time.LocalDate.parse(archiveDate)
        !d.isAfter(archive.plusDays(1)) && !d.isBefore(archive.minusDays(MAX_PAPER_AGE_DAYS))
    }.getOrDefault(true)

    /** «١٠ / ١ / ٢٠٢١» ← «10/1/2021»: مسافات حول الفواصل كانت تُسقط التاريخ كله. */
    private fun compactDate(text: String): String =
        Regex("(\\d)\\s*([/\\-.])\\s*(?=\\d)").replace(DocumentContentGate.normalizeLoose(text), "$1$2")

    private fun dateOf(rawLoose: String): String? {
        val loose = compactDate(rawLoose)
        val labeled = Regex("تاريخ\\s*[:#\\-]?\\s*(\\d{1,4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,4})").find(loose)
        ArchiveNaming.normalizeDate(labeled?.groupValues?.get(1))?.let { return it }
        val any = Regex("(?<!\\d)(\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}|\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{4})(?!\\d)")
        for (match in any.findAll(loose)) {
            ArchiveNaming.normalizeDate(match.value)?.let { return it }
        }
        return null
    }

    /** جهة بدليل صريح: بعد عنوان («عميلنا:»، «المستفيد:») أو سطر يبدأ ببادئة جهة («شركة»، «محلات»). */
    private fun strongPartyOf(original: String): String? {
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
        return null
    }

    /**
     * جهة ضعيفة الدليل: أول سطر قصير بلا أرقام ولا كلمات حقول. تصيب في الأوراق البسيطة
     * («القاسمي») وتخطئ في الأوراق المزدحمة («أمام الهتار للصرافه»، «المحترمون»)، فجهة Gemini تتقدّم عليها.
     */
    private fun weakPartyOf(original: String): String? {
        val lines = original.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(12)
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
