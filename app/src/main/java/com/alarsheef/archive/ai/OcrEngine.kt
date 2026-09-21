package com.alarsheef.archive.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * OCR على الجهاز نفسه عبر نموذج النظام المدمج
 * (android.graphics.text.TextRecognizer — Android 13+).
 *
 * يُستدعى عبر الانعكاس بدل الاعتماد وقت الترجمة: بعض بيئات SDK المخفّفة
 * لا تُضمّن هذه الفئة في android.jar، لكنها موجودة فعلًا في معظم أجهزة
 * Android 13+ الحقيقية (هي ما يشغّل "تحديد النص الذكي") — الانعكاس يحافظ
 * على ترجمة نجاح دائمًا، ويرجع null بهدوء إذا لم يجد النموذج.
 *
 * الملاحظة: دعم العربية يعتمد على نواة الجهاز؛ إن لم يُرجع نصًّا للغة واحدة
 * يُستخدم النص الموجود على أي حال (أفضل نتيجة ممكنة).
 */
object OcrEngine {

    private const val CLASS_NAME = "android.graphics.text.TextRecognizer"
    private const val MAX_DIMENSION = 2048

    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        var instance: Any? = null
        return try {
            val cls = Class.forName(CLASS_NAME)
            instance = cls.getMethod("create", Context::class.java).invoke(null, context) ?: return false
            supportMethods().any { method ->
                runCatching { (cls.getMethod(method).invoke(instance) as? Boolean) ?: false }.getOrDefault(false)
            }
        } catch (e: Throwable) {
            false
        } finally {
            instance?.let { closeInstance(it) }
        }
    }

    suspend fun recognizeFile(context: Context, file: File): String? = withContext(Dispatchers.IO) {
        var instance: Any? = null
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext null
            val cls = Class.forName(CLASS_NAME)
            instance = cls.getMethod("create", Context::class.java).invoke(null, context) ?: return@withContext null
            val supported = supportMethods().any { method ->
                runCatching { (cls.getMethod(method).invoke(instance) as? Boolean) ?: false }.getOrDefault(false)
            }
            if (!supported) return@withContext null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@withContext null

            try {
                val result = cls.getMethod("recognize", Bitmap::class.java).invoke(instance, bitmap)
                    ?: return@withContext null
                val segmentClass = result.javaClass
                val available = runCatching {
                    (segmentClass.getMethod("isRecognizedTextAvailable").invoke(result) as? Boolean) ?: false
                }.getOrDefault(false)
                if (!available) return@withContext null
                val text = runCatching {
                    (segmentClass.getMethod("getResultText").invoke(result) as? CharSequence)?.toString()
                }.getOrNull()
                val out = text?.trim().orEmpty()
                if (out.length < 3) null else out
            } finally {
                bitmap.recycle()
            }
        } catch (e: Throwable) {
            null
        } finally {
            instance?.let { closeInstance(it) }
        }
    }

    /** TextRecognizer يرث AutoCloseable — نغلقه عبر الانعكاس لتحرير نموذج النص */
    private fun closeInstance(instance: Any) {
        runCatching { instance.javaClass.getMethod("close").invoke(instance) }
    }

    /** "isSupported" أُضيف في API 34؛ الأجهزة على API 33 تعرض isRecognizerSupported */
    private fun supportMethods(): List<String> = listOf("isSupported", "isRecognizerSupported")
}