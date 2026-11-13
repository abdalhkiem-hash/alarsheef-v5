package com.alarsheef.archive.ai

/**
 * هل قراءة OCR المحلية صالحة للاعتماد؟ Tesseract لا يخطئ قليلًا على الصور المائلة أو المشوّشة،
 * بل يُرجع نصًا فارغًا أو حروفًا عشوائية. يُكشف الفشل بمؤشرات رخيصة، فيُصعَّد الملف
 * (إلى تصحيح الميل ثم Gemini) بدل أن يُحفظ نص خاطئ ويُبنى عليه التصنيف والبحث والأرشفة.
 */
object OcrQuality {

    enum class Verdict {
        /** لا نص يُعتدّ به. */
        EMPTY,
        /** نص موجود لكنه غالبًا عشوائي أو بثقة منخفضة. */
        POOR,
        /** قراءة موثوقة. */
        OK,
    }

    /** أقل عدد حروف وأرقام ليُعدّ النص موجودًا. */
    const val MIN_SIGNIFICANT_CHARS = 8

    /** ثقة Tesseract (0..100) تحتها تُعدّ القراءة رديئة. */
    const val MIN_CONFIDENCE = 62

    /** نسبة الكلمات القصيرة (حرف أو حرفان) فوقها يُشتبه بالعشوائية. */
    private const val MAX_SHORT_TOKEN_RATIO = 0.55

    /** أقل نسبة للحروف العربية بين الحروف في نص عربي (بقية النص أرقام ولاتينية قليلة). */
    private const val MIN_ARABIC_RATIO = 0.5

    /**
     * @param confidence متوسط ثقة المحرك إن وُجد (سامسونغ والنظام لا يعطيانه: null).
     */
    fun assess(text: String?, confidence: Int? = null): Verdict {
        val clean = text?.trim().orEmpty()
        var arabic = 0
        var latin = 0
        var digits = 0
        var marks = 0
        for (c in clean) {
            when {
                c in 'ء'..'ي' -> arabic++
                c.isLetter() && c.code < 0x250 -> latin++
                c in '0'..'9' || c in '٠'..'٩' -> digits++
                c == '‎' || c == '‏' || c in '‪'..'‮' || c in '̀'..'ͯ' -> marks++
            }
        }
        val significant = arabic + latin + digits
        if (significant < MIN_SIGNIFICANT_CHARS) return Verdict.EMPTY

        val tokens = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shortRatio = tokens.count { it.length <= 2 }.toDouble() / tokens.size
        val letters = arabic + latin
        val arabicRatio = if (letters == 0) 1.0 else arabic.toDouble() / letters
        // علامات اتجاه كثيرة (RLM/LRM) علامة صريحة على نص ممزَّق
        val markRatio = marks.toDouble() / clean.length

        if (confidence != null && confidence in 0..100 && confidence < MIN_CONFIDENCE) return Verdict.POOR
        if (tokens.size >= 4 && shortRatio > MAX_SHORT_TOKEN_RATIO) return Verdict.POOR
        if (markRatio > 0.04) return Verdict.POOR
        // نص فيه عربية مختلطة بلاتينية عشوائية (محرك عربي أخطأ الخط)
        if (arabic > 0 && letters >= 12 && arabicRatio < MIN_ARABIC_RATIO) return Verdict.POOR
        return Verdict.OK
    }

    fun isUsable(text: String?, confidence: Int? = null): Boolean = assess(text, confidence) == Verdict.OK
}
