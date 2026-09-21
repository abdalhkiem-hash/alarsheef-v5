package com.alarsheef.archive.ai

/**
 * اقتراح أسماء ذكية لنطاقات التسمية (سنة/شهر/يوم) في نوافذ "التسمية المخصصة".
 * يستخرج الاقتراحات من وسوم التصنيف التلقائي + النصوص الممسوحة (OCR) + أسماء
 * مجموعات الوجوه المسماة، بقواعد كلمات مفتاحية عربية، ويعيد حتى 3 اقتراحات.
 * كلها حوسبة محلية بلا أي اتصال.
 */
object NameSuggester {

    private val DOCUMENT_KEYWORDS = mapOf(
        "عقد" to "وثائق عقود",
        "شهادة" to "شهادات / مستندات رسمية",
        "فاتورة" to "فواتير",
        "إيصال" to "إيصالات",
        "تذكرة" to "تذاكر",
        "جواز" to "جوازات / وثائق سفر",
        "قرار" to "قرارات رسمية",
        "تقرير" to "تقارير",
        "رخصة" to "رخص",
        "حجز" to "حجوزات",
        "خطاب" to "خطابات",
        "تقدير" to "مستندات درجات"
    )

    /**
     * @param labels وسوم التصنيف التلقائي المجمعة لكل صور النطاق (من image_labels).
     * @param ocrText النصوص المجمعة (محدودة الطول) من كل صور النطاق.
     * @param faceNames أسماء مجموعات الوجوه المسماة التي تظهر ضمن النطاق.
     */
    fun suggestFor(labels: List<String>, ocrText: String, faceNames: List<String>): List<String> {
        val results = linkedSetOf<String>()
        val text = ocrText.take(3000)

        // 1) مفتاح مستندي واضح من النص المكتشف
        for ((keyword, suggestion) in DOCUMENT_KEYWORDS) {
            if (text.contains(keyword)) {
                results += suggestion
            }
        }

        // 2) غلبة الوسوم
        val counted = labels.groupingBy { it }.eachCount()
        counted.entries
            .sortedByDescending { it.value }
            .take(2)
            .forEach { (label, _) ->
                when {
                    label.contains("مستند") && !results.any { it.contains("مستند") } ->
                        results += "وثائق / أوراق"
                    label.contains("شاشة") -> results += "لقطات شاشة"
                    label.contains("طبيعة") || label.contains("حديقة") -> results += "صور الطبيعة"
                    label.contains("سماء") || label.contains("ماء") -> results += "صور سماء / ماء"
                    label.contains("وجه") || label.contains("أشخاص") ->
                        if (!results.any { it.contains("عائلة") || it.contains("أشخاص") }) results += "صور أشخاص"
                    label.contains("أبيض وأسود") ->
                        if (!results.any { it.contains("أبيض وأسود") }) results += "صور أبيض وأسود"
                }
            }

        // 3) مجموعات الوجوه المسماة: "صور فلان"
        val knownNames = faceNames.filter { !it.isBlank() && it.isNotBlank() }
        if (knownNames.isNotEmpty()) {
            val namesStr = knownNames.take(3).joinToString(" ")
            results += "صور $namesStr"
        }

        if (results.isEmpty() && counted.isNotEmpty()) {
            // آخر وسيلة: وسوم عامة
            results += "صور " + counted.entries.first().key
        }

        return results.take(3).toList()
    }
}