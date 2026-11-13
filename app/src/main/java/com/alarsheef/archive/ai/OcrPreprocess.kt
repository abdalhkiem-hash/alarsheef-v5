package com.alarsheef.archive.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * تحضير الصورة لمحرك OCR: رمادي، تكبير لدقة مناسبة، تمديد التباين، أبيض وأسود تكيّفي
 * (يتحمل الظل والإضاءة غير المتساوية)، وتصحيح ميل السطور. كلها على البكسلات مباشرة بلا مكتبات.
 */
object OcrPreprocess {

    /** صورة رمادية: بكسل لكل بايت (0 = أسود، 255 = أبيض). */
    class Gray(val width: Int, val height: Int, val px: ByteArray) {
        fun at(x: Int, y: Int): Int = px[y * width + x].toInt() and 0xFF
    }

    fun toGray(src: Bitmap): Gray {
        val w = src.width
        val h = src.height
        val argb = IntArray(w * h)
        src.getPixels(argb, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in argb.indices) {
            val c = argb[i]
            val l = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).roundToInt()
            out[i] = l.coerceIn(0, 255).toByte()
        }
        return Gray(w, h, out)
    }

    /** يكبّر أو يصغّر بحيث يصير أطول ضلع [targetLong] بكسل (التكبير بتنعيم ثنائي الخطية). */
    fun scaleLongSide(src: Bitmap, targetLong: Int): Bitmap {
        val long = max(src.width, src.height)
        if (long == targetLong || long == 0) return src
        val k = targetLong.toFloat() / long
        return Bitmap.createScaledBitmap(
            src.copy(Bitmap.Config.ARGB_8888, false),
            max(1, (src.width * k).roundToInt()),
            max(1, (src.height * k).roundToInt()),
            true
        )
    }

    /** يمدّ التباين بين المئين [lowPct] و[highPct]. يعدّل الصورة نفسها. */
    fun stretchContrast(g: Gray, lowPct: Float = 0.02f, highPct: Float = 0.98f): Gray {
        val hist = IntArray(256)
        for (b in g.px) hist[b.toInt() and 0xFF]++
        val total = g.px.size
        var acc = 0
        var lo = 0
        while (lo < 255 && acc + hist[lo] < total * lowPct) { acc += hist[lo]; lo++ }
        acc = 0
        var hi = 255
        while (hi > 0 && acc + hist[hi] < total * (1 - highPct)) { acc += hist[hi]; hi-- }
        if (hi - lo < 20) return g
        val scale = 255f / (hi - lo)
        for (i in g.px.indices) {
            val v = ((g.px[i].toInt() and 0xFF) - lo) * scale
            g.px[i] = v.roundToInt().coerceIn(0, 255).toByte()
        }
        return g
    }

    /** أبيض وأسود بعتبة محلية (طريقة Bradley): البكسل أسود إن كان أغمق من متوسط جواره بنسبة [t]. */
    fun adaptiveBinarize(g: Gray, windowFraction: Float = 0.125f, t: Float = 0.14f): Gray {
        val w = g.width
        val h = g.height
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += g.at(x, y)
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val half = max(8, (min(w, h) * windowFraction / 2).roundToInt())
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - half)
            val y1 = min(h - 1, y + half)
            for (x in 0 until w) {
                val x0 = max(0, x - half)
                val x1 = min(w - 1, x + half)
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (w + 1) + (x1 + 1)] - integral[y0 * (w + 1) + (x1 + 1)] -
                    integral[(y1 + 1) * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val black = g.at(x, y) * count < sum * (1f - t)
                out[y * w + x] = if (black) 0 else 255.toByte()
            }
        }
        return Gray(w, h, out)
    }

    /**
     * زاوية ميل السطور بالدرجات (موجبة = السطر يميل لأسفل يمينًا). طريقة مسقط الصفوف:
     * عند الدوران الصحيح تتكدّس البكسلات السوداء في صفوف قليلة ويكبر تباين المسقط.
     * @param binary صورة أبيض وأسود (الحبر = 0).
     */
    fun estimateSkewDegrees(binary: Gray, range: Float = 25f): Float {
        val step = max(1, max(binary.width, binary.height) / 700)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        var y = 0
        while (y < binary.height) {
            var x = 0
            while (x < binary.width) {
                if (binary.at(x, y) == 0) { xs.add(x / step); ys.add(y / step) }
                x += step
            }
            y += step
        }
        if (xs.size < 60) return 0f
        fun score(deg: Float): Double {
            val r = Math.toRadians(deg.toDouble())
            val c = cos(r)
            val s = sin(r)
            val maxY = (binary.width / step * abs(s) + binary.height / step * abs(c)).toInt() + 4
            val bins = IntArray(maxY * 2 + 8)
            val shift = maxY + 2
            for (i in xs.indices) {
                val yy = (ys[i] * c - xs[i] * s).roundToInt() + shift
                if (yy in bins.indices) bins[yy]++
            }
            var sumSq = 0.0
            for (b in bins) sumSq += b.toDouble() * b
            return sumSq
        }
        var best = 0f
        var bestScore = score(0f)
        var d = -range
        while (d <= range) {
            val sc = score(d)
            if (sc > bestScore) { bestScore = sc; best = d }
            d += 1f
        }
        val coarse = best
        var f = coarse - 1f
        while (f <= coarse + 1f) {
            val sc = score(f)
            if (sc > bestScore) { bestScore = sc; best = f }
            f += 0.25f
        }
        return best
    }

    /** يدير الصورة [degrees] حول مركزها على خلفية بيضاء، ويوسّع اللوحة كي لا يُقص شيء. */
    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (abs(degrees) < 0.3f) return src
        val rad = Math.toRadians(degrees.toDouble())
        val nw = (abs(src.width * cos(rad)) + abs(src.height * sin(rad))).roundToInt() + 8
        val nh = (abs(src.width * sin(rad)) + abs(src.height * cos(rad))).roundToInt() + 8
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val m = Matrix()
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postRotate(degrees)
        m.postTranslate(nw / 2f, nh / 2f)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun toBitmap(g: Gray): Bitmap {
        val argb = IntArray(g.width * g.height)
        for (i in argb.indices) {
            val v = g.px[i].toInt() and 0xFF
            argb[i] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(argb, g.width, g.height, Bitmap.Config.ARGB_8888)
    }

    /** خطوات التحضير الممكنة، تُجمع بحسب الحاجة. */
    data class Options(
        val longSide: Int = 0,
        val contrast: Boolean = false,
        val binarize: Boolean = false,
        val deskew: Boolean = false,
    )

    /** يطبّق [options] بالترتيب: تكبير ← رمادي/تباين ← تصحيح ميل ← أبيض وأسود. */
    fun apply(src: Bitmap, options: Options): Bitmap {
        var bmp = if (options.longSide > 0) scaleLongSide(src, options.longSide) else src
        var gray = toGray(bmp)
        if (options.contrast) gray = stretchContrast(gray)
        if (options.deskew) {
            val probe = adaptiveBinarize(gray)
            val angle = estimateSkewDegrees(probe)
            if (abs(angle) >= 0.3f) {
                bmp = rotate(toBitmap(gray), -angle)
                gray = toGray(bmp)
            }
        }
        if (options.binarize) gray = adaptiveBinarize(gray)
        return toBitmap(gray)
    }
}
