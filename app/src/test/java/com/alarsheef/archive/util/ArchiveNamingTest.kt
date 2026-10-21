package com.alarsheef.archive.util

import com.alarsheef.archive.ai.DocumentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** تسمية الملفات المستوردة: التنظيف، الأسماء التلقائية، بادئة النوع، الاسم الذكي. */
class ArchiveNamingTest {

    // ---------- sanitize ----------

    @Test
    fun sanitize_replacesIllegalCharsInsteadOfGluingWords() {
        assertEquals("الأمل_للتجارة", ArchiveNaming.sanitize("الأمل/للتجارة"))
        assertEquals("a_b", ArchiveNaming.sanitize("a?b"))
        assertEquals("a_b", ArchiveNaming.sanitize("a\nb"))
    }

    @Test
    fun sanitize_removesArabicPunctuation() {
        assertEquals("ملف_جديد", ArchiveNaming.sanitize("ملف؟ جديد،"))
    }

    @Test
    fun sanitize_collapsesSpacesAndTrimsEdges() {
        assertEquals("a_b", ArchiveNaming.sanitize("  a   b  "))
        assertEquals("name", ArchiveNaming.sanitize("..name.."))
    }

    @Test
    fun sanitize_onlyIllegalChars_yieldsBlank() {
        assertEquals("", ArchiveNaming.sanitize("///"))
        assertEquals("", ArchiveNaming.sanitize("   "))
    }

    @Test
    fun sanitize_respectsMaxLength_withoutTrailingSeparator() {
        assertEquals("abcde", ArchiveNaming.sanitize("abcdefghij", 5))
        assertEquals("abcd", ArchiveNaming.sanitize("abcd_efgh", 5))
    }

    // ---------- الأسماء التلقائية ----------

    @Test
    fun isAutoName_recognizesAppGeneratedNames() {
        assertTrue(ArchiveNaming.isAutoName("IMG_20260925_48213.jpg"))
        assertTrue(ArchiveNaming.isAutoName("IMG_20260925_48213_1.jpg"))
        assertTrue(ArchiveNaming.isAutoName("فاتورة_20260925_48213.jpg"))
        assertTrue(ArchiveNaming.isAutoName("حركة_صندوق_20260925_7.png"))
    }

    @Test
    fun isAutoName_rejectsUserAndSmartNames_soTheyAreNeverOverwritten() {
        assertFalse(ArchiveNaming.isAutoName("my_invoice.jpg"))
        assertFalse(ArchiveNaming.isAutoName("IMG_2026_1.jpg"))
        assertFalse(ArchiveNaming.isAutoName("IMG_20260925_123456.jpg"))
        assertFalse(ArchiveNaming.isAutoName("فاتورة_شركة_2026-09-25_345.00ر.س.jpg"))
    }

    @Test
    fun labelOf_extractsTypePrefix() {
        assertEquals("فاتورة", ArchiveNaming.labelOf("فاتورة_20260925_48213.jpg"))
        assertEquals("حركة_صندوق", ArchiveNaming.labelOf("حركة_صندوق_20260925_7.png"))
        assertNull(ArchiveNaming.labelOf("IMG_20260925_48213.jpg"))
        assertNull(ArchiveNaming.labelOf("random.jpg"))
    }

    // ---------- بادئة النوع ----------

    @Test
    fun typeLabel_mapsEveryCategory() {
        assertEquals("فاتورة", ArchiveNaming.typeLabel(DocumentCategory.INVOICE, emptyList()))
        assertEquals("حركة_صندوق", ArchiveNaming.typeLabel(DocumentCategory.CASH_BOX, emptyList()))
        assertEquals("عقد", ArchiveNaming.typeLabel(DocumentCategory.CONTRACT, emptyList()))
        assertEquals("هوية", ArchiveNaming.typeLabel(DocumentCategory.ID_DOCUMENT, emptyList()))
    }

    @Test
    fun typeLabel_receipt_distinguishesTransferFromPlainReceipt() {
        assertEquals("حوالة", ArchiveNaming.typeLabel(DocumentCategory.RECEIPT, listOf("حواله", "مبلغ")))
        assertEquals("حوالة", ArchiveNaming.typeLabel(DocumentCategory.RECEIPT, listOf("transfer")))
        assertEquals("إيصال", ArchiveNaming.typeLabel(DocumentCategory.RECEIPT, listOf("ايصال", "مبلغ")))
        assertEquals("إيصال", ArchiveNaming.typeLabel(DocumentCategory.RECEIPT, emptyList()))
    }

    @Test
    fun everyTypeLabel_producesNameRecognizedAsAuto() {
        // البادئة التي تُعطى للعامل يجب أن تولّد اسمًا يقبل إعادة التسمية الذكية لاحقًا
        val labels = DocumentCategory.values().map { ArchiveNaming.typeLabel(it, emptyList()) } +
            ArchiveNaming.typeLabel(DocumentCategory.RECEIPT, listOf("حواله"))
        for (label in labels) {
            assertTrue(label, ArchiveNaming.isAutoName("${label}_20260925_123.jpg"))
            assertEquals(label, ArchiveNaming.labelOf("${label}_20260925_123.jpg"))
        }
    }

    // ---------- التواريخ ----------

    @Test
    fun normalizeDate_acceptsBothOrdersAndSeparators() {
        assertEquals("2026-09-25", ArchiveNaming.normalizeDate("25/09/2026"))
        assertEquals("2026-09-05", ArchiveNaming.normalizeDate("2026-9-5"))
        assertEquals("2026-09-25", ArchiveNaming.normalizeDate("التاريخ: 25.09.2026 م"))
    }

    @Test
    fun normalizeDate_convertsHindiDigits() {
        assertEquals("2026-09-25", ArchiveNaming.normalizeDate("٢٠٢٦/٠٩/٢٥"))
    }

    @Test
    fun normalizeDate_rejectsImpossibleOrMissingDates() {
        assertNull(ArchiveNaming.normalizeDate("13/13/2026"))
        assertNull(ArchiveNaming.normalizeDate("بدون تاريخ"))
        assertNull(ArchiveNaming.normalizeDate(""))
        assertNull(ArchiveNaming.normalizeDate(null))
    }

    // ---------- الاسم الذكي ----------

    @Test
    fun smartBase_fullData() {
        assertEquals(
            "فاتورة_شركة-الأمل_2026-09-25_345ر.س",
            ArchiveNaming.smartBase("فاتورة", "شركة الأمل", "25/09/2026", "345.00", "ر.س", "2026-01-01")
        )
    }

    @Test
    fun smartBase_vendorOnly_usesFallbackDate() {
        assertEquals(
            "عقد_مؤسسة-النور_2026-02-03",
            ArchiveNaming.smartBase("عقد", "مؤسسة النور", null, null, null, "2026-02-03")
        )
    }

    @Test
    fun smartBase_amountOnly_cleansThousandsSeparatorsAndHindiDigits() {
        assertEquals(
            "فاتورة_2026-01-01_1250.5",
            ArchiveNaming.smartBase("فاتورة", null, null, "١٬٢٥٠٫٥٠", null, "2026-01-01")
        )
    }

    @Test
    fun smartBase_noVendorAndNoAmount_returnsNull_soFileKeepsItsName() {
        assertNull(ArchiveNaming.smartBase("فاتورة", null, "25/09/2026", null, "ر.س", "2026-01-01"))
        assertNull(ArchiveNaming.smartBase("فاتورة", "   ", null, "abc", null, "2026-01-01"))
    }

    @Test
    fun smartBase_resultIsNeverAnAutoName() {
        // وإلا أُعيدت تسمية الملف في كل دورة تحليل
        val name = ArchiveNaming.smartBase("فاتورة", "شركة", null, "10", null, "2026-01-01")!!
        assertFalse(ArchiveNaming.isAutoName("$name.jpg"))
    }

    // ---------- بادئة النوع من النص ----------

    private val invoiceText = "فاتورة ضريبية\nرقم الفاتورة: 12345\nالإجمالي 345.00 ريال\nالتاريخ 2026-09-25"
    private val transferText = "حوالة مالية\nالمبلغ: 5000 ريال\nالمستفيد: محمد علي\nرقم العملية: 987654321"
    private val chatText = "صباح الخير كيف حالك اليوم"

    @Test
    fun typeLabelFromText_usesContentGateVerdict() {
        assertEquals("فاتورة", ArchiveNaming.typeLabelFromText(invoiceText))
        assertEquals("حوالة", ArchiveNaming.typeLabelFromText(transferText))
    }

    @Test
    fun typeLabelFromText_noLabelWithoutSufficientEvidence() {
        assertNull(ArchiveNaming.typeLabelFromText(chatText))
        assertNull("كلمة واحدة دليل جزئي لا يكفي", ArchiveNaming.typeLabelFromText("فاتورة"))
        assertNull(ArchiveNaming.typeLabelFromText(""))
        assertNull(ArchiveNaming.typeLabelFromText(null))
    }

    @Test
    fun withTypeLabel_replacesImgPrefix_keepingDateAndSerial() {
        assertEquals("فاتورة_20260925_48213", ArchiveNaming.withTypeLabel("IMG_20260925_48213.jpg", "فاتورة"))
        assertEquals("عقد_20260925_48213_1", ArchiveNaming.withTypeLabel("IMG_20260925_48213_1.png", "عقد"))
    }

    @Test
    fun withTypeLabel_onlyTouchesAutoImgNames() {
        assertNull(ArchiveNaming.withTypeLabel("حوالة_20260925_48213.jpg", "فاتورة"))
        assertNull(ArchiveNaming.withTypeLabel("IMG_holiday.jpg", "فاتورة"))
        assertNull(ArchiveNaming.withTypeLabel("عقد المحل.jpg", "عقد"))
    }

    @Test
    fun withTypeLabel_resultStaysRenamable_soSmartNameCanFollow() {
        val base = ArchiveNaming.withTypeLabel("IMG_20260925_48213.jpg", "حوالة")!!
        assertTrue(ArchiveNaming.isAutoName("$base.jpg"))
        assertEquals("حوالة", ArchiveNaming.labelOf("$base.jpg"))
    }

    // ---------- الاسم المخطَّط بعد التحليل ----------

    private fun planned(
        fileName: String,
        locked: Boolean = false,
        ocrText: String? = null,
        vendor: String? = null,
        amount: String? = null,
        invoiceDate: String? = null,
    ) = ArchiveNaming.plannedBase(
        fileName = fileName, nameLocked = locked, ocrText = ocrText,
        vendor = vendor, invoiceDate = invoiceDate, amount = amount, currency = "ر.س",
        fallbackDate = "2026-01-01",
    )

    @Test
    fun planned_lockedOrUserNamedFile_isNeverRenamed() {
        assertNull(planned("IMG_20260925_48213.jpg", locked = true, ocrText = invoiceText, vendor = "شركة", amount = "10"))
        assertNull(planned("عقد المحل.jpg", ocrText = invoiceText, vendor = "شركة", amount = "10"))
        assertNull(planned("فاتورة_شركة_الأمل_2026-09-25_345.00ر.س.jpg", ocrText = invoiceText, vendor = "شركة", amount = "10"))
    }

    @Test
    fun planned_cloudInvoiceData_givesFullSmartName() {
        assertEquals(
            "فاتورة_شركة-الأمل_2026-09-25_345ر.س",
            planned("فاتورة_20260925_48213.jpg", vendor = "شركة الأمل", amount = "345.00", invoiceDate = "25/09/2026")
        )
    }

    @Test
    fun planned_textBuildsTheFullName_notOnlyATypePrefix() {
        assertEquals(
            "فاتورة_2026-09-25_345ريال_12345",
            planned("IMG_20260925_48213.jpg", ocrText = invoiceText)
        )
        assertEquals(
            "حوالة_محمد-علي_2026-01-01_5000ريال_987654321",
            planned("IMG_20260925_48213.jpg", ocrText = transferText)
        )
    }

    @Test
    fun planned_paperFieldsWin_cloudFillsOnlyGaps() {
        // المستفيد والمبلغ من الورقة. تاريخ السحابة يملأ لأن الورقة بلا تاريخ.
        assertEquals(
            "حوالة_محمد-علي_2026-09-25_5000ريال_987654321",
            planned(
                "IMG_20260925_48213.jpg", ocrText = transferText,
                vendor = "شركة الأمل", amount = "345.00", invoiceDate = "25/09/2026"
            )
        )
    }

    @Test
    fun planned_cloudDataWithoutKnownType_fallsBackToGenericLabel() {
        assertEquals(
            "مستند_شركة-الأمل_2026-09-25_345ر.س",
            planned("IMG_20260925_48213.jpg", vendor = "شركة الأمل", amount = "345.00", invoiceDate = "25/09/2026")
        )
    }

    @Test
    fun planned_paperTypeWins_overTheTemporaryPrefix() {
        assertEquals(
            "حوالة_محمد-علي_2026-09-25_5000ريال_987654321",
            planned(
                "فاتورة_20260925_48213.jpg", ocrText = transferText,
                vendor = "شركة", amount = "10", invoiceDate = "25/09/2026"
            )
        )
    }

    @Test
    fun planned_nothingToImprove_keepsName() {
        assertNull(planned("IMG_20260925_48213.jpg", ocrText = chatText))
        assertNull(planned("IMG_20260925_48213.jpg"))
        assertNull(planned("فاتورة_20260925_48213.jpg", ocrText = "فاتورة"))
    }

    @Test
    fun planned_fullNameFromText_thenStops() {
        val named = planned("IMG_20260925_48213.jpg", ocrText = invoiceText, vendor = "شركة الأمل")!!
        assertEquals("فاتورة_شركة-الأمل_2026-09-25_345ريال_12345", named)
        assertNull(planned("$named.jpg", ocrText = invoiceText, vendor = "شركة الأمل", amount = "999"))
    }

    @Test
    fun planned_pageTwo_keepsTheSameNameWithPageSuffix() {
        assertEquals(
            "فاتورة_2026-09-25_345ريال_12345_ص2",
            ArchiveNaming.plannedBase(
                fileName = "مستند_20260925_48213.jpg", nameLocked = false, ocrText = invoiceText,
                vendor = null, invoiceDate = null, amount = null, currency = null,
                fallbackDate = "2026-01-01", pageNumber = 2,
            )
        )
    }

    @Test
    fun retitle_appliesAddedType_andLeavesLockedNames() {
        val text = "أمر شراء\nالمخزن الرئيسي\n29-09-2026\nالصافي 2,557.17 دولار"
        val types = listOf(com.alarsheef.archive.util.DocumentName.UserType("أمر شراء", listOf("أمر شراء")))
        assertEquals(
            "أمر-شراء_المخزن-الرئيسي_2026-09-29_2557.17دولار",
            ArchiveNaming.retitleBase(
                "مستند_المخزن-الرئيسي_2026-09-29_2557.17دولار.png",
                nameLocked = false, ocrText = text, fallbackDate = "2026-09-29",
                userTypes = types, addedType = "أمر-شراء",
            )
        )
        assertNull(
            ArchiveNaming.retitleBase(
                "عقد المحل.png", nameLocked = true, ocrText = text, fallbackDate = "2026-09-29",
                userTypes = types, addedType = "أمر-شراء",
            )
        )
    }
}
