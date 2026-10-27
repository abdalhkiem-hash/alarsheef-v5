package com.alarsheef.archive.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarsheef.archive.ai.OcrEngine
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.ui.theme.TealBright
import com.alarsheef.archive.work.AiAnalysisScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import com.alarsheef.archive.ai.GeminiHealth
import com.alarsheef.archive.ai.GeminiRepository
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * لوحة "الذكاء الاصطناعي" في القائمة الجانبية:
 * مفاتيح التشغيل المحلية الثلاث + قسم Gemini السحابي (تفعيل/نموذج/حالة المفتاح/الحصة اليومية)
 * + حالة العدد المعلق + زر التحليل الفوري + زر إعادة التحليل الكامل + زر فتح مجموعات الوجوه.
 */
@Composable
fun AiPanelContent(
    prefs: SettingsPreferences,
    repository: ArchiveRepository,
    scope: CoroutineScope,
    onOpenFaces: () -> Unit
) {
    val context = LocalContext.current
    val ocrEnabled by prefs.aiOcrEnabled.collectAsStateWithLifecycle(initialValue = true)
    val labelsEnabled by prefs.aiLabelsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val facesEnabled by prefs.aiFacesEnabled.collectAsStateWithLifecycle(initialValue = true)
    val pending by repository.observePendingAiCount().collectAsStateWithLifecycle(initialValue = 0)
    val groups by repository.observeFaceGroupRows().collectAsStateWithLifecycle(initialValue = emptyList())
    val cloudEnabled by prefs.geminiCloudEnabled.collectAsStateWithLifecycle(initialValue = true)
    val quota by prefs.geminiQuota.collectAsStateWithLifecycle(
        initialValue = com.alarsheef.archive.settings.GeminiQuota(0, GeminiRepository.DAILY_LIMIT)
    )
    val ocrAvailable = remember { OcrEngine.isAvailable(context) }
    val keyConfigured = remember { GeminiRepository.isConfigured() }
    var healthReport by remember { mutableStateOf<GeminiHealth.Report?>(null) }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
        PanelTitle("الذكاء الاصطناعي")
        Text(
            "فحص محلي سريع أولًا دون إنترنت، ومعه Gemini السحابي عند الحاجة فقط " +
                "للصور الغامضة واستخراج الفواتير ودقة النص العربي.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SettingRow("التصنيف التلقائي للمحتوى", labelsEnabled) { scope.launch { prefs.setAiLabelsEnabled(it) } }
        SettingRow("استخراج النصوص (OCR)", ocrEnabled) { scope.launch { prefs.setAiOcrEnabled(it) } }
        SettingRow("تجميع الصور حسب الوجوه", facesEnabled) { scope.launch { prefs.setAiFacesEnabled(it) } }

        if (!ocrAvailable) {
            Text(
                if (keyConfigured && cloudEnabled)
                    "تنبيه: لا نموذج نص على هذا الجهاز (Android 13-) — سيُعتمد النص السحابي العربي."
                else
                    "تنبيه: لا نموذج نص على هذا الجهاز (Android 13-) — يُتخطى OCR إلا بتفعيل Gemini السحابي.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        SubHead("Gemini السحابي")
        SettingRow("تفعيل التحليل السحابي (وصف وفواتير وOCR)", cloudEnabled) {
            scope.launch { prefs.setGeminiCloudEnabled(it) }
        }

        if (cloudEnabled) {
            NoteSmall("النموذج المستخدم: ${GeminiRepository.model()}", Modifier.padding(top = 4.dp))
            Text(
                if (keyConfigured)
                    "المفتاح مضبوط (يُقرأ من local.properties وقت البناء). اضغط «فحص الاتصال» للتأكد من صلاحيته."
                else
                    "المفتاح غير مضبوط — أنشئه من aistudio.google.com/apikey وضعه في local.properties باسم GEMINI_API_KEY ثم أعد البناء.",
                style = MaterialTheme.typography.bodySmall,
                color = if (keyConfigured) TealBright
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
            quota.let { q ->
                NoteSmall("استهلاك اليوم: ${q.used} من ${q.limit} استدعاء (يصفَّر كل يوم)", Modifier.padding(top = 4.dp))
            }
            BtnOutline(
                if (checking) "جارٍ الفحص…" else "فحص الاتصال بـ Gemini",
                onClick = {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            healthReport = GeminiHealth.check()
                            checking = false
                        }
                    }
                },
                modifier = Modifier.padding(top = 10.dp)
            )
            healthReport?.let { report ->
                if (!report.keyConfigured) {
                    Text(
                        GeminiHealth.message(GeminiHealth.Status.KEY_MISSING),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                report.results.forEach { r ->
                    val ok = GeminiHealth.isOk(r.status)
                    val seconds = String.format(java.util.Locale.ROOT, "%.1f", r.millis / 1000.0)
                    Text(
                        "${r.model}: ${GeminiHealth.message(r.status)}" + if (ok) " ($seconds ث)" else r.httpCode?.let { " [HTTP $it]" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) TealBright else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Text(
                "تُستدعى السحابة للصور الغامضة أو الثقة المنخفضة والمستندات فقط، " +
                    "ومحاولة واحدة لكل استدعاء لحماية حدك اليومي.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Text(
            if (pending > 0) "متبقٍ للتحليل: $pending صورة — يجري التحليل في الخلفية"
            else "كل الأرشيف محلَّل ✓",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )

        BtnFull("تشغيل التحليل الفوري", onClick = { AiAnalysisScheduler.start(context) }, modifier = Modifier.padding(top = 14.dp))
        BtnOutline(
            "إعادة تحليل كل الأرشيف",
            onClick = {
                scope.launch {
                    repository.resetAiAnalysisForReanalyze()
                    AiAnalysisScheduler.start(context)
                }
            },
            modifier = Modifier.padding(top = 10.dp)
        )
        BtnOutline("مجموعات الوجوه (${groups.size})", onClick = onOpenFaces, modifier = Modifier.padding(top = 10.dp))
    }
}