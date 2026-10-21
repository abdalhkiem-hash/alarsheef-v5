package com.alarsheef.archive.scanner

import com.alarsheef.archive.data.entities.SourceApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** قواعد قرار الاستيراد التلقائي — المصدر، الهوية، منع التكرار، نافذة الفحص. */
class ImportRulesTest {

    // ---------- تحديد المصدر من المسار ----------

    @Test
    fun source_galleryFolders() {
        assertEquals(SourceApp.GALLERY, ImportRules.sourceFromRelativePath("DCIM/Camera/"))
        assertEquals(SourceApp.GALLERY, ImportRules.sourceFromRelativePath("DCIM/Screenshots/"))
        assertEquals(SourceApp.GALLERY, ImportRules.sourceFromRelativePath("Pictures/"))
    }

    @Test
    fun source_downloads() {
        assertEquals(SourceApp.DOWNLOADS, ImportRules.sourceFromRelativePath("Download/"))
        assertEquals(SourceApp.DOWNLOADS, ImportRules.sourceFromRelativePath("Download/Invoices/"))
    }

    @Test
    fun source_whatsapp_newAndLegacyLayouts() {
        assertEquals(
            SourceApp.WHATSAPP,
            ImportRules.sourceFromRelativePath("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/")
        )
        assertEquals(
            SourceApp.WHATSAPP,
            ImportRules.sourceFromRelativePath("WhatsApp/Media/WhatsApp Images/")
        )
    }

    @Test
    fun source_whatsappBusiness_newAndLegacyLayouts() {
        assertEquals(
            SourceApp.WHATSAPP_BUSINESS,
            ImportRules.sourceFromRelativePath(
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/"
            )
        )
        // الجذر القديم يبدأ بـ"WhatsApp" — يجب ألا يسقط في فرع واتساب العادي
        assertEquals(
            SourceApp.WHATSAPP_BUSINESS,
            ImportRules.sourceFromRelativePath("WhatsApp Business/Media/WhatsApp Business Images/")
        )
    }

    @Test
    fun source_isCaseInsensitive() {
        assertEquals(SourceApp.GALLERY, ImportRules.sourceFromRelativePath("dcim/camera/"))
        assertEquals(SourceApp.DOWNLOADS, ImportRules.sourceFromRelativePath("DOWNLOAD/"))
        assertEquals(
            SourceApp.WHATSAPP_BUSINESS,
            ImportRules.sourceFromRelativePath("android/media/COM.WHATSAPP.W4B/x/")
        )
    }

    @Test
    fun businessArrival_isTodaysNoticeImageOrPdf() {
        val images = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/"
        val docs = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents/"
        val stickers = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers/"
        val regular = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        val start = 1_700_000_000_000L
        val today = start / 1000
        assertTrue(ImportRules.isBusinessArrival(images, "اشعار.jpg", 20_000, today, start))
        assertTrue(ImportRules.isBusinessArrival(docs, "عقد.PDF", 20_000, today, start))
        assertFalse(ImportRules.isBusinessArrival(images, "اشعار.jpg", 20_000, today - 1, start))
        assertFalse(ImportRules.isBusinessArrival(stickers, "fun.webp", 20_000, today, start))
        assertFalse(ImportRules.isBusinessArrival(images, "motion.gif", 20_000, today, start))
        assertFalse(ImportRules.isBusinessArrival(regular, "اشعار.jpg", 20_000, today, start))
        assertFalse(ImportRules.isBusinessArrival("DCIM/Camera/", "اشعار.jpg", 20_000, today, start))
        assertFalse(ImportRules.isBusinessArrival(docs, "جدول.docx", 20_000, today, start))
    }

    @Test
    fun businessTree_opensDocumentsOnly() {
        assertTrue(ImportRules.shouldVisitBusinessDirectory("WhatsApp Business Documents"))
        assertTrue(ImportRules.shouldVisitBusinessDirectory("documents"))
        assertFalse(ImportRules.shouldVisitBusinessDirectory("WhatsApp Business Images"))
        assertFalse(ImportRules.shouldVisitBusinessDirectory("WhatsApp Business Video"))
        assertFalse(ImportRules.shouldVisitBusinessDirectory("WhatsApp Business Voice Notes"))
        assertFalse(ImportRules.shouldVisitBusinessDirectory("WhatsApp Business Stickers"))
        assertFalse(ImportRules.shouldVisitBusinessDirectory("WhatsApp Business Profile Photos"))
    }

    @Test
    fun source_unknownFolders_areNotImported() {
        assertNull(ImportRules.sourceFromRelativePath("Telegram/Telegram Images/"))
        assertNull(ImportRules.sourceFromRelativePath("Movies/"))
        assertNull(ImportRules.sourceFromRelativePath("Android/media/org.telegram.messenger/"))
        assertNull(ImportRules.sourceFromRelativePath(""))
    }

    // ---------- هوية الملف ----------

    @Test
    fun originalPath_alwaysSingleSlash() {
        assertEquals("Download/a.jpg", ImportRules.originalPathOf("Download/", "a.jpg"))
        assertEquals("Download/a.jpg", ImportRules.originalPathOf("Download", "a.jpg"))
        assertEquals("Download/a.jpg", ImportRules.originalPathOf("Download//", "a.jpg"))
    }

    @Test
    fun pageIdentity_usesRealPageNumber_failedPageDoesNotShiftTheNext() {
        assertEquals("Download/f.pdf#page=1", ImportRules.pageIdentity("Download/f.pdf", 1))
        assertEquals("Download/f.pdf#page=2", ImportRules.pageIdentity("Download/f.pdf", 2))
        assertEquals("Download/f.pdf#page=12", ImportRules.pageIdentity("Download/f.pdf", 12))
        assertEquals("Download/f.pdf#page=1", ImportRules.pageIdentity("Download/f.pdf", 0))
    }

    @Test
    fun pageDisplayName_numbersFromSecondPage() {
        assertEquals("عقد.pdf", ImportRules.pageDisplayName("عقد.pdf", 1))
        assertEquals("عقد.pdf (ص2)", ImportRules.pageDisplayName("عقد.pdf", 2))
        assertEquals("عقد.pdf (ص10)", ImportRules.pageDisplayName("عقد.pdf", 10))
    }

    @Test
    fun pageNumberOf_readsIdentityThenDisplayName() {
        assertEquals(1, ImportRules.pageNumberOf("content://doc/a.pdf", "a.pdf"))
        assertEquals(3, ImportRules.pageNumberOf("content://doc/a.pdf#page=3", "a.pdf"))
        assertEquals(2, ImportRules.pageNumberOf(null, "a.pdf (ص2)"))
    }

    // ---------- بوابة المسارات السريعة ----------

    @Test
    fun existingPaths_keepStoredValues() {
        val set = ImportRules.normalizeExistingPaths(listOf("DCIM/Camera/a.jpg", "Download/b.png"))
        assertTrue("DCIM/Camera/a.jpg" in set)
        assertTrue("Download/b.png" in set)
        assertEquals(2, set.size)
    }

    @Test
    fun existingPaths_legacyDoubleSlash_matchesNewSingleSlashIdentity() {
        val set = ImportRules.normalizeExistingPaths(listOf("Download//a.jpg"))
        assertTrue(ImportRules.originalPathOf("Download/", "a.jpg") in set)
    }

    @Test
    fun existingPaths_contentUris_areNotRewritten() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fa.pdf"
        val set = ImportRules.normalizeExistingPaths(listOf(uri))
        assertTrue(uri in set)
        assertFalse(uri.replace("//", "/") in set)
    }

    @Test
    fun existingPaths_pageOnlyRecord_marksWholePdfAsProcessed() {
        // الصفحة الأولى رُفضت/تكررت فلم تُخزَّن هويتها، وبقيت الصفحة الثانية فقط
        val set = ImportRules.normalizeExistingPaths(listOf("Download/f.pdf#page=1"))
        assertTrue("الملف يُعدّ معالَجًا فلا يُعاد تحويله", "Download/f.pdf" in set)
    }

    @Test
    fun existingPaths_pageOnlyRecord_worksForSafUris() {
        val uri = "content://authority/tree/t/document/primary%3ADownload%2Ff.pdf"
        val set = ImportRules.normalizeExistingPaths(listOf("$uri#page=3"))
        assertTrue(uri in set)
    }

    @Test
    fun existingPaths_emptyInput() {
        assertTrue(ImportRules.normalizeExistingPaths(emptyList()).isEmpty())
    }

    @Test
    fun existingPaths_resultIsMutable_forInScanUpdates() {
        val set = ImportRules.normalizeExistingPaths(listOf("a/b.jpg"))
        set.add("c/d.jpg")
        assertTrue("c/d.jpg" in set)
    }

    // ---------- منع التحويل المزدوج MediaStore ↔ SAF ----------

    @Test
    fun safKey_matchesRealTreeDocumentUri() {
        // الشكل الفعلي لـ DocumentFile تحت شجرة مختارة: …/tree/<شجرة>/document/<معرّف>
        val docUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload" +
            "/document/primary%3ADownload%2Finvoice.pdf"
        assertEquals(
            ImportRules.safDocumentKey("Download/", "invoice.pdf"),
            ImportRules.safDocumentKeyOf(docUri)
        )
    }

    @Test
    fun safKey_matchesNestedFolderAndArabicNameWithSpaces() {
        val docUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload" +
            "/document/primary%3ADownload%2FDocs%2F%D9%81%D8%A7%D8%AA%D9%88%D8%B1%D8%A9%202026.pdf"
        assertEquals(
            ImportRules.safDocumentKey("Download/Docs/", "فاتورة 2026.pdf"),
            ImportRules.safDocumentKeyOf(docUri)
        )
    }

    @Test
    fun safKey_relativePathWithoutTrailingSlash_stillMatches() {
        val docUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload" +
            "/document/primary%3ADownload%2Fa.pdf"
        assertEquals(ImportRules.safDocumentKey("Download", "a.pdf"), ImportRules.safDocumentKeyOf(docUri))
    }

    @Test
    fun safKey_isCaseInsensitive_likeTheSharedStorage() {
        val docUri = "content://com.android.externalstorage.documents/tree/primary%3Adownload" +
            "/document/primary%3Adownload%2FA.PDF"
        assertEquals(ImportRules.safDocumentKey("Download/", "a.pdf"), ImportRules.safDocumentKeyOf(docUri))
    }

    @Test
    fun safKey_plusSignInName_isNotTurnedIntoSpace() {
        val docUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload" +
            "/document/primary%3ADownload%2Fa+b.pdf"
        assertEquals(ImportRules.safDocumentKey("Download/", "a+b.pdf"), ImportRules.safDocumentKeyOf(docUri))
    }

    @Test
    fun safKey_differentFiles_doNotCollide() {
        val docUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload" +
            "/document/primary%3ADownload%2Fother.pdf"
        assertFalse(
            ImportRules.safDocumentKey("Download/", "invoice.pdf") == ImportRules.safDocumentKeyOf(docUri)
        )
    }

    @Test
    fun safKey_otherProviderIds_neverMatchMediaStoreKey() {
        // مزوّد التنزيلات يستخدم معرّفات مثل msf:1234 — لا تطابق خاطئ
        val docUri = "content://com.android.providers.downloads.documents/tree/downloads/document/msf%3A1234"
        assertEquals("msf:1234", ImportRules.safDocumentKeyOf(docUri))
        assertFalse(ImportRules.safDocumentKey("Download/", "a.pdf") == ImportRules.safDocumentKeyOf(docUri))
    }

    @Test
    fun safKey_nonDocumentUri_returnsNull() {
        assertNull(ImportRules.safDocumentKeyOf("content://media/external/file/42"))
        assertNull(ImportRules.safDocumentKeyOf("content://authority/tree/t/document/"))
        assertNull(ImportRules.safDocumentKeyOf(""))
    }

    @Test
    fun safKey_malformedEncoding_returnsNullInsteadOfCrashing() {
        assertNull(ImportRules.safDocumentKeyOf("content://authority/tree/t/document/primary%3ADownload%2Fa%ZZ.pdf"))
    }

    // ---------- تاريخ الأرشفة ----------

    @Test
    fun originalDate_prefersDateTaken() {
        assertEquals(1_700_000_000_000L, ImportRules.resolveOriginalDate(1_700_000_000_000L, 1_600_000_000L))
    }

    @Test
    fun originalDate_fallsBackToModifiedSecondsAsMillis() {
        assertEquals(1_600_000_000_000L, ImportRules.resolveOriginalDate(0L, 1_600_000_000L))
    }

    @Test
    fun originalDate_unknown_isZero_soRepositoryUsesItsOwnFallback() {
        assertEquals(0L, ImportRules.resolveOriginalDate(0L, 0L))
        assertEquals(0L, ImportRules.resolveOriginalDate(-5L, -5L))
    }

    // ---------- نافذة الفحص ----------

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val today = 1_800_000_000_000L // قيمة مرجعية ثابتة لبداية "اليوم"

    @Test
    fun window_firstScanEver_startsToday_neverPullsOldLibrary() {
        assertEquals(today, ImportRules.scanWindowStart(today, 0L))
        assertEquals(today, ImportRules.scanWindowStart(today, -1L))
    }

    @Test
    fun window_lastScanEarlierToday_startsAtMidnight() {
        assertEquals(today, ImportRules.scanWindowStart(today, today + 9 * hour))
        assertEquals(today, ImportRules.scanWindowStart(today, today))
    }

    @Test
    fun window_lastScanYesterdayAfternoon_coversTheEveningGap() {
        // فحص الأمس 14:00، وصلت فاتورة 23:00 — فحص اليوم يجب أن يلتقطها
        val lastScan = today - 10 * hour
        val arrivedLastNight = today - 1 * hour
        val start = ImportRules.scanWindowStart(today, lastScan)
        assertEquals(lastScan, start)
        assertTrue(arrivedLastNight >= start)
    }

    @Test
    fun window_longGap_isCappedAtMaxLookback() {
        val start = ImportRules.scanWindowStart(today, today - 30 * day)
        assertEquals(today - ImportRules.MAX_LOOKBACK_MS, start)
    }

    @Test
    fun window_gapExactlyAtCap_isNotCappedFurther() {
        val lastScan = today - ImportRules.MAX_LOOKBACK_MS
        assertEquals(lastScan, ImportRules.scanWindowStart(today, lastScan))
    }

    @Test
    fun window_clockMovedBackwards_futureLastScan_fallsBackToToday() {
        assertEquals(today, ImportRules.scanWindowStart(today, today + 5 * day))
    }

    @Test
    fun window_neverStartsAfterToday() {
        for (last in listOf(0L, today - 40 * day, today - day, today - 1, today, today + day)) {
            assertTrue(ImportRules.scanWindowStart(today, last) <= today)
        }
    }

    // ---------- اسم ملف الكاش ----------

    @Test
    fun tempName_keepsOnlyLowercasedExtension() {
        assertEquals("media_7.png", ImportRules.tempFileName("media", "Screenshot.PNG", 7L))
        assertEquals("pdf_7.pdf", ImportRules.tempFileName("pdf", "تقرير نهائي.pdf", 7L))
        assertEquals("custom_7.docx", ImportRules.tempFileName("custom", "report.final.docx", 7L))
    }

    @Test
    fun tempName_noExtension_defaultsToJpg() {
        assertEquals("media_7.jpg", ImportRules.tempFileName("media", "IMG-0001", 7L))
    }

    @Test
    fun tempName_veryLongArabicName_staysWithinFilesystemLimit() {
        // 200 حرف عربي = 400 بايت UTF-8 — يتجاوز حد 255 بايت لو أُدرج الاسم نفسه
        val longName = "م".repeat(200) + ".pdf"
        val temp = ImportRules.tempFileName("pdf", longName, System.nanoTime())
        assertTrue(temp.toByteArray(Charsets.UTF_8).size < 64)
        assertTrue(temp.endsWith(".pdf"))
    }

    @Test
    fun tempName_extensionCannotEscapeCacheDir() {
        val temp = ImportRules.tempFileName("custom", "x.a/../b", 7L)
        assertFalse(temp.contains("/"))
        assertFalse(temp.contains(".."))
    }

    // ---------- الاستيراد الخام من SAF ----------

    @Test
    fun raw_officeAndTextDocuments_areImported() {
        for (name in listOf("عقد.docx", "كشف.xlsx", "notes.txt", "data.csv", "عرض.pptx")) {
            assertTrue(name, ImportRules.isImportableRawDocument(name))
        }
    }

    @Test
    fun raw_unfinishedDownloads_areSkipped() {
        // تنزيل Chrome الجاري: استيراده يحفظ ملفًا مبتورًا ثم يُعدّ "مؤرشفًا"
        for (name in listOf("invoice.pdf.crdownload", "file.part", "x.tmp", "y.PARTIAL")) {
            assertFalse(name, ImportRules.isImportableRawDocument(name))
        }
    }

    @Test
    fun raw_mediaArchivesAndApps_areSkipped() {
        for (name in listOf("clip.mp4", "voice.opus", "song.MP3", "app.apk", "backup.zip", "a.rar")) {
            assertFalse(name, ImportRules.isImportableRawDocument(name))
        }
    }

    @Test
    fun raw_hiddenAndBlankNames_areSkipped() {
        assertFalse(ImportRules.isImportableRawDocument(".nomedia"))
        assertFalse(ImportRules.isImportableRawDocument(".trashed-123-doc.docx"))
        assertFalse(ImportRules.isImportableRawDocument(""))
        assertFalse(ImportRules.isImportableRawDocument("   "))
    }

    // ---------- مجلد الإدخال: الصورة وPDF فقط، والناقص يُترك ----------

    @Test
    fun inbox_imagesAndPdfs_areArchived() {
        assertEquals(ImportRules.InboxFileKind.IMAGE, ImportRules.inboxFileKind("ورقة.jpg", 20_000))
        assertEquals(ImportRules.InboxFileKind.IMAGE, ImportRules.inboxFileKind("scan.HEIC", 20_000))
        assertEquals(ImportRules.InboxFileKind.PDF, ImportRules.inboxFileKind("عقد.PDF", 20_000))
    }

    @Test
    fun inbox_emptyAndUnfinished_areLeftUntilComplete() {
        assertEquals(ImportRules.InboxFileKind.LEAVE, ImportRules.inboxFileKind("دايمن.png", 0))
        assertEquals(ImportRules.InboxFileKind.LEAVE, ImportRules.inboxFileKind("invoice.pdf.crdownload", 4000))
        assertEquals(ImportRules.InboxFileKind.LEAVE, ImportRules.inboxFileKind("file.part", 10))
        assertEquals(ImportRules.InboxFileKind.LEAVE, ImportRules.inboxFileKind("x.TMP", 10))
    }

    @Test
    fun inbox_beforeToday_staysInTheFolder() {
        val start = 1_700_000_000_000L
        assertTrue(ImportRules.isBeforeToday(start - 1, start))
        assertFalse(ImportRules.isBeforeToday(start, start))
        assertFalse(ImportRules.isBeforeToday(start + 60_000, start))
        assertFalse(ImportRules.isBeforeToday(0L, start))
    }

    @Test
    fun inbox_otherFiles_stayOut() {
        assertEquals(ImportRules.InboxFileKind.IGNORE, ImportRules.inboxFileKind("كتاب.docx", 5000))
        assertEquals(ImportRules.InboxFileKind.IGNORE, ImportRules.inboxFileKind("clip.mp4", 5000))
        assertEquals(ImportRules.InboxFileKind.IGNORE, ImportRules.inboxFileKind(".nomedia", 10))
        assertEquals(ImportRules.InboxFileKind.IGNORE, ImportRules.inboxFileKind("  ", 10))
    }
}
