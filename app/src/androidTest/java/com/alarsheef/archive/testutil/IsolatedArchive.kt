package com.alarsheef.archive.testutil

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alarsheef.archive.data.AppDatabase
import com.alarsheef.archive.data.repository.ArchiveRepository
import java.io.File
import java.util.Calendar

/**
 * أرشيف معزول للاختبار: قاعدة بالذاكرة + مجلدا ملفات/كاش مؤقتان، فلا تُلمس بيانات
 * التطبيق على الجهاز الذي تُشغَّل عليه الاختبارات. استدعِ [close] في @After.
 */
class IsolatedArchive(tag: String) {

    /** يحوّل filesDir/cacheDir إلى مجلد مؤقت ويمنع الرجوع لسياق التطبيق الحقيقي. */
    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    val root: File = File(appContext.cacheDir, "${tag}_${System.nanoTime()}").apply { mkdirs() }
    val context: Context = IsolatedContext(appContext, root)
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val repository = ArchiveRepository(context, db)

    /** ملف مصدر مؤقت بمحتوى نصي معروف. */
    fun source(name: String, content: String): File =
        File(context.cacheDir, name).apply { writeBytes(content.toByteArray()) }

    /** عدد الملفات الفعلية تحت مجلد الأرشيف على القرص. */
    fun archivedFileCount(): Int =
        File(context.filesDir, "archive").walkTopDown().count { it.isFile }

    fun close() {
        db.close()
        root.deleteRecursively()
    }

    companion object {
        fun noon(year: Int, month: Int, day: Int): Long =
            Calendar.getInstance().apply { clear(); set(year, month - 1, day, 12, 0, 0) }.timeInMillis
    }
}
