package com.alarsheef.archive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** الاسم من نص الورقة، على الحالات التي ظهرت في مجلد الإدخال. */
class DocumentNameTest {

    private fun name(text: String, types: List<DocumentName.UserType> = emptyList(), page: Int = 1): String? =
        DocumentName.compose(
            DocumentName.read(text, types),
            fallbackDate = "2026-12-31",
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
        assertEquals("حوالة_شركة-الشرع_2026-12-31_349200ريال_45977", name(text))
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
        assertEquals("إشعار-مدين_القاسمي_2026-12-31_2310دولار_89619", name(text))
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
        assertEquals("حوالة_2026-12-31_14700ريال_93015096", name(text))
    }

    @Test
    fun operationCode_keepsLetters() {
        val text = """
            تحويل لمشترك
            كود العملية Ehcz405b
            -30,000 ريال
            الحالة مقبولة
        """.trimIndent()
        assertEquals("حوالة_2026-12-31_30000ريال_Ehcz405b", name(text))
    }

    @Test
    fun reference_illegalCharacter_becomesHyphen() {
        val text = """
            إشعار الجوال
            المرجع FT262766X9ZG|MHU
            29,900 ريال
        """.trimIndent()
        assertEquals("إشعار_2026-12-31_29900ريال_FT262766X9ZG-MHU", name(text))
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

    // ---------- حالات حقيقية من الهاتف (24/11/2026): OCR يفصل عمود العناوين عن القيم ----------

    @Test
    fun walletScreenshot_labelsWithoutValues_giveNoPartyAndNoReference() {
        // كان الاسم: حوالة_إلى_2026-11-22_الي
        val text = """
            حوالات

            رقم الحوالة / كود العملية
            إلى

            من محفظة

            لمبلغ

            لعمولة

            لحالة

            مرجع المصدر

            المرسل
            لاسم الأول
            سم الاب
            سم الجد

            لجوال

            لاسم الرباعي
            لجوال

            36325354

            AINajm

            777700239
        """.trimIndent()
        val fields = DocumentName.read(text)
        assertNull(fields.party)
        assertNull(fields.nameReference)
        assertEquals("حوالة", fields.type)
    }

    @Test
    fun formLabelLine_isNotTakenAsParty_orReference() {
        // كان الاسم: مستند_مرجع-المصدر_2026-11-22_مرجع
        val text = """
            المبلغ

            الحالة

            وقت العملية

            رقم الحوالة / كود العملية
            مرجع المصدر

            من

            إلى

            المنظمة المصدرية

            منظمة الوجهة
        """.trimIndent()
        val fields = DocumentName.read(text)
        assertNull(fields.party)
        assertNull(fields.nameReference)
        assertNull(name(text))
    }

    @Test
    fun noticeNumber_onNextLineAfterDate_skipsTheDate() {
        // كان الرقم «2026» من التاريخ بدل رقم الإشعار 612306
        val text = """
            شركة القاسمي الدولية للصرافة والتحويلات
            الذار: رقم الإشعار
            2026/11/22 612306
            رقم الحوالة: 983272875
        """.trimIndent()
        val fields = DocumentName.read(text)
        assertEquals("612306", fields.nameReference)
        assertEquals("983272875", fields.transferNumber)
        assertTrue(fields.party.orEmpty().contains("القاسمي"))
    }

    @Test
    fun mobileNumber_nextToLabel_isNotTheReference() {
        // كان الاسم: مستند_2026-11-22_777700239 (جوال من سطر «رقم الحركة» المشوّش)
        val text = """
            خدمات الدواجن
            رقم الحركة: <= 777700239 - 777002134:
        """.trimIndent()
        assertNull(DocumentName.read(text).nameReference)
        assertEquals("326", DocumentName.read("رقم الحركة: 326 - 777700239").nameReference)
    }

    // ---------- نصوص Gemini الحقيقية لصور «مجلد الإدخال» (24/11/2026) ----------

    @Test
    fun handwrittenReceipt_amountBesideCurrency_notTheReceiptNumber_andWrongYearIgnored() {
        // كان: إيصال_مكتب-الغزي-والشامي_2021-01-10_46230ريال_46230
        val text = """
            مكتب الغزي والشامي
            لتسويق الدواجن
            لصاحبه / عادل أحمد الغزي وشركاه
            صنعاء - السوق المركزي
            سيارة: ٧١١٩٢٢٤٣٤ - ٧٣٠١٠٧٤٤٨
            الرقم: 46230
            ريال
            ١٥,٠٠٠
            سند قبض/تسويق
            التاريخ: ١٠ / ١ / ٢٠٢١
            إستلمت من الأخ / الدكتور أيمن المورد المحترم
            مبلغ وقدره: خمسة عشر ألف لا غير
        """.trimIndent()
        val fields = DocumentName.read(text)
        assertEquals("15000", fields.amount)
        assertEquals("46230", fields.nameReference)
        assertEquals(
            "إيصال_مكتب-الغزي-والشامي_2026-10-01_15000ريال_46230",
            DocumentName.compose(fields, fallbackDate = "2026-10-01", gapDate = "٢٠٢١/١/١٠"),
        )
    }

    @Test
    fun exchangeCreditNotice_accountAmountAcrossCurrencyColumn() {
        // كان: إشعار-دائن_معوض-المعروفي_2026-09-03_8646960ر.ي_2441 (المبلغ المقابل من البيان)
        val text = """
            معوض المعروفي للصرافة والتحويلات
            رقم المستند : 2441
            سند إشعار دائن
            التاريخ : 03-09-2026
            عميلنا
            ايمن نجيب قاسم فارع
            رقم الحساب :
            124311362
            مبلغ الحساب
            عملة الحساب
            61,764
            سعودي
            واحد وستون ألفاً، وسبعمائة وأربعة وستون سعودي لا غير
            البيان
            بيع حساب سعر 140 ويقابلها 8,646,960 ريال يمني الملاحظات :
        """.trimIndent()
        val fields = DocumentName.read(text)
        assertEquals("61764", fields.amount)
        assertEquals("ر.س", fields.currency)
    }

    @Test
    fun crowdedLetterhead_phoneIsNotAmount_shopPrefixIsTheParty() {
        // كان: فاتورة_أمام-الهتار-للصرافه_2026-09-22_777224885ر.ي_5092
        val text = """
            Desert Friends
            فرع شارع تعز - بجوار بوابة دار رعاية الأيتام
            أمام الهتار للصرافه
            تلفون المعرض : 01616154
            التاريخ : ٢٠٢٢/٩/٢٢م
            الرقم : 5092
            أصدقاء الصحراء
            القطري
            محلات أصدقاء الصحراء
            القطري
            خدمة العملاء : 77724999 - 777224885
            ر.ي
            فاتورة بيع أجل
            المطلوب من الأخ الأخوه مكتب الدكتور ايمن المحترم
        """.trimIndent()
        assertEquals(
            "فاتورة_محلات-أصدقاء-الصحراء_2026-09-22_5092",
            DocumentName.compose(DocumentName.read(text), fallbackDate = "2026-09-22"),
        )
    }

    @Test
    fun weakShortLineParty_givesWayToCloudVendor() {
        // كان: مستند_المحترمون_2026-09-29_09384
        val text = """
            Super Feed For Fodder Manufacture
            الرقم: 09384
            المحترمون
            إرسالية بضاعة
            التاريخ: ٢٠٢٣ / ٩ / ٢٩ م
            الإخوة / د. حامد العبسي
        """.trimIndent()
        val fields = DocumentName.read(text)
        assertEquals(false, fields.partyStrong)
        assertEquals(
            "مستند_سوبر-فيد-لصناعة-الأعلاف_2026-09-29_09384",
            DocumentName.compose(fields, fallbackDate = "2026-09-29", gapVendor = "سوبر فيد لصناعة الأعلاف / SUPER FEED"),
        )
    }

    @Test
    fun paperDate_mustBeNearTheArchiveDay() {
        assertTrue(DocumentName.plausibleDate("2026-10-01", "2026-10-01"))
        assertTrue(DocumentName.plausibleDate("2026-02-01", "2026-10-01"))
        assertTrue(!DocumentName.plausibleDate("2021-01-10", "2026-10-01"))
        assertTrue(!DocumentName.plausibleDate("2026-10-05", "2026-10-01"))
    }

    @Test
    fun chat_hasNoName() {
        assertNull(name("صباح الخير كيف حالك"))
    }
}
