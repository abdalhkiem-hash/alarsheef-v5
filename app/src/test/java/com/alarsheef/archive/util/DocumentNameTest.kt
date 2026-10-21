package com.alarsheef.archive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** الاسم من نص الورقة، على الحالات التي ظهرت في مجلد الإدخال. */
class DocumentNameTest {

    private fun name(text: String, types: List<DocumentName.UserType> = emptyList(), page: Int = 1): String? =
        DocumentName.compose(
            DocumentName.read(text, types),
            fallbackDate = "2026-01-01",
            pageNumber = page,
        )

    @Test
    fun creditAndDebit_sameDocumentNumber_stayTwoNames() {
        val credit = """
            سند إشعار دائن
            عميلنا: معوض المعروفي
            03-09-2026
            61,764 ريال سعودي
            رقم المستند 2441
            حساب 124311362
        """.trimIndent()
        val debit = """
            سند إشعار مدين
            عميلنا: معوض المعروفي
            03-09-2026
            8,646,960 ريال يمني
            رقم المستند 2441
            رقم الحوالة 555001
        """.trimIndent()
        assertEquals("إشعار-دائن_معوض-المعروفي_2026-09-03_61764ر.س_2441", name(credit))
        assertEquals("إشعار-مدين_معوض-المعروفي_2026-09-03_8646960ر.ي_2441", name(debit))
        assertNotEquals(name(credit), name(debit))
        assertEquals("555001", DocumentName.read(debit).transferNumber)
    }

    @Test
    fun sameParty_differentNoticeNumbers_andCurrencies() {
        val yer = """
            إشعار دائن رقم 45
            مكتب الدكتور ايمن
            19/09/2026
            28,178 ريال يمني
        """.trimIndent()
        val usd = """
            إشعار دائن رقم 44
            مكتب الدكتور ايمن
            19/09/2026
            14,513.47 دولار
        """.trimIndent()
        assertEquals("إشعار-دائن_مكتب-الدكتور-ايمن_2026-09-19_28178ر.ي_45", name(yer))
        assertEquals("إشعار-دائن_مكتب-الدكتور-ايمن_2026-09-19_14513.47دولار_44", name(usd))
    }

    @Test
    fun accountAmount_notCommission_andPaperNumberBeatsTransfer() {
        val text = """
            إرسال حوالة
            شركة الشرع
            الرقم 45977
            رقم الحوالة 68689912
            مبلغ الحساب 349,200 ريال
            العمولة 800
            الإجمالي 350,000
        """.trimIndent()
        val fields = DocumentName.read(text)
        assertEquals("45977", fields.nameReference)
        assertEquals("68689912", fields.transferNumber)
        assertEquals("349200", fields.amount)
        assertEquals("حوالة_شركة-الشرع_2026-01-01_349200ريال_45977", name(text))
    }

    @Test
    fun documentNumber_beatsTransferNumber() {
        val text = """
            سند إشعار مدين
            القاسمي
            رقم المستند 89619
            رقم الحوالة 989951831
            2,310 دولار
        """.trimIndent()
        assertEquals("إشعار-مدين_القاسمي_2026-01-01_2310دولار_89619", name(text))
    }

    @Test
    fun transferNumber_usedOnlyWhenNoPaperNumber() {
        val text = """
            حوالات
            رقم الحوالة 93015096
            مرجع المصدر 932957043695
            -14,700 ريال
            الحالة معلّقة
        """.trimIndent()
        assertEquals("93015096", DocumentName.read(text).nameReference)
        assertEquals("حوالة_2026-01-01_14700ريال_93015096", name(text))
    }

    @Test
    fun operationCode_keepsLetters() {
        val text = """
            تحويل لمشترك
            كود العملية Ehcz405b
            -30,000 ريال
            الحالة مقبولة
        """.trimIndent()
        assertEquals("حوالة_2026-01-01_30000ريال_Ehcz405b", name(text))
    }

    @Test
    fun reference_illegalCharacter_becomesHyphen() {
        val text = """
            إشعار الجوال
            المرجع FT262766X9ZG|MHU
            29,900 ريال
        """.trimIndent()
        assertEquals("إشعار_2026-01-01_29900ريال_FT262766X9ZG-MHU", name(text))
    }

    @Test
    fun invoice_usesTotalWhenNoAccountAmount() {
        val text = """
            فاتورة
            المقبولي
            رقم الفاتورة 23377
            الإجمالي 46,500 ريال يمني
            10/10/2026
        """.trimIndent()
        assertEquals("فاتورة_المقبولي_2026-10-10_46500ر.ي_23377", name(text))
    }

    @Test
    fun unknownType_archivesAsDocument_untilTheUserAddsIt() {
        val text = """
            أمر شراء
            المخزن الرئيسي
            29-09-2026
            الصافي 2,557.17 دولار
        """.trimIndent()
        assertEquals("مستند_المخزن-الرئيسي_2026-09-29_2557.17دولار", name(text))
        val types = listOf(DocumentName.UserType("أمر شراء", listOf("أمر شراء")))
        assertEquals("أمر-شراء_المخزن-الرئيسي_2026-09-29_2557.17دولار", name(text, types))
    }

    @Test
    fun missingAmount_isDropped_printedNumberRemains() {
        val text = """
            أمر صرف علاجات
            صبر للزراعة
            05-10-2026
            الرقم 12560
        """.trimIndent()
        val types = listOf(DocumentName.UserType("أمر صرف", listOf("أمر صرف")))
        assertEquals("أمر-صرف_صبر-للزراعة_2026-10-05_12560", name(text, types))
    }

    @Test
    fun secondPdfPage_sharesTheNameWithSuffix() {
        val text = """
            فاتورة
            المقبولي
            رقم الفاتورة 23377
            الإجمالي 46,500 ريال يمني
            10/10/2026
        """.trimIndent()
        assertEquals("فاتورة_المقبولي_2026-10-10_46500ر.ي_23377_ص2", name(text, page = 2))
    }

    @Test
    fun chat_hasNoName() {
        assertNull(name("صباح الخير كيف حالك"))
    }
}
