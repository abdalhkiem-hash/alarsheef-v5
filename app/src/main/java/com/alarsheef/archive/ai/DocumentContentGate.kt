package com.alarsheef.archive.ai

/** Test function to verify compilation */
fun testDocumentContentGateCompilation(): String = "compiled"

/**
 * بوابة التحقق من محتوى المستند النصي — المرحلة 1 من نهج التحقق المرحلي.
 * تعمل بشكل مستقل عن ImportGate و SmartClassifier.
 *
 * تريد-أوف موثق: التحقق النصي يُجرى في AiAnalysisWorker (بعد الاستيراد) لا في FileScannerWorker.
 * السبب: FileScannerWorker مسح يومي سريع — إضافة OCR متزامن يرفع زمن المسح 50-100x،
 * يكسر فصل الاهتمامات، ويمنع أرشفة الملفات "المعلّقة" للمراجعة عند فشل OCR.
 * المقايضة: الملفات تدخل الأرشيف أولاً بـ contentVerified=0، ثم تُصفّى لاحقًا.
 */
sealed class ContentVerdict {
    /** محتوى نصي واضح لمستند — يُقبل. */
    data class Accepted(
        val matchedKeywords: List<String>,
        val primaryCategory: DocumentCategory
    ) : ContentVerdict()

    /** محتوى نصي ضعيف/غامض — يُحفظ للمراجعة اليدوية. */
    data class Ambiguous(
        val partialMatches: List<String>
    ) : ContentVerdict()

    /** محتوى نصي لا يدل على مستند — يُرفض. */
    data class Rejected(
        val reason: String
    ) : ContentVerdict()
}

/** فئات المستندات المستهدفة. */
enum class DocumentCategory {
    INVOICE,      // فاتورة
    RECEIPT,      // إيصال/حوالة
    CASH_BOX,     // حركة صندوق
    CONTRACT,     // عقد
    ID_DOCUMENT,  // هوية/جواز
    NOTE          // ملاحظات/نص عام
}

/**
 * يطبع النص المُستخرج ويقرر إن كان يدل على مستند مستهدف.
 * مطابقة كلمات مفتاحية مع تطبيع نص (إزالة تشكيل، توحيد مسافات) وشرط "أكثر من دليل".
 */
fun verify(ocrText: String): ContentVerdict {
    if (ocrText.isNullOrBlank()) {
        return ContentVerdict.Rejected("نص فارغ")
    }

    val normalized = normalize(ocrText)

    // كلمات مفتاحية لكل فئة — تطبيع مسبق
    val keywords = mapOf(
        DocumentCategory.INVOICE to listOf("فاتورة", "مبلغ", "إجمالي", "ضريبة", "قيمة", "مشتريات", "مبيعات", "فاتورة ضريبية", "invoice", "total", "tax"),
        DocumentCategory.RECEIPT to listOf("إيصال", "حوالة", "تحويل", "استلام", "دفع", "قبض", "مبلغ", "مرسل", "مستلم", "receipt", "transfer"),
        DocumentCategory.CASH_BOX to listOf("صندوق", "حركة", "إيداع", "سحب", "رصيد", "أمانة", "أمانات", "cash", "box"),
        DocumentCategory.CONTRACT to listOf("عقد", "إتفاق", "بند", "طرف أول", "طرف ثاني", "مدة", "إيجار", "بيع", "شراء", "contract"),
        DocumentCategory.ID_DOCUMENT to listOf("هوية", "جواز", "رقم قومي", "بطاقة", "سجل مدني", "تاريخ ميلاد", "id", "passport", "national id"),
        DocumentCategory.NOTE to listOf("ملاحظة", "ملاحظات", "تذكير", "هام", "هام جداً", "note", "memo")
    )

    val matches = mutableMapOf<DocumentCategory, MutableList<String>>()
    var totalMatches = 0

    keywords.forEach { (category, words) ->
        val found = words.filter { word ->
            normalized.contains(word, ignoreCase = true)
        }
        if (found.isNotEmpty()) {
            matches[category] = found.toMutableList()
            totalMatches += found.size
        }
    }

    return when {
        // لا توجد أي مطابقة
        totalMatches == 0 -> ContentVerdict.Rejected("لا توجد كلمات مفتاحية لمستند")

        // مطابقة واحدة فقط → غامض (قد يكون نصاً عشوائياً)
        totalMatches == 1 -> {
            val (cat, words) = matches.entries.first()
            ContentVerdict.Ambiguous(words)
        }

        // مطابقة في أكثر من فئة → غامض (نص مختلط)
        matches.size > 1 -> {
            val allWords = matches.values.flatten()
            ContentVerdict.Ambiguous(allWords)
        }

        // مطابقة قوية في فئة واحدة فقط → مقبول
        else -> {
            val (cat, words) = matches.entries.first()
            ContentVerdict.Accepted(words, cat)
        }
    }
}

/** يطبع النص: يزيل التشكيل، يوحد المسافات، يحول للأحرف الأساسية. */
private fun normalize(text: String): String {
    return text
        // إزالة التشكيل العربي (علامات التشكيل: 0x064B-0x065F)
        .replace(Regex("[\\u064B-\\u065F]"), "")
        // توحيد الألف: أ إ آ → ا
        .replace(Regex("[\\u0622\\u0623\\u0625]"), "ا")
        // توحيد الياء: ي ى → ي
        .replace(Regex("[\\u0649\\u064A]"), "ي")
        // توحيد التاء المربوطة: ة → ه
        .replace("ة", "ه")
        // إزالة علامات الترقيم والرموز
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        // توحيد المسافات
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()
}