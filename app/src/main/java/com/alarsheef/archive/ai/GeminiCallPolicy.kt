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

    /**
     * قرار الانتقال بعد فشل نموذج: كم يُتخطّى هذا النموذج، وهل يُجرَّب التالي في السلسلة.
     * المبدأ: أي فشل يخص النموذج نفسه (حصة، ازدحام، غير موجود، رد فارغ/غير صالح، مهلة) ينقل
     * إلى التالي فورًا فلا يتوقف العمل. وحده انقطاع الإنترنت أو رفض المفتاح يوقف الكل،
     * لأن النماذج الأخرى ستفشل للسبب نفسه.
     */
    data class Failover(val skipModelMs: Long, val tryNext: Boolean)

    const val MINUTE_LIMIT_SKIP_MS = 60 * 1000L
    const val BUSY_SKIP_MS = 10 * 60 * 1000L
    const val TIMEOUT_SKIP_MS = 15 * 60 * 1000L
    const val MISSING_MODEL_SKIP_MS = 24 * 60 * 60 * 1000L

    fun failover(
        httpCode: Int?,
        body: String,
        timedOut: Boolean,
        offline: Boolean,
        nowMillis: Long,
    ): Failover = when {
        offline -> Failover(0L, tryNext = false)
        timedOut -> Failover(TIMEOUT_SKIP_MS, tryNext = true)
        httpCode == 401 || httpCode == 403 -> Failover(0L, tryNext = false)
        isDailyQuotaExhausted(httpCode, body) ->
            Failover(retryAfterMs(body) ?: msUntilQuotaReset(nowMillis), tryNext = true)
        httpCode == 429 -> Failover(retryAfterMs(body) ?: MINUTE_LIMIT_SKIP_MS, tryNext = true)
        httpCode == 404 -> Failover(MISSING_MODEL_SKIP_MS, tryNext = true)
        httpCode != null && httpCode in 500..599 -> Failover(BUSY_SKIP_MS, tryNext = true)
        // 400 (طلب لا يقبله هذا النموذج) أو 200 برد فارغ/غير صالح: نموذج آخر قد ينجح — بلا تخطٍّ
        else -> Failover(0L, tryNext = true)
    }

    /** منطقة Google لتجديد الحصة اليومية: منتصف الليل بتوقيت المحيط الهادئ. */
    const val QUOTA_ZONE = "America/Los_Angeles"

    /**
     * 429 بسبب الحصة **اليومية** للنموذج (لا حد الدقيقة): لا فائدة من أي نداء آخر لهذا
     * النموذج حتى تتجدد. ردّ Google يحمل `GenerateRequestsPerDay...` أو «per day».
     */
    fun isDailyQuotaExhausted(httpCode: Int?, body: String): Boolean =
        httpCode == 429 && (body.contains("PerDay") || body.contains("per day", ignoreCase = true))

    private val RETRY_IN = Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?", RegexOption.IGNORE_CASE)
    private val RETRY_DELAY = Regex("\"retryDelay\"\\s*:\\s*\"([\\d.]+)s\"")

    /** المدة التي يطلب Google الانتظار قبلها («Please retry in 2h38m0.5s» أو retryDelay)؛ null لو غابت. */
    fun retryAfterMs(body: String): Long? {
        RETRY_IN.find(body)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val min = m.groupValues[2].toLongOrNull() ?: 0L
            val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val ms = (h * 3600 + min * 60) * 1000 + (s * 1000).toLong()
            if (ms > 0) return ms
        }
        return RETRY_DELAY.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() }?.takeIf { it > 0 }
    }

    /** المدة حتى منتصف الليل القادم بتوقيت [QUOTA_ZONE] — احتياط حين لا يذكر الرد موعدًا. */
    fun msUntilQuotaReset(nowMillis: Long): Long {
        val zone = java.time.ZoneId.of(QUOTA_ZONE)
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val next = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        return java.time.Duration.between(now, next).toMillis()
    }

    /** «يوم الحصة» بتوقيت Google، لا بتوقيت الهاتف: عدّاد التطبيق يتجدد مع حصة Google. */
    fun quotaDay(nowMillis: Long): String =
        java.time.Instant.ofEpochMilli(nowMillis).atZone(java.time.ZoneId.of(QUOTA_ZONE)).toLocalDate().toString()
}
