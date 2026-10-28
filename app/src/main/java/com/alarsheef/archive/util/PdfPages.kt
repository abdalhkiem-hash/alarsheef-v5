package com.alarsheef.archive.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.roundToInt

/** صفحة من ملف PDF مؤرشف. تُرسم عند العرض فقط ولا تُخزَّن صورة منها. */
data class PdfPage(val file: File, val index: Int)

object PdfPages {

    private const val MIN_WIDTH = 200
    private const val MAX_WIDTH = 2400
    private const val MAX_HEIGHT = 4200

    fun isPdf(path: String): Boolean = path.endsWith(".pdf", ignoreCase = true)

    fun count(file: File): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (e: Exception) {
        0
    }

    /** يرسم الصفحة [index] بعرض [targetWidth] (مقيَّدًا) على خلفية بيضاء. null إن تعذّر. */
    fun render(file: File, index: Int, targetWidth: Int): Bitmap? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (index !in 0 until renderer.pageCount) return@use null
                renderer.openPage(index).use { page ->
                    var width = targetWidth.coerceIn(MIN_WIDTH, MAX_WIDTH)
                    var height = (width.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                    if (height > MAX_HEIGHT) {
                        width = (width.toFloat() * MAX_HEIGHT / height).roundToInt().coerceAtLeast(1)
                        height = MAX_HEIGHT
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}
