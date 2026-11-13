package com.alarsheef.archive.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** مقياس جودة OCR: نصوص حقيقية من قياس Tesseract على صور عربية مائلة ومشوّشة. */
class OcrQualityTest {

    private fun verdict(text: String?, conf: Int? = null) = OcrQuality.assess(text, conf)

    @Test
    fun cleanArabicInvoiceText_isOk() {
        assertEquals(
            OcrQuality.Verdict.OK,
            verdict("فاتورة مبيعات رقم 4521 التاريخ 2026/11/13 العميل: عبدالله محمد الحمادي المبلغ الإجمالي 345,000 ريال شكراً لتعاملكم معنا")
        )
        assertEquals(
            OcrQuality.Verdict.OK,
            verdict("حوالة واردة رقم العملية 79338451206 المرسل: حميد سلطان أحمد المستفيد: مكتب الدكتور أيمن المبلغ 1,250.00 ريال يمني")
        )
    }

    @Test
    fun latinDocumentText_isOk() {
        assertEquals(OcrQuality.Verdict.OK, verdict("Invoice No 4521 Total 345.00 USD Thank you for your business"))
    }

    @Test
    fun longReferenceNumberAlone_isOk() {
        assertEquals(OcrQuality.Verdict.OK, verdict("79338451206"))
    }

    @Test
    fun emptyOrTinyText_isEmpty() {
        assertEquals(OcrQuality.Verdict.EMPTY, verdict(null))
        assertEquals(OcrQuality.Verdict.EMPTY, verdict("   "))
        assertEquals(OcrQuality.Verdict.EMPTY, verdict("12 ـ"))
    }

    /** ما أرجعه Tesseract لصورة هاتف مائلة: حروف لاتينية متفرقة وعلامات اتجاه ممزّقة. */
    @Test
    fun scatteredGarbageFromTiltedPhoto_isPoor() {
        val garbage = "1  agai aoe\u201D \u200Fقور\u200E  oo 'g \u200Fا\u200E  go J 345) 000 \u200Fمعنا\u200E  المبلغ \u200E١\u200F ل \u200Eas\u200F"
        assertEquals(OcrQuality.Verdict.POOR, verdict(garbage))
    }

    @Test
    fun arabicMixedWithRandomLatin_isPoor() {
        assertEquals(OcrQuality.Verdict.POOR, verdict("رقم deal ts ae ds ko lo mn pq rs tu وزن ab cd ef gh"))
    }

    @Test
    fun lowConfidenceIsPoorEvenIfTextLooksFine() {
        val text = "فاتورة مبيعات رقم 4521 التاريخ 2026/11/13 العميل عبدالله محمد"
        assertEquals(OcrQuality.Verdict.OK, verdict(text, conf = 90))
        assertEquals(OcrQuality.Verdict.POOR, verdict(text, conf = 40))
    }

    @Test
    fun isUsable_onlyForOk() {
        assertTrue(OcrQuality.isUsable("حوالة واردة رقم العملية 79338451206 المرسل حميد سلطان أحمد"))
        assertFalse(OcrQuality.isUsable(""))
        assertFalse(OcrQuality.isUsable(null))
    }
}
