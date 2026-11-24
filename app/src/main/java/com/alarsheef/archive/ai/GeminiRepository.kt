package com.alarsheef.archive.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.alarsheef.archive.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * عميل سحابي لـ Google Gemini عبر REST (v1beta) بدون أي مكتبة خارجية —
 * HttpURLConnection فقط، والاستدعاء يعمل على خيط IO.
 *
 * المفتاح يأتي من BuildConfig.GEMINI_API_KEY (يُحقَّن من local.properties عند البناء)
 * ويُرسل في ترويسة x-goog-api-key فقط — لا يُطبع ولا يُخزَّن في قاعدة البيانات.
 *
 * سياسة الفشل: أي خطأ (شبكة، 429 تجاوز حد اليوم، مفتاح خاطئ، استجابة غير صالحة)
 * يُعاد منه null بهدوء حتى يكمل المحلّل المحلي عمله — لا استثناء يُسقط المسح.
 */
object GeminiRepository {

    private const val TAG = "GeminiRepository"

    /**
     * سلسلة النماذج مرتبة من الأدق إلى الأخف (فُحصت على المفتاح 24/11/2026).
     * لكل نموذج حصته اليومية المستقلة في الطبقة المجانية (20 طلبًا)، فالسلسلة كلها ~140 طلبًا يوميًا.
     * أي فشل في نموذج (حصة، ازدحام 503، غير موجود 404، مهلة، رد فارغ أو غير صالح) ينقل النداء نفسه
     * فورًا إلى التالي: الدقة أولًا ثم الاستمرارية. `gemini-3.5-flash` حُذف لأنه يتشارك حصة 3.6.
     * نماذج 2.5 تعيد 404 على هذا المفتاح، وPro ترفضه الطبقة المجانية.
     */
    val MODEL_CHAIN: List<String> = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3-flash-preview",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-flash-lite-latest",
    )

    /** النموذج المفضّل (رأس السلسلة). */
    val MODEL_FLASH: String get() = MODEL_CHAIN.first()

    /** حد Google المجاني اليومي لكل نموذج (رد 429 الفعلي: quotaValue = 20). */
    const val PER_MODEL_DAILY_LIMIT = 20

    /** سقف محاولات سحابية يومية يُحترم قبل أي استدعاء: حصص نماذج السلسلة معًا. */
    const val DAILY_LIMIT = PER_MODEL_DAILY_LIMIT * 7

    /** متى يعود كل نموذج للخدمة بعد فشله. يُحفظ على القرص فلا يُحرق نداء فاشل مع كل دفعة أو تشغيل. */
    private val skipUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    private var store: android.content.SharedPreferences? = null

    /** آخر نموذج أجاب بنجاح — يُسجَّل مع نتيجة التحليل. */
    @Volatile
    private var lastAnswered: String = MODEL_CHAIN.first()

    /** يُستدعى من Application: يسترجع حالة النماذج المحفوظة. */
    fun attach(context: android.content.Context) {
        val prefs = context.applicationContext.getSharedPreferences("gemini_quota_state", android.content.Context.MODE_PRIVATE)
        store = prefs
        val now = System.currentTimeMillis()
        val stale = ArrayList<String>()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(SKIP_PREFIX) && !key.startsWith(OLD_PREFIX)) return@forEach
            if (value is Long && value > now) {
                skipUntil[key.removePrefix(SKIP_PREFIX).removePrefix(OLD_PREFIX)] = value
            } else {
                stale += key
            }
        }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        refreshPause()
    }

    private const val SKIP_PREFIX = "skip_"
    private const val OLD_PREFIX = "exhausted_"

    private fun isSkipped(model: String): Boolean =
        (skipUntil[model] ?: 0L) > System.currentTimeMillis()

    /** كل نماذج السلسلة متوقفة (حصة/ازدحام): لا نداء حتى يعود أحدها. */
    val dailyQuotaExhausted: Boolean
        get() = MODEL_CHAIN.all { isSkipped(it) }

    private fun skipModel(model: String, ms: Long, reason: String) {
        if (ms <= 0L) return
        val until = System.currentTimeMillis() + ms
        skipUntil[model] = until
        store?.edit()?.putLong(SKIP_PREFIX + model, until)?.apply()
        Log.w(TAG, "تخطّي $model حتى ${java.util.Date(until)}: $reason")
        refreshPause()
    }

    /** يُظهر التوقف في الشاشة الرئيسية حين تتوقف كل النماذج. */
    private fun refreshPause() {
        if (!dailyQuotaExhausted) return
        val until = MODEL_CHAIN.minOf { skipUntil[it] ?: 0L }
        if (until > rateLimitedUntil) rateLimitedUntil = until
        if (until > pauseUntil.value) pauseUntil.value = until
    }

    /**
     * true أثناء تهدئة عامة (انقطاع الشبكة) أو حين تتوقف كل النماذج.
     * يقرأه العامل كي يتوقّف عن استهلاك حصّته اليومية على نداءات مصيرها الفشل.
     */
    val rateLimited: Boolean
        get() = System.currentTimeMillis() < rateLimitedUntil || dailyQuotaExhausted

    /**
     * نهاية التهدئة. تبقى حتى تنقضي حتى لو بدأت دفعة تحليل جديدة،
     * وإلا كان خطأ 503 يُعاد ضربه على كل صورة.
     */
    @Volatile
    private var rateLimitedUntil: Long = 0L

    private val pauseUntil = MutableStateFlow(0L)

    /** تقرأه الشاشة الرئيسية لإظهار «التسمية السحابية متوقفة مؤقتًا». */
    val cloudPauseUntil: StateFlow<Long> = pauseUntil.asStateFlow()

    /** لا تُمسح تهدئة ما زالت سارية، ولا توقف النماذج. */
    fun resetRateLimit() {
        if (rateLimited) return
        rateLimitedUntil = 0L
        if (pauseUntil.value != 0L) pauseUntil.value = 0L
    }

    private fun pauseFor(httpCode: Int?, networkFailed: Boolean) {
        val cooldown = GeminiCallPolicy.cooldownMs(httpCode, networkFailed)
        if (cooldown <= 0L) return
        val until = System.currentTimeMillis() + cooldown
        if (until > rateLimitedUntil) rateLimitedUntil = until
        if (until > pauseUntil.value) pauseUntil.value = until
    }

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val MAX_OCR_DIMENSION = 1600
    private const val MAX_CLASSIFY_DIMENSION = 1024

    /** هل أُضيف المفتاح فعلًا في local.properties؟ */
    fun isConfigured(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    /**
     * النموذج الذي أجاب آخر نداء ناجح (يُسجَّل مع نتيجة التحليل)، أو أول نموذج متاح.
     * [usePro] مُبقى في التوقيع فقط، لا يغيّر النموذج.
     */
    @Suppress("UNUSED_PARAMETER")
    fun model(usePro: Boolean = false): String = lastAnswered

    /** أول نموذج متاح الآن في السلسلة (للفحص والعرض). */
    fun firstAvailableModel(): String = MODEL_CHAIN.firstOrNull { !isSkipped(it) } ?: MODEL_CHAIN.first()

    /**
     * استدعاء عام generateContent على سلسلة النماذج.
     * @param jsonSchema مخطط JSON اختياري — عند وجوده يُطلب رد JSON منظَّم.
     * @param accept يتحقق من الرد؛ الرد المرفوض (JSON غير صالح مثلًا) ينقل إلى النموذج التالي.
     * @return نص أول رد مقبول، أو null إن فشلت كل النماذج.
     */
    suspend fun generateContent(
        prompt: String,
        image: File?,
        usePro: Boolean,
        jsonSchema: String? = null,
        maxImageDimension: Int = MAX_CLASSIFY_DIMENSION,
        accept: (String) -> Boolean = { true },
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured() || rateLimited) return@withContext null
        val body = try {
            val parts = org.json.JSONArray().put(JSONObject().put("text", prompt))
            if (image != null) {
                val base64 = encodeJpegBase64(image, maxImageDimension) ?: return@withContext null
                parts.put(
                    JSONObject()
                        .put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", base64))
                )
            }
            val generationConfig = JSONObject().put("temperature", 0.1)
            if (jsonSchema != null) {
                generationConfig
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", JSONObject(jsonSchema))
            }
            JSONObject()
                .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", generationConfig)
                .toString()
        } catch (e: Exception) {
            Log.w(TAG, "تعذّر تجهيز الطلب: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext null
        }

        for (model in MODEL_CHAIN) {
            if (isSkipped(model)) continue
            val attempt = callModel(model, body)
            if (attempt.text != null && accept(attempt.text)) {
                lastAnswered = model
                return@withContext attempt.text
            }
            val decision = GeminiCallPolicy.failover(
                attempt.httpCode, attempt.errorBody, attempt.timedOut, attempt.offline,
                System.currentTimeMillis(),
            )
            val reason = when {
                attempt.offline -> "لا اتصال"
                attempt.timedOut -> "انتهت المهلة"
                attempt.httpCode in 200..299 -> "رد فارغ أو غير صالح"
                else -> "HTTP ${attempt.httpCode}"
            }
            Log.w(TAG, "فشل $model ($reason)${if (decision.tryNext) "، الانتقال إلى التالي" else ""}")
            skipModel(model, decision.skipModelMs, reason)
            if (!decision.tryNext) {
                pauseFor(attempt.httpCode, networkFailed = attempt.offline)
                return@withContext null
            }
        }
        Log.w(TAG, "فشلت كل نماذج السلسلة لهذا الطلب")
        null
    }

    private class Attempt(
        val text: String?,
        val httpCode: Int?,
        val errorBody: String = "",
        val timedOut: Boolean = false,
        val offline: Boolean = false,
    )

    private fun callModel(model: String, body: String): Attempt = try {
        val connection = URL("${BASE_URL}$model:generateContent").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                Attempt(firstText(response)?.takeIf { it.isNotBlank() }, code)
            } else {
                Attempt(null, code, errorBody = response)
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: java.net.SocketTimeoutException) {
        Attempt(null, null, timedOut = true)
    } catch (e: IOException) {
        // لا اتصال بالإنترنت (لا DNS، لا شبكة): يوقف السلسلة كلها
        Attempt(null, null, offline = true)
    } catch (e: Exception) {
        Log.w(TAG, "فشل استدعاء $model: ${e.javaClass.simpleName}: ${e.message}")
        Attempt(null, null)
    }

    /** نتيجة نداء الفحص: رمز HTTP (null عند انقطاع الشبكة) ومدة الرد. */
    class PingResult(val httpCode: Int?, val networkFailed: Boolean, val millis: Long)

    /**
     * نداء نصي صغير جدًا لنموذج معيّن للتحقق من المفتاح والنموذج.
     * لا يخصم من الحصة اليومية ولا يفعّل تهدئة الاستدعاءات ([pauseFor])، فلا يؤثر على التحليل الفعلي.
     */
    suspend fun ping(model: String): PingResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!isConfigured()) return@withContext PingResult(null, false, 0L)
        try {
            val body = JSONObject()
                .put(
                    "contents",
                    org.json.JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", org.json.JSONArray().put(JSONObject().put("text", "اكتب كلمة: تم")))
                    )
                )
                .put("generationConfig", JSONObject().put("temperature", 0.0))
            val connection = URL("${BASE_URL}$model:generateContent").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                // يُستهلك الرد حتى يُغلق الاتصال نظيفًا
                (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
                PingResult(code, false, System.currentTimeMillis() - started)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            PingResult(null, true, System.currentTimeMillis() - started)
        } catch (e: Exception) {
            PingResult(null, false, System.currentTimeMillis() - started)
        }
    }

    /** OCR سحابي: نص حرفي بالعربية (يعمل على أي إصدار أندرويد). */
    suspend fun extractText(file: File, usePro: Boolean): String? = generateContent(
        prompt = OCR_PROMPT,
        image = file,
        usePro = usePro,
        maxImageDimension = MAX_OCR_DIMENSION,
    )?.trim()?.takeIf { it.isNotBlank() }

    /** يفكّ عيّنة منخفضة الأبعاد ويعيد JPEG مضغوطًا base64 (سطر واحد). */
    private fun encodeJpegBase64(file: File, maxDimension: Int): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) sample *= 2

        var bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        try {
            val longest = max(bitmap.width, bitmap.height)
            if (longest > maxDimension) {
                val scale = maxDimension.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            val bytes = ByteArrayOutputStream().also { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    /** يقرأ المرشّح الأول من استجابة generateContent. */
    private fun firstText(response: String): String? = runCatching {
        val parts = JSONObject(response)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        (0 until parts.length())
            .mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private val OCR_PROMPT = """
        هذه صورة من أرشيف مستندات. اقرأ كل النص الظاهر فيها واكتبه حرفيًا بلغته الأصلية
        (خاصة النص العربي بتشكيله وترقيمه العربي-الهندي)، مع الحفاظ على أسطر النص وترتيبه
        وتباينه البصري. لا تترجم ولا تلخّص ولا تضف أي تعليق أو عنوان.
        إن لم يكن في الصورة أي نص أصلاً أعد الجملة التالية بالضبط: لا يوجد نص
    """.trimIndent()
}
