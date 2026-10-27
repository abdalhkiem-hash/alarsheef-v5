package com.alarsheef.archive.ai

/**
 * فحص صحة Gemini: هل المفتاح موجود وصالح، وهل كل نموذج يستجيب.
 * تصنيف الرد ورسائله دوال نقية تُختبر على JVM؛ النداء الفعلي في [GeminiRepository.ping].
 */
object GeminiHealth {

    enum class Status { OK, KEY_MISSING, KEY_REJECTED, MODEL_NOT_FOUND, RATE_LIMITED, UNAVAILABLE, NETWORK, OTHER }

    class ModelResult(val model: String, val status: Status, val httpCode: Int?, val millis: Long)

    class Report(val keyConfigured: Boolean, val results: List<ModelResult>)

    /** يحوّل رمز HTTP إلى حالة. 400 للمفتاح غير الصالح (يردّه Google INVALID_ARGUMENT)، لأن طلب الفحص ثابت وسليم. */
    fun classify(httpCode: Int?, networkFailed: Boolean): Status = when {
        networkFailed -> Status.NETWORK
        httpCode == null -> Status.OTHER
        httpCode in 200..299 -> Status.OK
        httpCode == 400 || httpCode == 401 || httpCode == 403 -> Status.KEY_REJECTED
        httpCode == 404 -> Status.MODEL_NOT_FOUND
        httpCode == 429 -> Status.RATE_LIMITED
        httpCode in 500..599 -> Status.UNAVAILABLE
        else -> Status.OTHER
    }

    fun isOk(status: Status): Boolean = status == Status.OK

    /** رسالة عربية قصيرة لكل حالة. */
    fun message(status: Status): String = when (status) {
        Status.OK -> "يعمل ✓"
        Status.KEY_MISSING -> "المفتاح غير مضبوط في local.properties"
        Status.KEY_REJECTED -> "المفتاح مرفوض أو غير صالح"
        Status.MODEL_NOT_FOUND -> "النموذج غير متاح لهذا المفتاح"
        Status.RATE_LIMITED -> "تجاوز الحد المسموح (قد لا تشمل الحصة المجانية هذا النموذج)"
        Status.UNAVAILABLE -> "خدمة Google مشغولة، أعد المحاولة لاحقًا"
        Status.NETWORK -> "لا اتصال بالإنترنت"
        Status.OTHER -> "رد غير متوقع"
    }

    /** يفحص النموذج الفعّال، فإن لم يوجد (404) فحص البديل أيضًا. المفتاح المفقود يعيد تقريرًا بلا نداء شبكة. */
    suspend fun check(): Report {
        if (!GeminiRepository.isConfigured()) return Report(false, emptyList())
        suspend fun run(model: String): ModelResult {
            val ping = GeminiRepository.ping(model)
            return ModelResult(model, classify(ping.httpCode, ping.networkFailed), ping.httpCode, ping.millis)
        }
        val primary = run(GeminiRepository.model())
        val results = if (primary.status == Status.MODEL_NOT_FOUND && primary.model != GeminiRepository.MODEL_FALLBACK) {
            listOf(primary, run(GeminiRepository.MODEL_FALLBACK))
        } else {
            listOf(primary)
        }
        return Report(true, results)
    }
}
