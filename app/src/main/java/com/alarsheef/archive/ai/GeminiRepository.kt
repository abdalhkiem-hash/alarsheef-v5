package com.alarsheef.archive.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.alarsheef.archive.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
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

    /** نموذج سريع ورخيص — الافتراضي لتقليل استهلاك الحد اليومي. */
    const val MODEL_FLASH = "gemini-2.5-flash"

    /** نموذج أقوى (اختياري عبر الإعدادات) للصور الصعبة. */
    const val MODEL_PRO = "gemini-2.5-pro"

    /** سقف محاولات سحابية يومية يُحترم قبل أي استدعاء. */
    const val DAILY_LIMIT = 50

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_OCR_DIMENSION = 1600
    private const val MAX_CLASSIFY_DIMENSION = 1024

    /** هل أُضيف المفتاح فعلًا في local.properties؟ */
    fun isConfigured(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    fun model(usePro: Boolean): String = if (usePro) MODEL_PRO else MODEL_FLASH

    /**
     * استدعاء عام generateContent.
     * @param jsonSchema مخطط JSON اختياري — عند وجوده يُطلب رد JSON منظَّم.
     * @return نص المرشّح الأول، أو null عند أي فشل.
     */
    suspend fun generateContent(
        prompt: String,
        image: File?,
        usePro: Boolean,
        jsonSchema: String? = null,
        maxImageDimension: Int = MAX_CLASSIFY_DIMENSION,
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        try {
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

            val body = JSONObject()
                .put("contents", org.json.JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", generationConfig)

            val connection = URL("${BASE_URL}${model(usePro)}:generateContent")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
                connection.outputStream.use { out ->
                    out.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    // 429 = تجاوز حد اليوم، 400/403 = مفتاح أو طلب خاطئ — كلها تُعامل بصمت
                    Log.w(TAG, "HTTP $code من Gemini: ${response.take(300)}")
                    return@withContext null
                }
                firstText(response)?.takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "فشل استدعاء Gemini: ${e.javaClass.simpleName}: ${e.message}")
            null
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
