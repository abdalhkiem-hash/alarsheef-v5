package com.alarsheef.archive.util

import com.alarsheef.archive.data.entities.ArchivedImage

/** نوع المشاركة: PDF وحده application/pdf، وخليط صور وPDF نوع عام، والصور فقط image. */
object ShareMime {
    fun of(images: List<ArchivedImage>): String {
        val pdfs = images.count { PdfPages.isPdf(it.storedPath) }
        return when {
            pdfs == 0 -> "image/*"
            pdfs == images.size -> "application/pdf"
            else -> "*/*"
        }
    }
}
