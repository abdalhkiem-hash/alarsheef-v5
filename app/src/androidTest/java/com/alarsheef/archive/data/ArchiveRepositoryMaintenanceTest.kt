package com.alarsheef.archive.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.repository.ImportResult
import com.alarsheef.archive.testutil.IsolatedArchive
import com.alarsheef.archive.testutil.IsolatedArchive.Companion.noon
import com.alarsheef.archive.util.FileUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** الحذف والنقل وإظهار المخفي ومجموعات الأيام/الوجوه — كلها على أرشيف معزول. */
@RunWith(AndroidJUnit4::class)
class ArchiveRepositoryMaintenanceTest {

    private lateinit var archive: IsolatedArchive
    private val repository get() = archive.repository
    private val imageDao get() = archive.db.archivedImageDao()

    @Before
    fun setUp() {
        archive = IsolatedArchive("repo_maintenance_test")
    }

    @After
    fun tearDown() {
        archive.close()
    }

    // ---------- حذف يوم ----------

    @Test
    fun deleteDayOrGroup_removesRowsAndFilesFromDisk() = runBlocking {
        repository.importFile(archive.source("a.jpg", "one"), SourceApp.GALLERY, originalDate = noon(2024, 2, 29))
        repository.importFile(archive.source("b.jpg", "two"), SourceApp.GALLERY, originalDate = noon(2024, 2, 29))
        repository.importFile(archive.source("c.jpg", "other-day"), SourceApp.GALLERY, originalDate = noon(2024, 3, 1))
        assertEquals(3, archive.archivedFileCount())

        repository.deleteDayOrGroup(2024, 2, 29)

        assertEquals("يبقى ملف اليوم الآخر فقط", 1, imageDao.getAllOnce().size)
        assertEquals("ملفات اليوم المحذوف تُزال من القرص لا من القاعدة فقط", 1, archive.archivedFileCount())
    }

    @Test
    fun deleteDayOrGroup_manualDayWithFiles_removesBoth() = runBlocking {
        repository.createDayGroup(2024, 2, 29)
        repository.importFile(archive.source("a.jpg", "one"), SourceApp.GALLERY, originalDate = noon(2024, 2, 29))

        repository.deleteDayOrGroup(2024, 2, 29)

        assertTrue(repository.observeDayGroups(2024, 2).first().isEmpty())
        assertTrue(imageDao.getAllOnce().isEmpty())
        assertEquals(0, archive.archivedFileCount())
    }

    @Test
    fun deleteDayOrGroup_alsoRemovesHiddenPendingFiles() = runBlocking {
        repository.importFile(
            archive.source("p.jpg", "pending"), SourceApp.WHATSAPP,
            originalDate = noon(2024, 2, 29), contentVerified = ContentVerified.AMBIGUOUS,
        )
        repository.deleteDayOrGroup(2024, 2, 29)
        assertEquals(0, archive.archivedFileCount())
        assertTrue(repository.observePendingReviewImages().first().isEmpty())
    }

    // ---------- يوم يدوي ----------

    @Test
    fun createDayGroup_twice_doesNotCrash_andReportsExisting() = runBlocking {
        assertTrue(repository.createDayGroup(2024, 2, 10) > 0L)
        assertEquals(-1L, repository.createDayGroup(2024, 2, 10))
        assertEquals(1, repository.observeDayGroups(2024, 2).first().size)
    }

    // ---------- نقل ----------

    @Test
    fun moveImages_toAnotherDay_movesFileAndRow() = runBlocking {
        repository.importFile(archive.source("a.jpg", "one"), SourceApp.GALLERY, originalDate = noon(2024, 2, 29))
        val before = imageDao.getAllOnce().single()

        assertEquals(1, repository.moveImages(listOf(before), 2025, 1, 5))

        val after = imageDao.getAllOnce().single()
        assertEquals(Triple(2025, 1, 5), Triple(after.year, after.month, after.day))
        assertTrue(File(after.storedPath).exists())
        assertFalse(File(before.storedPath).exists())
        assertEquals(File(archive.context.filesDir, "archive/2025/01/05"), File(after.storedPath).parentFile)
    }

    @Test
    fun moveImages_toSameDay_isNoOp_notRenamedCopy() = runBlocking {
        repository.importFile(archive.source("a.jpg", "one"), SourceApp.GALLERY, originalDate = noon(2024, 2, 29))
        val before = imageDao.getAllOnce().single()

        assertEquals(0, repository.moveImages(listOf(before), 2024, 2, 29))

        val after = imageDao.getAllOnce().single()
        assertEquals(before.fileName, after.fileName)
        assertEquals(before.storedPath, after.storedPath)
        assertTrue(File(after.storedPath).exists())
    }

    // ---------- المخفي (معلّق/مرفوض) ----------

    @Test
    fun manualReimportOfRejectedFile_makesItVisibleAgain() = runBlocking {
        val hash = FileUtils.sha256Of(archive.source("probe.jpg", "doc"))
        repository.importFile(
            archive.source("auto.jpg", "doc"), SourceApp.WHATSAPP, countDuplicate = false,
            contentVerified = ContentVerified.REJECTED,
        )
        assertTrue("المرفوض مخفي من القوائم", imageDao.getAllOnce().isEmpty())

        val result = repository.importFile(archive.source("manual.jpg", "doc"), SourceApp.MANUAL_IMPORT)

        assertTrue(result is ImportResult.Duplicate)
        assertEquals(ContentVerified.UNVERIFIED, imageDao.findByContentHash(hash)!!.contentVerified)
        assertEquals("يظهر الآن بدل رسالة «موجود» عن ملف لا يُرى", 1, imageDao.getAllOnce().size)
    }

    @Test
    fun automaticRescanOfRejectedFile_keepsItHidden() = runBlocking {
        val hash = FileUtils.sha256Of(archive.source("probe.jpg", "doc"))
        repository.importFile(
            archive.source("auto.jpg", "doc"), SourceApp.WHATSAPP, countDuplicate = false,
            contentVerified = ContentVerified.REJECTED,
        )
        repository.importFile(archive.source("again.jpg", "doc"), SourceApp.WHATSAPP, countDuplicate = false)

        assertEquals(ContentVerified.REJECTED, imageDao.findByContentHash(hash)!!.contentVerified)
        assertTrue(imageDao.getAllOnce().isEmpty())
    }

    // ---------- التسمية الذكية ----------

    /** يستورد ملفًا ويعيد صفّه (يشمل المخفي). */
    private suspend fun imported(
        content: String,
        status: ContentVerified = ContentVerified.ACCEPTED,
        preferredBaseName: String? = null,
        ocrText: String? = null,
    ) = run {
        val hash = FileUtils.sha256Of(archive.source("probe_$content.jpg", content))
        repository.importFile(
            archive.source("$content.jpg", content), SourceApp.WHATSAPP, countDuplicate = false,
            originalDate = noon(2024, 2, 29), contentVerified = status,
            preferredBaseName = preferredBaseName, ocrText = ocrText,
        )
        imageDao.findByContentHash(hash)!!
    }

    private suspend fun candidateIds(since: Long = 0L) =
        repository.smartNameCandidates(since, limit = 100).map { it.id }.toSet()

    @Test
    fun importFile_storesTextReadByImportGate_soAnalysisNeedsNoSecondOcr() = runBlocking {
        val row = imported("with-text", ocrText = "فاتورة ضريبية رقم 12345")
        assertEquals("فاتورة ضريبية رقم 12345", repository.ocrTextFor(row.id))

        val bare = imported("without-text")
        assertEquals(null, repository.ocrTextFor(bare.id))
    }

    @Test
    fun smartNameCandidates_analyzedDocumentWithoutCloudResult_isRetried() = runBlocking {
        val doc = imported("doc")
        assertTrue("قبل التحليل ليست مرشّحة — الدفعة العادية تتولاها", candidateIds().isEmpty())

        repository.markAiAnalyzed(doc.id)
        assertEquals(setOf(doc.id), candidateIds())
    }

    @Test
    fun smartNameCandidates_stopAfterSuccessfulCloudClassification() = runBlocking {
        val doc = imported("doc")
        repository.markAiAnalyzed(doc.id)
        // نجح النداء السحابي ولو بلا وصف ولا فاتورة: لا إعادة محاولة تحرق الحصة كل يوم
        repository.saveCloudMeta(doc.id, description = "", invoice = null, model = "test")
        assertTrue(candidateIds().isEmpty())
    }

    @Test
    fun smartNameCandidates_excludeLockedHiddenAndNonDocuments() = runBlocking {
        val locked = imported("locked", preferredBaseName = "عقد المحل")
        val pending = imported("pending", status = ContentVerified.AMBIGUOUS)
        val photo = imported("photo", status = ContentVerified.UNVERIFIED)
        listOf(locked, pending, photo).forEach { repository.markAiAnalyzed(it.id) }

        assertTrue(candidateIds().isEmpty())
    }

    @Test
    fun smartNameCandidates_includeUnverifiedImageWithLocalDocumentLabel() = runBlocking {
        val scan = imported("scan", status = ContentVerified.UNVERIFIED)
        repository.markAiAnalyzed(scan.id)
        repository.saveLabels(scan.id, listOf(com.alarsheef.archive.ai.ClassifiedLabel("مستند / ملف نصي", 0.8f)))

        assertEquals(setOf(scan.id), candidateIds())
    }

    @Test
    fun smartNameCandidates_respectImportWindow_noBulkRenameOfOldArchive() = runBlocking {
        val doc = imported("doc")
        repository.markAiAnalyzed(doc.id)

        assertEquals(setOf(doc.id), candidateIds(since = System.currentTimeMillis() - 60_000))
        assertTrue(candidateIds(since = System.currentTimeMillis() + 60_000).isEmpty())
    }

    @Test
    fun smartRename_keepsFileOnDisk_andDropsOutOfCandidates() = runBlocking {
        val doc = imported("doc")
        repository.markAiAnalyzed(doc.id)

        val renamed = repository.renameImage(doc, "فاتورة_شركة_الأمل_2024-02-29_345.00ر.س", lock = false)!!

        assertEquals("فاتورة_شركة_الأمل_2024-02-29_345.00ر.س.jpg", renamed.fileName)
        assertTrue(File(renamed.storedPath).exists())
        assertFalse(File(doc.storedPath).exists())
        assertFalse("الاسم الذكي لا يقفل الاسم", renamed.nameLocked)
        assertTrue("اسم غير تلقائي لا يُعاد ترشيحه", candidateIds().isEmpty())
    }

    // ---------- الوجوه ----------

    @Test
    fun deleteFaceGroup_removesCropFilesFromDisk() = runBlocking {
        val crop = File(archive.context.filesDir, "face_crops/g1_1.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        repository.upsertFaceGroup("g1", "أحمد")
        repository.addFaceMember("g1", imageId = 1L, descriptorHash = "00000000000000ff", cropPath = crop.absolutePath)

        repository.deleteFaceGroup("g1")

        assertFalse("ملف القصّ لا يبقى يتيمًا", crop.exists())
        assertTrue(repository.observeFaceGroupRows().first().isEmpty())
    }
}
