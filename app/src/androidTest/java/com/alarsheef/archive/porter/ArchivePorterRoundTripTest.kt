package com.alarsheef.archive.porter

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.testutil.IsolatedArchive
import com.alarsheef.archive.testutil.IsolatedArchive.Companion.noon
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** تصدير الأرشيف إلى ZIP ثم استعادته في أرشيف آخر — النسخ الاحتياطي لا قيمة له إن لم يُستعد كاملًا. */
@RunWith(AndroidJUnit4::class)
class ArchivePorterRoundTripTest {

    private lateinit var source: IsolatedArchive
    private lateinit var target: IsolatedArchive

    @Before
    fun setUp() {
        source = IsolatedArchive("porter_source")
        target = IsolatedArchive("porter_target")
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private suspend fun exportSourceTo(zip: File): PorterResult =
        ArchivePorter(source.context, source.repository)
            .exportTo(Uri.fromFile(zip), source.repository.allForExport())

    private suspend fun importIntoTarget(zip: File): PorterResult =
        ArchivePorter(target.context, target.repository).importFrom(Uri.fromFile(zip))

    @Test
    fun exportThenImport_restoresFilesDatesAndMetadata() = runBlocking {
        source.repository.importFile(
            source.source("a.jpg", "invoice-bytes"), SourceApp.WHATSAPP, countDuplicate = false,
            originalPath = "WhatsApp/Media/WhatsApp Images/a.jpg",
            originalDate = noon(2024, 2, 29),
            contentVerified = ContentVerified.ACCEPTED,
            namePrefix = "فاتورة",
            originalName = "WhatsApp Image 2024-02-29.jpg",
        )
        source.repository.importFile(
            source.source("b.png", "scan-bytes"), SourceApp.DOCUMENT_SCAN,
            originalDate = noon(2023, 7, 15), preferredBaseName = "عقد المحل",
        )
        val zip = File(source.root, "backup.zip")

        assertEquals(PorterResult.Success(2), exportSourceTo(zip))
        assertEquals(PorterResult.Success(2), importIntoTarget(zip))

        val rows = target.db.archivedImageDao().getAllOnce()
        assertEquals(2, rows.size)

        val invoice = rows.first { it.sourceApp == SourceApp.WHATSAPP }
        assertEquals(Triple(2024, 2, 29), Triple(invoice.year, invoice.month, invoice.day))
        assertEquals(noon(2024, 2, 29), invoice.capturedAt)
        assertEquals(ContentVerified.ACCEPTED, invoice.contentVerified)
        assertEquals("WhatsApp Image 2024-02-29.jpg", invoice.originalName)
        assertEquals("WhatsApp/Media/WhatsApp Images/a.jpg", invoice.originalPath)
        assertTrue(invoice.fileName, invoice.fileName.startsWith("فاتورة_20240229_"))
        assertEquals("invoice-bytes", File(invoice.storedPath).readText())

        val scan = rows.first { it.sourceApp == SourceApp.DOCUMENT_SCAN }
        assertEquals(Triple(2023, 7, 15), Triple(scan.year, scan.month, scan.day))
        assertEquals("عقد_المحل.png", scan.fileName)
        assertTrue("الاسم اليدوي يبقى مقفولًا بعد الاستعادة", scan.nameLocked)
        assertEquals("scan-bytes", File(scan.storedPath).readText())
    }

    @Test
    fun export_writesMetadataFirst_andCountsOnlyExistingFiles() = runBlocking {
        source.repository.importFile(source.source("a.jpg", "one"), SourceApp.GALLERY)
        source.repository.importFile(source.source("b.jpg", "two"), SourceApp.GALLERY)
        // ملف مفقود من القرص وسجله باقٍ
        val rows = source.db.archivedImageDao().getAllOnce()
        File(rows.first().storedPath).delete()
        val zip = File(source.root, "backup.zip")

        assertEquals(PorterResult.Success(1), exportSourceTo(zip))

        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertEquals("metadata.json", names.first())
            assertEquals(2, names.size)
        }
    }

    @Test
    fun import_legacyArchiveWithMetadataLast_stillRestoresSourceAndDate() = runBlocking {
        // الشكل الذي كانت تكتبه النسخ السابقة: الملفات أولًا ثم metadata.json
        val entry = "files/2022/5/9/old.jpg"
        val zip = File(target.root, "legacy.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(entry))
            out.write("legacy-bytes".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("metadata.json"))
            out.write(
                JSONArray().put(
                    JSONObject()
                        .put("entry", entry).put("fileName", "old.jpg")
                        .put("sourceApp", "GALLERY")
                        .put("year", 2022).put("month", 5).put("day", 9)
                        .put("capturedAt", noon(2022, 5, 9))
                ).toString().toByteArray()
            )
            out.closeEntry()
        }

        assertEquals(PorterResult.Success(1), importIntoTarget(zip))

        val row = target.db.archivedImageDao().getAllOnce().single()
        assertEquals("المصدر كان يضيع ويصير MANUAL_IMPORT", SourceApp.GALLERY, row.sourceApp)
        assertEquals("تاريخ الالتقاط كان يصير وقت الاستيراد", noon(2022, 5, 9), row.capturedAt)
        assertEquals(Triple(2022, 5, 9), Triple(row.year, row.month, row.day))
        assertEquals("old.jpg", row.fileName)
    }

    @Test
    fun import_archiveWithoutMetadata_fallsBackToFolderDates() = runBlocking {
        val zip = File(target.root, "bare.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("files/2021/12/31/x.jpg"))
            out.write("bare".toByteArray())
            out.closeEntry()
        }

        assertEquals(PorterResult.Success(1), importIntoTarget(zip))

        val row = target.db.archivedImageDao().getAllOnce().single()
        assertEquals(Triple(2021, 12, 31), Triple(row.year, row.month, row.day))
        assertEquals(SourceApp.MANUAL_IMPORT, row.sourceApp)
    }

    @Test
    fun importingSameArchiveTwice_addsNothingTheSecondTime() = runBlocking {
        source.repository.importFile(source.source("a.jpg", "one"), SourceApp.GALLERY)
        val zip = File(source.root, "backup.zip")
        exportSourceTo(zip)

        assertEquals(PorterResult.Success(1), importIntoTarget(zip))
        assertEquals(PorterResult.Success(0), importIntoTarget(zip))

        assertEquals(1, target.db.archivedImageDao().getAllOnce().size)
        assertEquals(1, target.archivedFileCount())
    }

    @Test
    fun import_leavesNoTempFilesInCache() = runBlocking {
        source.repository.importFile(source.source("a.jpg", "one"), SourceApp.GALLERY)
        val zip = File(source.root, "backup.zip")
        exportSourceTo(zip)

        importIntoTarget(zip)

        val leftovers = target.context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("restore_") }
        assertTrue("ملفات مؤقتة باقية: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun import_nonZipFile_failsOrAddsNothing_withoutCrashing() = runBlocking {
        val notZip = File(target.root, "note.zip").apply { writeText("this is not a zip") }
        val result = importIntoTarget(notZip)
        assertTrue(result == PorterResult.Failure || result == PorterResult.Success(0))
        assertTrue(target.db.archivedImageDao().getAllOnce().isEmpty())
    }
}
