package com.alarsheef.archive.util

import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.map.Mapper
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** ملف PDF يُعرض بصفحته الأولى، فتعمل `AsyncImage(model = File(...))` كما هي في المصغّرات. */
class PdfFileMapper : Mapper<File, PdfPage> {
    override fun map(data: File, options: Options): PdfPage? =
        if (PdfPages.isPdf(data.path)) PdfPage(data, 0) else null
}

class PdfPageKeyer : Keyer<PdfPage> {
    // الحجم المطلوب جزء من المفتاح: مصغّر صغير في الذاكرة لا يُعاد استخدامه في العارض الكبير
    override fun key(data: PdfPage, options: Options): String {
        val width = (options.size.width as? Dimension.Pixels)?.px ?: 0
        return "pdf:${data.file.path}:${data.file.lastModified()}:${data.index}:$width"
    }
}

class PdfPageFetcher(
    private val data: PdfPage,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val width = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_WIDTH
        val bitmap = withContext(Dispatchers.IO) { PdfPages.render(data.file, data.index, width) }
            ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<PdfPage> {
        override fun create(data: PdfPage, options: Options, imageLoader: ImageLoader): Fetcher =
            PdfPageFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_WIDTH = 1080
    }
}
