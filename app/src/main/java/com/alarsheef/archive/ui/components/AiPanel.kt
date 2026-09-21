package com.alarsheef.archive.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarsheef.archive.ai.OcrEngine
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.work.AiAnalysisScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember

/**
 * لوحة "الذكاء الاصطناعي" في القائمة الجانبية:
 * مفاتيح التشغيل للميزات الأربع + حالة العدد المعلق + زر التحليل الفوري
 * + زر إعادة التحليل الكامل + زر فتح مجموعات الوجوه.
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
    val ocrAvailable = remember { OcrEngine.isAvailable(context) }

    Column(Modifier.padding(20.dp)) {
        Text("الذكاء الاصطناعي", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        Text(
            "كل التحليل يعمل على جهازك بالكامل دون إنترنت ولا رفع لأي صورة.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SwitchRow2("التصنيف التلقائي للمحتوى", labelsEnabled) { scope.launch { prefs.setAiLabelsEnabled(it) } }
        SwitchRow2("استخراج النصوص (OCR)", ocrEnabled) { scope.launch { prefs.setAiOcrEnabled(it) } }
        SwitchRow2("تجميع الصور حسب الوجوه", facesEnabled) { scope.launch { prefs.setAiFacesEnabled(it) } }

        if (!ocrAvailable) {
            Text(
                "تنبيه: هذه النسخة من Android (± 13) لا توفر نموذج النص على الجهاز — سيُتخطى OCR بهدوء.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Text(
                "التعرف على النصوص يتطلب Android 13 أو أحدث.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        Text(
            if (pending > 0) "متبقٍ للتحليل: $pending صورة — يجري التحليل في الخلفية"
            else "كل الأرشيف محلَّل ✓",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(onClick = { AiAnalysisScheduler.start(context) }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Text("تشغيل التحليل الفوري")
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    repository.resetAiAnalysisForReanalyze()
                    AiAnalysisScheduler.start(context)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) {
            Text("إعادة تحليل كل الأرشيف")
        }
        OutlinedButton(
            onClick = onOpenFaces,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) {
            Icon(Icons.Filled.Face, contentDescription = null)
            Text("مجموعات الوجوه (${groups.size})", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SwitchRow2(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}