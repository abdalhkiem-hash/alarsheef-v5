package com.alarsheef.archive.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** وسم تلقائي: اسم + درجة ثقة 0..1 */
data class ClassifiedLabel(val label: String, val confidence: Float)

/**
 * مصنّف محتوى الصورة يعمل كليًا على الجهاز بدون أي تبعية خارجية.
 * يعتمد على خصائص كلاسيكية مُستخرجة من الصورة (درجة الحدة، التشبّع، السطوع،
 * الهيمنة اللونية، توزع الحواف) وقواعد حاكمة بسيطة تُرجِع وسومًا عربية مثل:
 * "مستند/ورقة" و"لقطة شاشة" و"طبيعة" و"سماء" و"داخلي" و"أبيض وأسود".
 *
 * ليس شبكة عصبية ضخمة — لكنه يغطي التصنيفات الشائعة في أرشيف عائلي
 * بدقة عملية مقبولة مع صفر إنترنت وصفر تنزيلات.
 */
object SmartClassifier {

    private const val ANALYSIS_DIMENSION = 512

    /**
     * يحلل ملف صورة ويعيد الوسوم مرتبة بثقتها (الأعلى أولًا) بدون تكرار.
     * @param facesDetected عدد الوجوه التي اكتشفها ML Kit مسبقًا (إن توفرت) —
     *        تُحول إلى وسم "صورة شخص/وجه" إضافي بثقة ثابتة.
     */
    fun classifyFile(file: File, facesDetected: Int = 0): List<ClassifiedLabel> {
        val bitmap = decodeSampled(file) ?: return emptyList()
        try {
            return classify(bitmap, facesDetected)
        } finally {
            bitmap.recycle()
        }
    }

    fun classify(bitmap: Bitmap, facesDetected: Int = 0): List<ClassifiedLabel> {
        val gray = IntArray(bitmap.width * bitmap.height)
        val sat = FloatArray(bitmap.width * bitmap.height)
        val hue = FloatArray(bitmap.width * bitmap.height)
        var lumSum = 0.0
        var satSum = 0.0
        var minLum = 255.0
        var maxLum = 0.0

        repeat(bitmap.width * bitmap.height) { idx ->
            val x = idx % bitmap.width
            val y = idx / bitmap.width
            val color = bitmap.getPixel(x, y)
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val lum = (0.299 * r + 0.587 * g + 0.114 * b)
            gray[idx] = lum.toInt()
            lumSum += lum
            minLum = min(minLum, lum)
            maxLum = max(maxLum, lum)

            val s = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            sat[idx] = s
            satSum += s

            if (mx != mn) {
                val delta = (mx - mn).toFloat()
                val h = when (mx) {
                    r -> 60f * (((g - b) / delta) % 6f)
                    g -> 60f * (((b - r) / delta) + 2f)
                    else -> 60f * (((r - g) / delta) + 4f)
                }
                hue[idx] = if (h < 0) h + 360f else h
            }
        }

        val w = bitmap.width
        val h = bitmap.height
        val n = gray.size
        val meanLum = lumSum / n
        val meanSat = satSum / n

        // ارتفاعات لونية: سماوي/أزرق (170..260)، أخضر (70..160)، دافئ (0..60 أو 300..360)
        var blueCount = 0
        var greenCount = 0
        var warmCount = 0
        var saturatedCount = 0
        repeat(n) { idx ->
            val hh = hue[idx]
            if (sat[idx] > 0.15f) {
                saturatedCount++
                when {
                    hh in 170f..260f -> blueCount++
                    hh in 70f..160f -> greenCount++
                    hh < 60f || hh >= 300f -> warmCount++
                }
            }
        }
        val blueRatio = blueCount.toFloat() / n
        val greenRatio = greenCount.toFloat() / n
        val warmRatio = warmCount.toFloat() / n

        // درجة انحراف اللون عن الأبيض/الرمادي (تدل على صورة ملونة)
        val chroma = satSum / n

        // كشف الحدود بمشتق بسيط على قناة الإضاءة (Sobel مبسط على الأعمدة بعدد كافٍ)
        var edgeSum = 0.0
        var edgeCount = 0
        var y = 1
        while (y < h - 1 && edgeCount < 40_000) {
            var x = 1
            while (x < w - 1 && edgeCount < 40_000) {
                val gx = gray[y * w + x + 1] - gray[y * w + x - 1]
                val gy = gray[(y + 1) * w + x] - gray[(y - 1) * w + x]
                val mag = sqrt((gx.toDouble() * gx) + (gy.toDouble() * gy))
                edgeSum += mag
                edgeCount++
                x += 2
            }
            y += 2
        }
        val edgeMean = if (edgeCount > 0) edgeSum / edgeCount else 0.0
        // كثافة الحواف القوية (نص/طاولة) — التقطيع وتوزيعها
        var strongRate = 0.0
        if (edgeCount > 0) {
            var strong = 0
            var i = 1
            while (i < w - 1) {
                var j = 1
                while (j < h - 1) {
                    val g = abs(gray[j * w + i + 1] - gray[j * w + i - 1]) +
                        abs(gray[(j + 1) * w + i] - gray[(j - 1) * w + i])
                    if (g > 140) strong++
                    j += 2
                }
                i += 2
            }
            strongRate = strong.toDouble() / (w * h)
        }

        val results = mutableListOf<ClassifiedLabel>()

        // مخطّط روائي/ملف نصي: مستوى رمادي، حواف كثيفة، تباين واسع — تُقوّى كثافة الحواف القوية الرأي
        val docScore = if (meanSat < 0.28 && edgeMean > 14.0 && (maxLum - minLum) > 140) {
            min(0.95f, 0.45f + (edgeMean - 14.0).toFloat() / 80.0f + strongRate.toFloat() * 2.5f)
        } else 0f
        if (docScore >= 0.5f) results += ClassifiedLabel("مستند / ملف نصي", docScore)

        // لقطة شاشة: ألوان مسطّحة، حواف ضعيفة، عدد ألوان مميزة صغير، تباين لوني بسيط
        val screenshotScore = if (edgeMean < 8.0 && meanSat > 0.02f) 0.6f else 0f
        if (screenshotScore > 0f) results += ClassifiedLabel("لقطة شاشة", screenshotScore)

        // طبيعة: نسبة خضرة عالية أو زرقة مع تشبّع جيد
        if (greenRatio > 0.35f || blueRatio > 0.45f) {
            val natureScore = min(0.9f, (max(greenRatio, blueRatio) - 0.3f) * 1.5f + 0.3f)
            when {
                greenRatio > blueRatio -> results += ClassifiedLabel("طبيعة / حديقة", natureScore)
                else -> results += ClassifiedLabel("سماء / ماء", natureScore)
            }
        }

        // داخلي دافئ: ألوان دافئة متفشية وتباين سطوح معتدل
        if (warmRatio > 0.45f && chroma > 0.1f) {
            results += ClassifiedLabel("داخلي / ضوء دافئ", min(0.75f, warmRatio * 1.2f))
        }

        // أبيض وأسود: تشبّع شبه معدوم على كامل الصورة
        if (meanSat < 0.04f) results += ClassifiedLabel("أبيض وأسود / تدرج رمادي", 0.75f)

        // إضاءة منخفضة: سطوع متوسط منخفض جدًا (صور ليلية)
        if (meanLum < 60f) results += ClassifiedLabel("إضاءة منخفضة / ليلية", 0.6f)

        // وجوه — إشارة قوية من ML Kit إن وُجدت
        if (facesDetected > 0) {
            results += ClassifiedLabel(
                if (facesDetected == 1) "صورة شخص / وجه" else "مجموعة أشخاص",
                min(0.9f, 0.55f + facesDetected * 0.12f)
            )
        }

        return results.sortedByDescending { it.confidence }
    }

    /** فك ترميز مصغّر مع فحص الأبعاد أولًا — حماية من نفاد الذاكرة */
    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > ANALYSIS_DIMENSION) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}