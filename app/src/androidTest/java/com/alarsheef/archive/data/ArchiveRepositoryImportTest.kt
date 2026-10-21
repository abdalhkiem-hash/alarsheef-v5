package com.alarsheef.archive.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.ImportResult
import com.alarsheef.archive.scanner.ImportRules
import com.alarsheef.archive.util.ArchiveNaming
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/**
 * مسار الاستيراد الفعلي [ArchiveRepository.importFile]: منع التكرار بالبصمة، مطالبة
 * المسار الأصلي، مجلد التاريخ، التسمية، وتنظيف المصدر.
 *
 * معزول تمامًا عن بيانات التطبيق: قاعدة بالذاكرة + مجلدا ملفات/كاش مؤقتان،
 * فلا يُلمس أرشيف المستخدم على الجهاز الذي تُشغَّل عليه الاختبارات.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveRepositoryImportTest {

    /** يحوّل filesDir/cacheDir إلى مجلد مؤقت ويمنع الرجوع لسياق التطبيق الحقيقي. */
    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ArchiveRepository

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        root = File(appContext.cacheDir, "repo_import_test_${System.nanoTime()}").apply { mkdirs() }
        context = IsolatedContext(appContext, root)
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ArchiveRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun source(name: String, content: String): File =
        File(context.cacheDir, name).apply { writeBytes(content.toByteArray()) }

    private fun noon(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, 12, 0, 0) }.timeInMillis

    private suspend fun rows(): List<ArchivedImage> = db.archivedImageDao().getAllOnce()

    // ---------- استيراد جديد ----------

    @Test
    fun newFile_isAdded_withAllMetadata() = runBlocking {
        val src = source("a.jpg", "content-a")
        val result = repository.importFile(
            sourceFile = src, sourceApp = SourceApp.WHATSAPP, countDuplicate = false,
            originalPath = "WhatsApp/Media/WhatsApp Images/a.jpg",
            originalDate = noon(2024, 2, 29),
            contentVerified = ContentVerified.ACCEPTED,
            originalName = "a.jpg",
        )
        assertEquals(ImportResult.Added, result)

        val row = rows().single()
        assertEquals(SourceApp.WHATSAPP, row.sourceApp)
        assertEquals("WhatsApp/Media/WhatsApp Images/a.jpg", row.originalPath)
        assertEquals("a.jpg", row.originalName)
        assertEquals(ContentVerified.ACCEPTED, row.contentVerified)
        assertEquals(1, row.receivedCount)
        assertEquals(64, row.contentHash.length)
        assertFalse(row.nameLocked)
        assertTrue("الملف المؤرشف موجود على القرص", File(row.storedPath).exists())
        assertEquals("content-a", File(row.storedPath).readText())
    }

    @Test
    fun file_isPlacedInFolderOfItsOriginalDate_notImportDate() = runBlocking {
        repository.importFile(source("a.jpg", "leap"), SourceApp.GALLERY, originalDate = noon(2024, 2, 29))
        val row = rows().single()
        assertEquals(2024, row.year)
        assertEquals(2, row.month)
        assertEquals(29, row.day)
        assertEquals(noon(2024, 2, 29), row.capturedAt)
        assertEquals(
            File(context.filesDir, "archive/2024/02/29/${row.fileName}").absolutePath,
            row.storedPath
        )
    }

    @Test
    fun unknownOriginalDate_fallsBackToFileModificationTime() = runBlocking {
        val src = source("a.jpg", "old")
        assertTrue(src.setLastModified(noon(2023, 7, 15)))
        repository.importFile(src, SourceApp.GALLERY, originalDate = 0L)
        val row = rows().single()
        assertEquals(2023, row.year)
        assertEquals(7, row.month)
        assertEquals(15, row.day)
    }

    @Test
    fun sourceTempFile_isDeletedByDefault_andKeptOnRequest() = runBlocking {
        val deleted = source("a.jpg", "one")
        repository.importFile(deleted, SourceApp.GALLERY)
        assertFalse(deleted.exists())

        val kept = source("b.jpg", "two")
        repository.importFile(kept, SourceApp.GALLERY, deleteSourceAfterImport = false)
        assertTrue(kept.exists())
    }

    // ---------- التسمية ----------

    @Test
    fun defaultName_isAutoImgName_withSourceExtension() = runBlocking {
        repository.importFile(source("x.png", "png-bytes"), SourceApp.GALLERY, originalDate = noon(2024, 2, 29))
        val name = rows().single().fileName
        assertTrue(name, name.startsWith("IMG_20240229_"))
        assertTrue(name, name.endsWith(".png"))
        assertTrue(name, ArchiveNaming.isAutoName(name))
    }

    @Test
    fun namePrefix_fromContentGate_isUsedAndStaysRenamable() = runBlocking {
        repository.importFile(
            source("x.jpg", "invoice"), SourceApp.WHATSAPP,
            originalDate = noon(2024, 2, 29), namePrefix = "فاتورة",
        )
        val name = rows().single().fileName
        assertTrue(name, name.startsWith("فاتورة_20240229_"))
        assertTrue(name, ArchiveNaming.isAutoName(name))
        assertEquals("فاتورة", ArchiveNaming.labelOf(name))
    }

    @Test
    fun preferredBaseName_isSanitized_andLocked() = runBlocking {
        repository.importFile(
            source("scan.jpg", "scan"), SourceApp.DOCUMENT_SCAN, preferredBaseName = "عقد/المحل",
        )
        val row = rows().single()
        assertEquals("عقد_المحل.jpg", row.fileName)
        assertTrue(row.nameLocked)
    }

    @Test
    fun fileWithoutExtension_getsJpg() = runBlocking {
        repository.importFile(source("noext", "raw"), SourceApp.GALLERY)
        assertTrue(rows().single().fileName.endsWith(".jpg"))
    }

    @Test
    fun filesImportedInSameInstant_getDistinctNames() = runBlocking {
        repeat(5) { i ->
            repository.importFile(source("f$i.jpg", "burst-$i"), SourceApp.GALLERY, originalDate = noon(2024, 2, 29))
        }
        val all = rows()
        assertEquals(5, all.size)
        assertEquals(5, all.map { it.fileName }.toSet().size)
        assertEquals(5, all.map { it.storedPath }.toSet().size)
        assertTrue(all.all { File(it.storedPath).exists() })
    }

    // ---------- منع التكرار ----------

    @Test
    fun sameContentFromAnotherPath_isDuplicate_notSecondRow() = runBlocking {
        repository.importFile(source("a.jpg", "same"), SourceApp.WHATSAPP, countDuplicate = false,
            originalPath = "WhatsApp/a.jpg")
        val dup = source("copy.jpg", "same")
        val result = repository.importFile(dup, SourceApp.DOWNLOADS, countDuplicate = false,
            originalPath = "Download/copy.jpg")

        assertEquals(ImportResult.Duplicate(1), result)
        val row = rows().single()
        assertEquals("الفحص التلقائي لا يضخّم العدّاد", 1, row.receivedCount)
        assertEquals(SourceApp.WHATSAPP, row.sourceApp)
        assertFalse("المصدر المؤقت المكرر يُنظَّف", dup.exists())
        // ملف واحد فقط على القرص
        assertEquals(1, File(context.filesDir, "archive").walkTopDown().count { it.isFile })
    }

    @Test
    fun manualDuplicate_incrementsReceivedCount() = runBlocking {
        repository.importFile(source("a.jpg", "same"), SourceApp.MANUAL_IMPORT)
        val result = repository.importFile(source("b.jpg", "same"), SourceApp.MANUAL_IMPORT, countDuplicate = true)
        assertEquals(ImportResult.Duplicate(2), result)
        assertEquals(2, rows().single().receivedCount)
    }

    @Test
    fun duplicate_claimsOriginalPath_whenExistingRowHasNone() = runBlocking {
        // أُضيف يدويًا (بلا مسار) ثم اكتشفه الفحص التلقائي في مصدره
        repository.importFile(source("a.jpg", "same"), SourceApp.MANUAL_IMPORT)
        assertNull(rows().single().originalPath)

        repository.importFile(source("b.jpg", "same"), SourceApp.GALLERY, countDuplicate = false,
            originalPath = "DCIM/Camera/a.jpg")
        assertEquals("DCIM/Camera/a.jpg", rows().single().originalPath)
    }

    @Test
    fun duplicate_neverOverwritesExistingOriginalPath() = runBlocking {
        repository.importFile(source("a.jpg", "same"), SourceApp.WHATSAPP, originalPath = "WhatsApp/a.jpg")
        repository.importFile(source("b.jpg", "same"), SourceApp.DOWNLOADS, countDuplicate = false,
            originalPath = "Download/a.jpg")
        assertEquals("WhatsApp/a.jpg", rows().single().originalPath)
    }

    @Test
    fun importedPath_feedsFastPathGate_soNextScanSkipsTheFile() = runBlocking {
        val path = ImportRules.originalPathOf("Download/", "invoice.jpg")
        repository.importFile(source("t.jpg", "inv"), SourceApp.DOWNLOADS, originalPath = path)
        val gate = ImportRules.normalizeExistingPaths(repository.findAllOriginalPaths())
        assertTrue(path in gate)
    }

    @Test
    fun pdfPages_areStoredPerPage_andWholeFileIsRecognizedNextScan() = runBlocking {
        val base = ImportRules.originalPathOf("Download/", "contract.pdf")
        // الصفحة 1 مكررة مع ملف موجود (له مسار آخر) فلا تُخزَّن هويتها
        repository.importFile(source("existing.png", "page-1"), SourceApp.WHATSAPP, originalPath = "WhatsApp/x.png")
        for (page in 1..3) {
            repository.importFile(
                source("p$page.png", "page-$page"), SourceApp.DOWNLOADS, countDuplicate = false,
                originalPath = ImportRules.pageIdentity(base, page),
                originalName = ImportRules.pageDisplayName("contract.pdf", page),
            )
        }
        assertEquals(3, rows().size)
        val stored = repository.findAllOriginalPaths()
        assertFalse("هوية الصفحة الأولى لم تُخزَّن", "$base#page=1" in stored)
        assertFalse(base in stored)
        assertTrue(
            "صفحاته الباقية تُثبت الملف في بوابة المسارات",
            base in ImportRules.normalizeExistingPaths(stored)
        )
        assertEquals("contract.pdf (ص2)", rows().first { it.originalPath == "$base#page=2" }.originalName)
        assertEquals("contract.pdf (ص3)", rows().first { it.originalPath == "$base#page=3" }.originalName)
    }

    // ---------- فشل ----------

    @Test
    fun emptyFile_fails_withoutRowOrOrphan() = runBlocking {
        val empty = File(context.cacheDir, "empty.jpg").apply { createNewFile() }
        assertEquals(ImportResult.Failed, repository.importFile(empty, SourceApp.GALLERY))
        assertTrue(rows().isEmpty())
        assertEquals(0, File(context.filesDir, "archive").walkTopDown().count { it.isFile })
    }

    @Test
    fun missingFile_fails() = runBlocking {
        val missing = File(context.cacheDir, "ghost.jpg")
        assertEquals(ImportResult.Failed, repository.importFile(missing, SourceApp.GALLERY))
        assertTrue(rows().isEmpty())
    }
}
