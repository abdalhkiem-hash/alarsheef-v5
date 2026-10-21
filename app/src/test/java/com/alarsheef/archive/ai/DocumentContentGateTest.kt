package com.alarsheef.archive.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** بوابة المحتوى النصي: القبول/التعليق/الرفض من نص OCR — قلب قرار "المستندات فقط". */
class DocumentContentGateTest {

    private fun accepted(text: String): ContentVerdict.Accepted {
        val verdict = DocumentContentGate.verify(text)
        assertTrue("متوقع قبول، والنتيجة: $verdict", verdict is ContentVerdict.Accepted)
        return verdict as ContentVerdict.Accepted
    }

    private fun assertAmbiguous(text: String) {
        val verdict = DocumentContentGate.verify(text)
        assertTrue("متوقع تعليق، والنتيجة: $verdict", verdict is ContentVerdict.Ambiguous)
    }

    private fun assertRejected(text: String) {
        val verdict = DocumentContentGate.verify(text)
        assertTrue("متوقع رفض، والنتيجة: $verdict", verdict is ContentVerdict.Rejected)
    }

    // ---------- قبول ----------

    @Test
    fun arabicTaxInvoice_isAcceptedAsInvoice() {
        val v = accepted("فاتورة ضريبية\nرقم الفاتورة: 12345\nالإجمالي 345.00 ريال\nالتاريخ 2026-09-25")
        assertEquals(DocumentCategory.INVOICE, v.primaryCategory)
    }

    @Test
    fun englishTaxInvoice_isAcceptedAsInvoice() {
        val v = accepted("TAX INVOICE\nInvoice No: 20261234\nTotal 150.00 USD")
        assertEquals(DocumentCategory.INVOICE, v.primaryCategory)
    }

    @Test
    fun bankTransfer_isAcceptedAsReceipt_withTransferEvidence() {
        val v = accepted("حوالة مالية\nالمبلغ: 5000 ريال\nالمستفيد: محمد علي\nرقم العملية: 987654321")
        assertEquals(DocumentCategory.RECEIPT, v.primaryCategory)
        assertTrue(v.matchedKeywords.any { it.contains("حواله") })
    }

    @Test
    fun receipt_isAcceptedAsReceipt() {
        val v = accepted("إيصال استلام\nالمبلغ 250.00 ريال\nالتاريخ 01/10/2026")
        assertEquals(DocumentCategory.RECEIPT, v.primaryCategory)
    }

    @Test
    fun cashBoxMovement_isAcceptedAsCashBox() {
        val v = accepted("حركة الصندوق\nالرصيد 1500.00\nوارد 200.00\nمنصرف 50.00")
        assertEquals(DocumentCategory.CASH_BOX, v.primaryCategory)
    }

    @Test
    fun rentalContract_isAcceptedAsContract() {
        val v = accepted("عقد إيجار\nالطرف الأول: أحمد\nالطرف الثاني: خالد\nالتاريخ 2026-03-01\nالتوقيع")
        assertEquals(DocumentCategory.CONTRACT, v.primaryCategory)
    }

    @Test
    fun passport_isAcceptedAsIdDocument() {
        val v = accepted("جواز سفر\nPassport No: 12345678\nالجنسية: يمني")
        assertEquals(DocumentCategory.ID_DOCUMENT, v.primaryCategory)
    }

    @Test
    fun hindiDigitsAndArabicDecimalSeparator_countAsNumericEvidence() {
        val v = accepted("فاتورة\nالمبلغ ٣٤٥٫٠٠ ريال\n٢٠٢٦/٠٩/٢٥")
        assertEquals(DocumentCategory.INVOICE, v.primaryCategory)
        assertTrue("#مبلغ" in v.matchedKeywords)
        assertTrue("#تاريخ" in v.matchedKeywords)
    }

    @Test
    fun diacriticsAndTatweel_doNotHideKeywords() {
        val v = accepted("فَاتُورَة ضريبيـــة\nالمبلغ 99.50 ريال\nالتاريخ 2026-01-02")
        assertEquals(DocumentCategory.INVOICE, v.primaryCategory)
    }

    @Test
    fun financialTableWithoutAnchor_isAccepted_defaultingToCashBox() {
        val v = accepted("المبلغ 100.00 الرصيد 50.00 مدين 2026-01-05")
        assertEquals(DocumentCategory.CASH_BOX, v.primaryCategory)
    }

    // ---------- تعليق ----------

    @Test
    fun anchorAlone_isAmbiguous_notSilentlyRejected() {
        assertAmbiguous("فاتورة")
        assertAmbiguous("حركة الصندوق اليومية")
    }

    @Test
    fun twoSupportsWithoutNumbers_areAmbiguous() {
        assertAmbiguous("المبلغ والرصيد")
    }

    // ---------- رفض ----------

    @Test
    fun emptyOrTinyText_isRejected() {
        assertRejected("")
        assertRejected("   ")
        assertRejected("ab")
    }

    @Test
    fun casualChat_isRejected() {
        assertRejected("صباح الخير كيف حالك اليوم")
        assertRejected("Hello world, see you tomorrow")
    }

    @Test
    fun mailInboxScreenshot_doesNotTriggerCashBoxKeywords() {
        // "صندوق الوارد" يحوي "صندوق" و"وارد" — يجب ألا يُعدّ حركة صندوق
        assertRejected("صندوق الوارد 3 رسائل جديدة")
    }

    @Test
    fun shortLatinTerms_doNotMatchInsideLongerWords() {
        // "tax" داخل "taxi" ليست دليلًا ضريبيًا
        assertRejected("my taxi is here")
    }

    // ---------- التطبيع ----------

    @Test
    fun normalizeLoose_unifiesArabicFormsAndDigits() {
        assertEquals("الفاتوره رقم 123", DocumentContentGate.normalizeLoose("الفَاتُورَة   رقم ١٢٣"))
        assertEquals("اجمالي", DocumentContentGate.normalizeLoose("إجمالي"))
        assertEquals("invoice", DocumentContentGate.normalizeLoose("INVOICE"))
    }

    @Test
    fun normalizeWords_replacesPunctuationWithSpaces() {
        assertEquals("فاتوره رقم 12", DocumentContentGate.normalizeWords("فاتورة: رقم#12"))
    }
}
