package com.alarsheef.archive.ai

import org.json.JSONObject
import java.io.File

/** بيانات فاتورة/إيصال مستخرجة سحابيًا — كل الحقول اختيارية (null إن لم تكن فاتورة). */
data class InvoiceInfo(
    val vendor: String?,
    val date: String?,
    val amount: String?,
    val currency: String?,
    val details: String?,
    /** رقم المرجع كما كُتب (رقم الفاتورة/الحوالة/العملية). */
    val reference: String? = null,
)

/** نتيجة التصنيف السحابي: وسوم + وصف عربي + فاتورة اختيارية. */
data class GeminiClassification(
    val labels: List<ClassifiedLabel>,
    val description: String,
    val invoice: InvoiceInfo?,
    /** النص الكامل للصورة، يُعاد فقط حين طُلب مع التصنيف في نداء واحد. */
    val text: String? = null,
)

/**
 * التصنيف السحابي عبر Gemini: وسوم عربية أدقّ من المحلّل الإحصائي +
 * وصف للصورة + استخراج بيانات الفاتورة تلقائيًا عند وجودها.
 *
 * يُستدعى من نظام الهجين في AiAnalysisWorker فقط عندما تكون الصورة غامضة
 * أو مستندًا يستحق وصفًا/فاتورة — بعد فشل المحلّل المحلي أو ثقته المنخفضة.
 */
object GeminiClassifier {

    /**
     * @param includeText يطلب مع التصنيف النص الحرفي الكامل في **نداء واحد** بدل نداء OCR منفصل،
     *        فينخفض الاستهلاك من محاولتين إلى واحدة لكل مستند. الصورة تُرسل بدقة أعلى حينها.
     */
    suspend fun classify(file: File, usePro: Boolean, includeText: Boolean = false): GeminiClassification? {
        val json = GeminiRepository.generateContent(
            prompt = if (includeText) CLASSIFY_PROMPT + "\n" + TEXT_PROMPT_ADDON else CLASSIFY_PROMPT,
            image = file,
            usePro = usePro,
            jsonSchema = if (includeText) SCHEMA_WITH_TEXT else SCHEMA,
            maxImageDimension = if (includeText) 1600 else 1024,
        ) ?: return null
        return parse(json)
    }

    private fun parse(json: String): GeminiClassification? = runCatching {
        val obj = JSONObject(json)
        val fullText = obj.optString("fullText").trim().takeIf { it.isNotBlank() && it != "null" }

        val labels = obj.optJSONArray("labels")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = item.optString("label").trim()
                if (label.isEmpty() || label == "null") return@mapNotNull null
                val confidence = item.optDouble("confidence", 0.75).toFloat().coerceIn(0f, 1f)
                ClassifiedLabel(label, confidence)
            }
        }.orEmpty()
            .distinctBy { it.label }
            .sortedByDescending { it.confidence }
            .take(6)

        val description = obj.optString("description").trim().takeIf {
            it.isNotBlank() && it != "null"
        }.orEmpty()

        val invoice = obj.optJSONObject("invoice")?.let { node ->
            fun field(key: String): String? = node.optString(key).trim()
                .takeIf { it.isNotEmpty() && it != "null" && it != "-" && it != "لا يوجد" }
            val info = InvoiceInfo(
                vendor = field("vendor"),
                date = field("date"),
                amount = field("amount"),
                currency = field("currency"),
                details = field("details"),
                reference = field("reference"),
            )
            // لا نعتبر "فاتورة" ما لم يُذكر جهة أو مبلغ أو بنود أو رقم مرجع فعلي
            if (info.vendor == null && info.amount == null && info.details == null && info.reference == null) null else info
        }

        GeminiClassification(labels, description, invoice, fullText)
    }.getOrNull()?.takeIf { it.labels.isNotEmpty() || it.description.isNotBlank() }

    /** المخطط نفسه مع حقل النص الكامل (غير مطلوب، فلا يُفشل الرد إن غاب). */
    private val SCHEMA_WITH_TEXT: String by lazy {
        SCHEMA.replace(
            "\"description\": {\"type\": \"STRING\"},",
            "\"description\": {\"type\": \"STRING\"},\n    \"fullText\": {\"type\": \"STRING\"},"
        )
    }

    private val TEXT_PROMPT_ADDON = """

        - fullText: كل النص الظاهر في الصورة حرفيًا بلغته الأصلية (خاصة العربية بترقيمها العربي-الهندي)
          مع الحفاظ على أسطره وترتيبه، بلا ترجمة ولا تلخيص ولا تعليق.
          وإن لم يكن في الصورة أي نص فاكتب بالضبط: لا يوجد نص
    """.trimIndent()

    private val SCHEMA = """
        {
          "type": "OBJECT",
          "properties": {
            "labels": {
              "type": "ARRAY",
              "items": {
                "type": "OBJECT",
                "properties": {
                  "label": {"type": "STRING"},
                  "confidence": {"type": "NUMBER"}
                },
                "required": ["label", "confidence"]
              }
            },
            "description": {"type": "STRING"},
            "invoice": {
              "type": "OBJECT",
              "properties": {
                "vendor": {"type": "STRING"},
                "date": {"type": "STRING"},
                "amount": {"type": "STRING"},
                "currency": {"type": "STRING"},
                "details": {"type": "STRING"},
                "reference": {"type": "STRING"}
              }
            }
          },
          "required": ["labels", "description"]
        }
    """.trimIndent()

    private val CLASSIFY_PROMPT = """
        حلّل الصورة التالية من أرشيف مستندات عائلي وأعِد JSON فقط وفق المخطط المطلوب.
        - labels: حتى 6 وسوم عربية مختصرة (بدون أرقام تسلسلية) مع درجة ثقة من 0 إلى 1،
          من هذه العائلة قدر الإمكان: "مستند / ملف نصي"، "لقطة شاشة"، "فاتورة"، "إيصال"،
          "حركة بنكية"، "رسالة نصية"، "هوية / جواز"، "شهادة / دبلوم"، "طبيعة / حديقة"،
          "سماء / ماء"، "صورة شخص / وجه"، "مجموعة أشخاص"، "داخلي / ضوء دافئ"،
          "أبيض وأسود / تدرج رمادي".
        - description: وصف عربي دقيق من جملة واحدة لما تراه في الصورة.
        - invoice: إن كانت الصورة فاتورة أو إيصالًا ماليًا فاملئ:
          vendor (اسم الجهة أو المتجر)، date (تاريخ الإصدار كما هو مكتوب)،
          amount (المبلغ كما هو)، currency (العملة)، details (أهم البنود باختصارًا)،
          reference (رقم المرجع الرئيسي كما هو مكتوب: رقم الفاتورة أو رقم الحوالة أو كود/مرجع العملية،
          بالأرقام والحروف نفسها بلا تعديل؛ واتركه فارغًا إن لم يوجد رقم واضح).
          وإن لم تكن مالية فاترك invoice حقلًا فارغًا أو احذفه.
    """.trimIndent()
}
