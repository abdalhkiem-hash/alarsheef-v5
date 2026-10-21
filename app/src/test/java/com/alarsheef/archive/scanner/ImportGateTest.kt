package com.alarsheef.archive.scanner

import com.alarsheef.archive.ai.DocumentShape
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.scanner.ImportGate.Decision
import com.alarsheef.archive.scanner.ImportGate.TextEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * قرار بوابة الاستيراد: (شكل الصورة × وجه) ثم (قوة الدليل × ما قاله النص).
 *
 * المطلب الذي تحميه هذه الاختبارات: في وضع "المستندات فقط" لا تظهر صورة شخصية أو
 * عائلية ضمن الأرشيف — لا بوسم «لقطة شاشة» الشكلي، ولا لأن OCR غير متاح لحظتها.
 * (تحليل البكسلات نفسه — [DocumentShape] من ملف — يُختبر على جهاز.)
 */
class ImportGateTest {

    private fun decide(shape: DocumentShape?, hasFace: Boolean, excludePersonal: Boolean = true) =
        ImportGate.decide(shape, hasFace, excludePersonal, documentsOnly = true)

    /** هل تظهر الصورة ضمن الأرشيف (لا مخفية للمراجعة ولا مرفوضة)؟ */
    private fun isVisible(status: ContentVerified?) =
        status == ContentVerified.ACCEPTED || status == ContentVerified.UNVERIFIED

    // ---------- المرحلة 1: الشكل + الوجه (المستندات فقط) ----------

    @Test
    fun landscapesAndUndecodable_areRejected_withOrWithoutFace() {
        for (face in listOf(false, true)) {
            assertEquals(Decision.REJECT, decide(DocumentShape.NOT_DOCUMENT, face))
            assertEquals(Decision.REJECT, decide(null, face))
        }
    }

    @Test
    fun documentShape_withoutFace_isStrong() {
        assertEquals(Decision.STRONG, decide(DocumentShape.DOCUMENT, hasFace = false))
    }

    @Test
    fun screenshotOrLooseShape_withoutFace_isOnlyWeakEvidence() {
        assertEquals(Decision.WEAK, decide(DocumentShape.SCREENSHOT, hasFace = false))
        assertEquals(Decision.WEAK, decide(DocumentShape.PLAUSIBLE, hasFace = false))
    }

    @Test
    fun softBackgroundPortrait_screenshotShapeWithFace_isNoLongerAutoAccepted() {
        // أصل التسرّب: وسم «لقطة شاشة» = حواف ضعيفة فقط، وكان يُقبل حتى مع وجه
        assertEquals(Decision.FACE_WEAK, decide(DocumentShape.SCREENSHOT, hasFace = true))
    }

    @Test
    fun documentShapeWithFace_needsTextProof_notAutoAccepted() {
        // هوية/جواز محتملة — أو شخص بثوب أبيض أمام جدار فاتح
        assertEquals(Decision.FACE_STRONG, decide(DocumentShape.DOCUMENT, hasFace = true))
    }

    @Test
    fun looseShapeWithFace_isRejected() {
        assertEquals(Decision.REJECT, decide(DocumentShape.PLAUSIBLE, hasFace = true))
    }

    @Test
    fun documentsOnly_ignoresExcludePersonalSwitch_faceAlwaysMatters() {
        for (shape in DocumentShape.values()) {
            assertEquals(
                decide(shape, hasFace = true, excludePersonal = true),
                decide(shape, hasFace = true, excludePersonal = false)
            )
        }
    }

    // ---------- المفتاحان أُزيلا: العلم لا يفتح المعرض ----------

    @Test
    fun documentsOnlyFlag_cannotAdmitNonDocuments() {
        assertEquals(Decision.REJECT, ImportGate.decide(null, hasFace = false, excludePersonalPhotos = false, documentsOnly = false))
        assertEquals(
            Decision.REJECT,
            ImportGate.decide(DocumentShape.NOT_DOCUMENT, hasFace = false, excludePersonalPhotos = false, documentsOnly = false)
        )
        assertEquals(
            Decision.STRONG,
            ImportGate.decide(DocumentShape.DOCUMENT, hasFace = false, excludePersonalPhotos = false, documentsOnly = false)
        )
        assertEquals(
            Decision.FACE_STRONG,
            ImportGate.decide(DocumentShape.DOCUMENT, hasFace = true, excludePersonalPhotos = false, documentsOnly = false)
        )
    }

    @Test
    fun allPhotosMode_withoutText_isNotImported() {
        assertNull(ImportGate.finalStatus(Decision.STRONG, TextEvidence.NOT_CHECKED))
        assertNull(ImportGate.finalStatus(Decision.FACE_WEAK, TextEvidence.NO_TEXT))
    }

    // ---------- المرحلة 2: الحالة النهائية ----------

    @Test
    fun rejectDecision_isNeverImported_whateverTheTextSays() {
        for (evidence in TextEvidence.values()) {
            assertNull(evidence.name, ImportGate.finalStatus(Decision.REJECT, evidence))
        }
    }

    @Test
    fun insufficientTextOnDocumentWithoutFace_goesToReview() {
        assertEquals(
            ContentVerified.AMBIGUOUS,
            ImportGate.finalStatus(Decision.STRONG, TextEvidence.REJECTED, DocumentShape.DOCUMENT)
        )
    }

    @Test
    fun insufficientTextWithFaceOrWeakShape_staysOut() {
        assertNull(ImportGate.finalStatus(Decision.FACE_STRONG, TextEvidence.REJECTED, DocumentShape.DOCUMENT))
        assertNull(ImportGate.finalStatus(Decision.FACE_WEAK, TextEvidence.REJECTED, DocumentShape.SCREENSHOT))
        assertNull(ImportGate.finalStatus(Decision.WEAK, TextEvidence.REJECTED, DocumentShape.SCREENSHOT))
        assertNull(ImportGate.finalStatus(Decision.WEAK, TextEvidence.REJECTED, DocumentShape.PLAUSIBLE))
        assertNull(ImportGate.finalStatus(Decision.STRONG, TextEvidence.REJECTED, DocumentShape.SCREENSHOT))
        assertNull(ImportGate.finalStatus(Decision.STRONG, TextEvidence.REJECTED, shape = null))
        assertNull(ImportGate.finalStatus(Decision.REJECT, TextEvidence.REJECTED, DocumentShape.DOCUMENT))
    }

    @Test
    fun acceptedText_importsAsAccepted_evenWithFace() {
        // المستند أقوى من الوجه — حين يثبته النص: هوية، جواز، فاتورة باسم شخص
        for (decision in listOf(Decision.STRONG, Decision.WEAK, Decision.FACE_STRONG, Decision.FACE_WEAK)) {
            assertEquals(decision.name, ContentVerified.ACCEPTED, ImportGate.finalStatus(decision, TextEvidence.ACCEPTED))
        }
    }

    @Test
    fun partialTextEvidence_goesToReview_exceptSoftPortrait() {
        for (decision in listOf(Decision.STRONG, Decision.WEAK, Decision.FACE_STRONG)) {
            assertEquals(decision.name, ContentVerified.AMBIGUOUS, ImportGate.finalStatus(decision, TextEvidence.PARTIAL))
        }
        assertNull(ImportGate.finalStatus(Decision.FACE_WEAK, TextEvidence.PARTIAL))
    }

    @Test
    fun noTextOrUnchecked_isNeverImported() {
        for (decision in listOf(Decision.STRONG, Decision.WEAK, Decision.FACE_STRONG, Decision.FACE_WEAK)) {
            for (evidence in listOf(TextEvidence.NO_TEXT, TextEvidence.NOT_CHECKED)) {
                assertNull("$decision $evidence", ImportGate.finalStatus(decision, evidence, DocumentShape.DOCUMENT))
            }
        }
    }

    @Test
    fun unreadableDocumentWithoutFace_goesToReview() {
        assertEquals(
            ContentVerified.AMBIGUOUS,
            ImportGate.finalStatus(Decision.STRONG, TextEvidence.UNREADABLE, DocumentShape.DOCUMENT)
        )
    }

    @Test
    fun unreadableWithFaceOrWeakShape_staysOut() {
        assertNull(ImportGate.finalStatus(Decision.FACE_STRONG, TextEvidence.UNREADABLE, DocumentShape.DOCUMENT))
        assertNull(ImportGate.finalStatus(Decision.FACE_WEAK, TextEvidence.UNREADABLE, DocumentShape.SCREENSHOT))
        assertNull(ImportGate.finalStatus(Decision.WEAK, TextEvidence.UNREADABLE, DocumentShape.SCREENSHOT))
        assertNull(ImportGate.finalStatus(Decision.WEAK, TextEvidence.UNREADABLE, DocumentShape.PLAUSIBLE))
        assertNull(ImportGate.finalStatus(Decision.STRONG, TextEvidence.UNREADABLE, shape = null))
        assertNull(ImportGate.finalStatus(Decision.REJECT, TextEvidence.UNREADABLE, DocumentShape.NOT_DOCUMENT))
    }

    // ---------- الخاصية المطلوبة على كامل المصفوفة ----------

    @Test
    fun imageWithFace_isVisibleOnlyWhenTextProvesItIsADocument() {
        for (shape in DocumentShape.values()) {
            val decision = decide(shape, hasFace = true)
            for (evidence in TextEvidence.values()) {
                val status = ImportGate.finalStatus(decision, evidence)
                if (evidence != TextEvidence.ACCEPTED) {
                    assertFalse("وجه + $shape + $evidence ظهر ضمن الأرشيف", isVisible(status))
                }
            }
        }
    }

    @Test
    fun weakShapeEvidence_isVisibleOnlyWhenTextProvesItIsADocument() {
        for (shape in listOf(DocumentShape.SCREENSHOT, DocumentShape.PLAUSIBLE)) {
            val decision = decide(shape, hasFace = false)
            for (evidence in TextEvidence.values()) {
                val status = ImportGate.finalStatus(decision, evidence)
                if (evidence != TextEvidence.ACCEPTED) {
                    assertFalse("$shape + $evidence ظهر ضمن الأرشيف", isVisible(status))
                }
            }
        }
    }

    @Test
    fun nothingIsMarkedRejectedInDatabase_rejectionMeansNotImported() {
        for (decision in Decision.values()) {
            for (evidence in TextEvidence.values()) {
                assertNotEquals(ContentVerified.REJECTED, ImportGate.finalStatus(decision, evidence))
            }
        }
    }

    // ---------- رد OCR "لا يوجد نص" ----------

    @Test
    fun noTextReply_exactAndCloseVariants() {
        assertTrue(ImportGate.isNoTextReply("لا يوجد نص"))
        assertTrue(ImportGate.isNoTextReply("  لا يوجد نص.\n"))
        assertTrue(ImportGate.isNoTextReply("«لا يوجد نص»"))
        assertTrue(ImportGate.isNoTextReply("لا يوجد نص في الصورة"))
    }

    @Test
    fun noTextReply_realTextIsNotMistaken() {
        assertFalse(ImportGate.isNoTextReply("فاتورة ضريبية رقم 12345"))
        assertFalse(ImportGate.isNoTextReply(""))
        // مستند حقيقي يحوي العبارة ضمن نص طويل
        assertFalse(
            ImportGate.isNoTextReply("إشعار: لا يوجد نص إضافي على ظهر هذه الفاتورة. المبلغ الإجمالي 345.00 ريال")
        )
    }

    // ---------- البوابة الآمنة (المستندات فقط): لا يضيع مستند صحيح أبدًا ----------

    /** هل تُؤرشف الصورة (تبقى في المصدر إذا كانت false — لا رفض ولا إخفاء). */
    private fun archives(shape: DocumentShape?, hasText: Boolean?) =
        ImportGate.safeToArchive(shape, hasText)

    @Test
    fun safeToArchive_anyDocumentShape_isKept_evenWithoutText() {
        for (shape in listOf(DocumentShape.DOCUMENT, DocumentShape.SCREENSHOT, DocumentShape.PLAUSIBLE)) {
            for (text in listOf(true, false, null)) {
                assertTrue("$shape + نص=$text ضاع من الأرشيف", archives(shape, text))
            }
        }
    }

    @Test
    fun safeToArchive_notDocument_withConfirmedNoText_isLeftInSource() {
        assertFalse(archives(DocumentShape.NOT_DOCUMENT, false))
    }

    @Test
    fun safeToArchive_notDocument_shapeButTextFound_isKept() {
        // صورة شخصية تُصوّر فيها ورقة مثلاً: النص ينقذها (لا نحرم مستندًا)
        assertTrue(archives(DocumentShape.NOT_DOCUMENT, true))
    }

    @Test
    fun safeToArchive_unreadableFailsOpen_keepsFile() {
        // تعذّر القراءة (لا OCR / خطأ فك) لا يُسقط شيئًا — لا نص قاطع
        assertTrue(archives(DocumentShape.NOT_DOCUMENT, null))
        assertTrue(archives(null, false))
        assertTrue(archives(null, true))
        assertTrue(archives(null, null))
    }
}
