package com.alarsheef.archive.ai

/**
 * متى تتوقف التسمية السحابية، ومتى تُحسب الحصة.
 * دالة نقية حتى تُختبر بلا شبكة ولا جهاز.
 */
object GeminiCallPolicy {

    /** بعد 429: حد الدقيقة لدى Google ينقضي خلال دقائق. */
    const val RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000L

    /** بعد 503 أو انقطاع الشبكة: النموذج مشغول، لا فائدة من إعادة الضرب فورًا. */
    const val UNAVAILABLE_COOLDOWN_MS = 15 * 60 * 1000L

    /**
     * مدة إيقاف النداءات التالية. 0 = هذا الرد لا يوقف الدفعة
     * (مثل 400 أو رد 200 فارغ).
     */
    fun cooldownMs(httpCode: Int?, networkFailed: Boolean): Long = when {
        networkFailed -> UNAVAILABLE_COOLDOWN_MS
        httpCode == 429 -> RATE_LIMIT_COOLDOWN_MS
        httpCode == 500 || httpCode == 502 || httpCode == 503 || httpCode == 504 -> UNAVAILABLE_COOLDOWN_MS
        else -> 0L
    }

    /** الحصة تُسجَّل فقط بعد رد ناجح فيه نص. الفشل لا يخصم. */
    fun commitQuota(httpOk: Boolean, hasText: Boolean): Boolean = httpOk && hasText
}
