package com.alarsheef.archive.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

object PdfToImageConverter {

    private const val TAG = "PdfToImage"

    /**
     * دقة التصيير النهائية: 200 نقطة في البوصة — تكفي بوضوح للقراءة والـ OCR،
     * وتخفّض ذاكرة الصفحة من ≈61 إلى ≈15 ميجابايت لورقة A4 فتقل أخطاء OOM
     * التي كانت تحذف صفحات بصمت.
     */
    private const val TARGET_DPI = 200

    /**
     * سقف أمان لأبعاد الصفحة الواحدة (بكسل بالضلع الأطول). يحمي فقط من الصفحات
     * الشاذة أو الملفات التالفة؛ لا يؤثر على أي حجم ورق طبيعي عند 200 DPI.
     */
    private const val MAX_DIMENSION = 4000

    /** سقف عدد الصفحات للملف الواحد، حماية من ملفات PDF الضخمة */
    private const val MAX_PAGES = 100

    /**
     * يحوّل كل صفحة من ملف PDF إلى صورة PNG مستقلة ويحفظها كملفات مؤقتة
     * في مساحة الكاش لحين استيرادها للأرشيف. الملف الأصلي (PDF) لا يُلمس.
     *
     * نستخدم ARGB_8888 — وليس RGB_565 — لأن PdfRenderer.render() يشترطه
     * ويرفض غيره برسالة Unsupported pixel format. صفحات المستندات لا تحتاج
     * قناة شفافية، لكن دعم النظام للنوع مفروض؛ يُعوَّض استهلاك الذاكرة
     * (≈ 15 ميجابايت لصفحة A4 عند 200 DPI) بتصيير صفحة واحدة في كل دورة
     * وبتقليص الممتد فوق سقف الحد الأقصى الآمن.
     */
    fun convertToPngPages(context: Context, pdfFile: File): List<File> {
        val results = mutableListOf<File>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)

            if (renderer.pageCount > MAX_PAGES) {
                Log.w(TAG, "PDF ${pdfFile.name}: ${renderer.pageCount} صفحة — ستُعالَج أول $MAX_PAGES فقط")
            }
            val pageCount = min(renderer.pageCount, MAX_PAGES)
            for (i in 0 until pageCount) {
                var bitmap: Bitmap? = null
                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(i)
                    var widthPx = (page.width / 72f * TARGET_DPI).roundToInt().coerceAtLeast(1)
                    var heightPx = (page.height / 72f * TARGET_DPI).roundToInt().coerceAtLeast(1)

                    val largest = maxOf(widthPx, heightPx)
                    if (largest > MAX_DIMENSION) {
                        val ratio = MAX_DIMENSION.toFloat() / largest
                        widthPx = (widthPx * ratio).roundToInt().coerceAtLeast(1)
                        heightPx = (heightPx * ratio).roundToInt().coerceAtLeast(1)
                    }

                    bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    // nanoTime بدل currentTimeMillis: صفحات من ملفين في نفس اللترية
                    // لا تمحو بعضها (قد يتقاسم currentTimeMillis نفس القيمة).
                    val outFile = File(context.cacheDir, "pdfpage_${System.nanoTime()}_$i.png")
                    FileOutputStream(outFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    results.add(outFile)
                } catch (e: OutOfMemoryError) {
                    // صفحة كبيرة جدًا — نتخطاها ونكمل بالباقي
                    Log.w(TAG, "OOM لتصيير الصفحة $i: ${e.message}")
                } catch (e: Exception) {
                    // صفحة تالفة — نتخطاها ونكمل بالباقي
                    Log.w(TAG, "فشل تصيير الصفحة $i من ${pdfFile.name}: ${e.message}")
                } finally {
                    runCatching { page?.close() }
                    bitmap?.recycle()
                }
            }
        } catch (e: Exception) {
            // ملف PDF تالف أو محمي — نرجع بما نجح منه
            Log.w(TAG, "تعذر فتح PDF ${pdfFile.name}: ${e.message}")
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }

        return results
    }
}
