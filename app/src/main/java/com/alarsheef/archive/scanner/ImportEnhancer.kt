package com.alarsheef.archive.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * معالجات صور الاستيراد — محلية بالكامل (بلا إنترنت وبلا OpenCV):
 *
 * 1) [cropDocument]: كشف حدود الورقة عبر عتبة Otsu على الرمادي ثم إيجاد
 *    أربع زوايا للمنطقة الفاتحة (TL/TR/BR/BL بدقائق x±y)، وقصّ منظوري
 *    عبر Matrix.setPolyToPoly. يرجع null إذا لم توجد ورقة واضحة
 *    (صورة عادية/لقطة شاشة) فلا يُطبَّق أي قصّ.
 *
 * 2) [enhance]: تحسين تلقائي بتمديد المدى الطيفي لكل قناة بين
 *    المئين 1% و99% (يشبه Levels/Auto) — يخفّف الظل ويوازن الأبيض.
 *    القناة ذات المدى الصغير (< 48) تُترك كما هي لتفادي تضخيم الضجيج.
 */
object ImportEnhancer {

    /** دقة تحليل الحواف (تكفي لاكتشاف الورقة وسريعة). */
    private const val ANALYSIS_MAX = 1024

    /** سقف مقاس المخرجات بعد التصحيح (يمنع OOM على 108MP). */
    private const val OUTPUT_MAX = 4096

    /** أدنى مدى طيفي يُسمح بتمديده. */
    private const val MIN_STRETCH_RANGE = 48

    // ---------- قصّ حدود المستند ----------

    fun cropDocument(src: Bitmap): Bitmap? {
        return try {
        val sw = src.width
        val sh = src.height
        if (sw < 64 || sh < 64) return null

        val scale = min(1f, ANALYSIS_MAX / max(sw, sh).toFloat())
        val w = max(8, (sw * scale).toInt())
        val h = max(8, (sh * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        // حساب الرمادي وهيستوغرام التدرّجات
        val gray = IntArray(w * h)
        val hist = IntArray(256)
        for (i in pixels.indices) {
            val c = pixels[i]
            val g = (306 * ((c shr 16) and 0xFF) + 601 * ((c shr 8) and 0xFF) + 114 * (c and 0xFF)) shr 8
            gray[i] = g
            hist[g]++
        }

        val total = w * h
        val thr = otsuThreshold(hist, total)
        var brightCount = 0
        for (i in gray.indices) if (gray[i] > thr) brightCount++
        val brightRatio = brightCount.toDouble() / total
        // صورة فاتحة بالكامل (لقطة/مستند أبيض ملء الإطار) → لا قصّ
        if (brightRatio < 0.10 || brightRatio > 0.93) return null

        // الزوايا الأربع للمنطقة الفاتحة
        var tlI = -1; var trI = -1; var brI = -1; var blI = -1
        var tlS = Int.MAX_VALUE; var trS = Int.MIN_VALUE
        var brS = Int.MIN_VALUE; var blS = Int.MAX_VALUE
        for (i in gray.indices) {
            if (gray[i] <= thr) continue
            val x = i % w
            val y = i / w
            val s1 = x + y
            val s2 = x - y
            if (s1 < tlS) { tlS = s1; tlI = i }
            if (s1 > brS) { brS = s1; brI = i }
            if (s2 > trS) { trS = s2; trI = i }
            if (s2 < blS) { blS = s2; blI = i }
        }
        if (tlI < 0 || trI < 0 || brI < 0 || blI < 0) return null
        if (tlI == trI || tlI == brI || tlI == blI || trI == brI || trI == blI || brI == blI) return null

        fun px(i: Int): Pair<Float, Float> = (i % w) * (sw / w.toFloat()) to (i / w) * (sh / h.toFloat())
        val (tlX, tlY) = px(tlI)
        val (trX, trY) = px(trI)
        val (brX, brY) = px(brI)
        val (blX, blY) = px(blI)

        // مساحة الشبه المنحرف (بالتناسب مع مساحة الصورة) — رفض الأشكال الطافية
        val area2 = abs(
            (tlX * trY - trX * tlY) + (trX * brY - brX * trY) +
                (brX * blY - blX * brY) + (blX * tlY - tlX * blY)
        )
        if (area2 / 2f < 0.10f * sw * sh) return null

        // مخرجات من نسب الشبه المنحرف، بسقف OUTPUT_MAX
        val minX = min(min(tlX, trX), min(brX, blX))
        val maxX = max(max(tlX, trX), max(brX, blX))
        val minY = min(min(tlY, trY), min(brY, blY))
        val maxY = max(max(tlY, trY), max(blY, blY))
        if (maxX - minX < 32f || maxY - minY < 32f) return null
        var outW = (maxX - minX).toInt()
        var outH = (maxY - minY).toInt()
        val shrink = max(outW, outH).toFloat() / OUTPUT_MAX
        if (shrink > 1f) { outW = (outW / shrink).toInt(); outH = (outH / shrink).toInt() }
        if (outW < 16 || outH < 16) return null

        val matrix = Matrix()
        val srcPts = floatArrayOf(tlX, tlY, trX, trY, brX, brY, blX, blY)
        val dstPts = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return null

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.concat(matrix)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        out
    } catch (e: Throwable) {
        null
    }
    }

    /** عتبة Otsu الكلاسيكية على هيستوغرام الرمادي. */
    private fun otsuThreshold(hist: IntArray, total: Int): Int {
        var sum = 0
        for (t in 0..255) sum += t * hist[t]
        var sumB = 0
        var wB = 0
        var best = 0.0
        var thr = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        return thr
    }

    // ---------- التحسين التلقائي ----------

    fun enhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return src
        val step = max(1, max(w, h) / 512)

        val rHist = IntArray(256)
        val gHist = IntArray(256)
        val bHist = IntArray(256)
        var sampled = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = src.getPixel(x, y)
                rHist[(c shr 16) and 0xFF]++
                gHist[(c shr 8) and 0xFF]++
                bHist[c and 0xFF]++
                sampled++
                x += step
            }
            y += step
        }
        if (sampled < 64) return src

        fun bounds(hist: IntArray): Pair<Float, Float> {
            val loTarget = (sampled * 0.01).toInt().coerceAtLeast(1)
            val hiTarget = (sampled * 0.99).toInt().coerceAtLeast(1)
            var acc = 0
            var lo = 0
            for (t in 0..255) { acc += hist[t]; if (acc >= loTarget) { lo = t; break } }
            acc = 0
            var hi = 255
            for (t in 0..255) { acc += hist[t]; if (acc >= hiTarget) { hi = t; break } }
            return lo.toFloat() to hi.toFloat()
        }

        fun channelScale(lo: Float, hi: Float): Pair<Float, Float> {
            val range = hi - lo
            if (range < MIN_STRETCH_RANGE) return 1f to 0f
            val s = 255f / range
            return s to -lo * s
        }

        val (rLo, rHi) = bounds(rHist)
        val (gLo, gHi) = bounds(gHist)
        val (bLo, bHi) = bounds(bHist)
        val (sr, or_) = channelScale(rLo, rHi)
        val (sg, og) = channelScale(gLo, gHi)
        val (sb, ob) = channelScale(bLo, bHi)

        val matrix = ColorMatrix(
            floatArrayOf(
                sr, 0f, 0f, 0f, or_,
                0f, sg, 0f, 0f, og,
                0f, 0f, sb, 0f, ob,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
