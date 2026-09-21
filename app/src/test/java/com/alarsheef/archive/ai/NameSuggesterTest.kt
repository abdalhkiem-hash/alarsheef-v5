package com.alarsheef.archive.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameSuggesterTest {

    @Test
    fun documentKeywordFromOcr_producesContractSuggestion() {
        val result = NameSuggester.suggestFor(emptyList(), "هذا عقد إيجار الوحدة السكنية", emptyList())
        assertTrue(result.contains("وثائق عقود"))
    }

    @Test
    fun invoiceKeywordFromOcr_producesInvoicesSuggestion() {
        val result = NameSuggester.suggestFor(emptyList(), "فاتورة رقم 123 إجمالي 50 ريال", emptyList())
        assertTrue(result.contains("فواتير"))
    }

    @Test
    fun dominantScreenshotLabel_producesScreenshotsSuggestion() {
        val result = NameSuggester.suggestFor(
            labels = listOf("لقطة شاشة", "لقطة شاشة", "مستند"),
            ocrText = "",
            faceNames = emptyList()
        )
        assertTrue(result.contains("لقطات شاشة"))
    }

    @Test
    fun namedFaceGroups_producePersonSuggestion() {
        val result = NameSuggester.suggestFor(emptyList(), "", listOf("أحمد", "سارة"))
        assertTrue(result.contains("صور أحمد سارة"))
    }

    @Test
    fun blankFaceNames_areIgnored() {
        val result = NameSuggester.suggestFor(emptyList(), "", listOf("   "))
        assertTrue(result.none { it.startsWith("صور ") })
    }

    @Test
    fun noSignals_returnsEmpty() {
        assertTrue(NameSuggester.suggestFor(emptyList(), "", emptyList()).isEmpty())
    }

    @Test
    fun neverReturnsMoreThanThreeSuggestions() {
        val result = NameSuggester.suggestFor(
            labels = listOf("مستند", "لقطة شاشة", "طبيعة", "أبيض وأسود"),
            ocrText = "عقد شهادة فاتورة جواز رخصة",
            faceNames = listOf("أحمد")
        )
        assertTrue(result.size <= 3)
    }

    @Test
    fun genericFallback_usedWhenOnlyLabelsExist() {
        val result = NameSuggester.suggestFor(labels = listOf("حيوان"), ocrText = "", faceNames = emptyList())
        assertEquals(listOf("صور حيوان"), result)
    }
}