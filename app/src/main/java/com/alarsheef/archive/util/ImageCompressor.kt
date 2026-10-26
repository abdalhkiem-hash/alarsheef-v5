package com.alarsheef.archive.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * ضغط الصورة عند الأرشفة: WebP بجودة [QUALITY] والضلع الأقصر لا يتجاوز [MAX_SHORT_EDGE] بكسل.
 * الضلع الأقصر لا الأطول: لقطات الشاشة الطويلة (1800×4000) كانت ستُصغَّر إلى عرض ~900 لو حُدّ الأطول.
 * لا تكبير أبدًا، ولا يُستبدل الأصل إلا لو صار أصغر بوضوح ([MIN_SAVING]).
 * بصمة التكرار تُحسب على الأصل قبل الضغط، فلا يتغيّر اكتشاف المكرر.
 */
object ImageCompressor {

    private const val TAG = "ImageCompressor"
    const val MAX_SHORT_EDGE = 1600
    const val QUALITY = 85

    /** لا يُستبدل الأصل إلا لو كان المضغوط أصغر منه بهذه النسبة على الأقل. */
    private const val MIN_SAVING = 0.10

    /** صيغ لا تُضغط: متحركة أو غير قابلة للفك بأمان. */
    private val SKIPPED_EXTENSIONS = setOf("gif", "webp")

    class Result(val file: File, val extension: String)

    /** الحجم الهدف (عرض، ارتفاع) بحيث لا يتجاوز الضلع الأقصر [maxShort]، بلا تكبير. */
    fun targetSize(width: Int, height: Int, maxShort: Int = MAX_SHORT_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val short = minOf(width, height)
        if (short <= maxShort) return width to height
        val scale = maxShort.toDouble() / short
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** أكبر قوة للعدد 2 تُبقي الضلع الأقصر المفكوك ≥ [maxShort] (فك أصغر ثم تحجيم دقيق). */
    fun sampleSizeFor(width: Int, height: Int, maxShort: Int = MAX_SHORT_EDGE): Int {
        val short = minOf(width, height)
        var sample = 1
        while (short / (sample * 2) >= maxShort) sample *= 2
        return sample
    }

    /** يقرّر هل يستحق المضغوط استبدال الأصل. */
    fun worthKeeping(originalBytes: Long, compressedBytes: Long): Boolean =
        compressedBytes > 0 && compressedBytes <= originalBytes * (1.0 - MIN_SAVING)

    /**
     * يضغط [source] إلى ملف مؤقت في الكاش، أو يعيد null لتُحفظ الصورة الأصلية كما هي
     * (صيغة مستثناة، فك فاشل، أو لا توفير يُذكر). على المستدعي حذف الملف المؤقت.
     */
    fun compress(context: Context, source: File): Result? {
        if (FileUtils.extensionOf(source.name) in SKIPPED_EXTENSIONS) return null
        var decoded: Bitmap? = null
        var finalBitmap: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null

            val (tw, th) = targetSize(bounds.outWidth, bounds.outHeight)
            val matrix = Matrix()
            if (decoded.width != tw || decoded.height != th) {
                matrix.postScale(tw.toFloat() / decoded.width, th.toFloat() / decoded.height)
            }
            matrix.postRotate(exifRotation(source))
            finalBitmap = if (matrix.isIdentity) decoded else
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

            val out = File(context.cacheDir, "cmp_${System.nanoTime()}.webp")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            val ok = out.outputStream().use { finalBitmap.compress(format, QUALITY, it) }
            if (!ok || !worthKeeping(source.length(), out.length())) {
                runCatching { out.delete() }
                return null
            }
            return Result(out, "webp")
        } catch (e: IOException) {
            Log.w(TAG, "تعذّر الضغط، تُحفظ الصورة كما هي: ${e.message}")
            return null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "ذاكرة غير كافية للضغط، تُحفظ الصورة كما هي")
            return null
        } finally {
            if (finalBitmap != null && finalBitmap !== decoded) finalBitmap.recycle()
            decoded?.recycle()
        }
    }

    /** درجة التدوير من EXIF، حتى لا تفقد صور الكاميرا اتجاهها بعد إعادة الترميز (التدوير لا يُحفظ في WebP). */
    private fun exifRotation(file: File): Float = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: IOException) {
        0f
    }
}
