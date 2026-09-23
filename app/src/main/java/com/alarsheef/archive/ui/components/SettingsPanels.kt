package com.alarsheef.archive.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarsheef.archive.scanner.WorkScheduler
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.work.BackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SettingsPanelContent(
    prefs: SettingsPreferences,
    scope: CoroutineScope,
    onPickImportFolder: () -> Unit,
    onPickWhatsappImages: () -> Unit,
    onPickWhatsappDocs: () -> Unit,
    onPickWhatsappBizImages: () -> Unit,
    onPickWhatsappBizDocs: () -> Unit,
    onPickDownloads: () -> Unit,
) {
    val context = LocalContext.current
    val autoImport by prefs.autoImport.collectAsStateWithLifecycle(initialValue = true)
    val whatsapp by prefs.sourceWhatsapp.collectAsStateWithLifecycle(initialValue = true)
    val whatsappBusiness by prefs.sourceWhatsappBusiness.collectAsStateWithLifecycle(initialValue = true)
    val gallery by prefs.sourceGallery.collectAsStateWithLifecycle(initialValue = true)
    val downloads by prefs.sourceDownloads.collectAsStateWithLifecycle(initialValue = true)
    val excludePersonal by prefs.excludePersonalPhotos.collectAsStateWithLifecycle(initialValue = true)
    val importFolderUri by prefs.importFolderUri.collectAsStateWithLifecycle(initialValue = null)

    Column(Modifier.padding(20.dp)) {
        Text("الإعدادات", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
        SwitchRow("الاستيراد التلقائي اليومي", autoImport) { enabled ->
            scope.launch { prefs.setAutoImport(enabled) }
            if (enabled) {
                WorkScheduler.scheduleDailyScan(context)
                WorkScheduler.runOnce(context)
            } else {
                WorkScheduler.cancelDailyScan(context)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("مصادر السحب", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        SwitchRow("واتساب", whatsapp) { scope.launch { prefs.setSourceWhatsapp(it) } }
        SwitchRow("واتساب أعمال", whatsappBusiness) { scope.launch { prefs.setSourceWhatsappBusiness(it) } }
        SwitchRow("الاستديو / المعرض", gallery) { scope.launch { prefs.setSourceGallery(it) } }
        SwitchRow("الملفات / التنزيلات", downloads) { scope.launch { prefs.setSourceDownloads(it) } }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        SwitchRow("استثناء الصور الشخصية/العائلية (كشف الوجوه)", excludePersonal) {
            scope.launch { prefs.setExcludePersonalPhotos(it) }
        }
        Text("مجلدات المصدر (SAF — اخترها من منتقي النظام)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        OutlinedButton(onClick = onPickWhatsappImages, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("تحديد مجلد صور واتساب") }
        OutlinedButton(onClick = onPickWhatsappDocs, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("تحديد مجلد وثائق واتساب") }
        OutlinedButton(onClick = onPickWhatsappBizImages, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("تحديد مجلد صور واتساب أعمال") }
        OutlinedButton(onClick = onPickWhatsappBizDocs, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("تحديد مجلد وثائق واتساب أعمال") }
        OutlinedButton(onClick = onPickDownloads, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("تحديد مجلد التنزيلات") }
        OutlinedButton(
            onClick = {
                WorkScheduler.runOnce(context)
                android.widget.Toast.makeText(context, "بدأ الفحص الآن — قد يستغرق دقيقة", android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
        ) {
            Text("فحص الآن يدويًا")
        }
        OutlinedButton(onClick = onPickImportFolder, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(
                if (importFolderUri != null) "تغيير مجلد الاستيراد المخصص"
                else "تحديد مجلد الاستيراد المخصص"
            )
        }
        if (importFolderUri != null) {
            Text(
                "سيُفحص هذا المجلد يوميًا ضمن الفحص التلقائي.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
fun BackupPanelContent(
    prefs: SettingsPreferences,
    scope: CoroutineScope,
    onPickBackupFolder: () -> Unit
) {
    val context = LocalContext.current
    val enabled by prefs.backupEnabled.collectAsStateWithLifecycle(initialValue = false)
    val frequency by prefs.backupFrequency.collectAsStateWithLifecycle(initialValue = "daily")
    val folderUri by prefs.backupFolderUri.collectAsStateWithLifecycle(initialValue = null)
    val lastBackupAt by prefs.lastBackupAt.collectAsStateWithLifecycle(initialValue = 0L)

    Column(Modifier.padding(20.dp)) {
        Text("النسخة الاحتياطية", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))

        SwitchRow("تفعيل النسخ الاحتياطي التلقائي", enabled) { on ->
            scope.launch { prefs.setBackupEnabled(on) }
            if (on) {
                BackupScheduler.schedule(context, frequency)
                BackupScheduler.runOnce(context)
            } else {
                BackupScheduler.cancel(context)
            }
        }

        if (enabled) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                FrequencyChip("يومي", frequency == "daily", Modifier.weight(1f)) {
                    scope.launch { prefs.setBackupFrequency("daily") }
                    BackupScheduler.schedule(context, "daily")
                }
                FrequencyChip("أسبوعي", frequency == "weekly", Modifier.weight(1f).padding(start = 8.dp)) {
                    scope.launch { prefs.setBackupFrequency("weekly") }
                    BackupScheduler.schedule(context, "weekly")
                }
            }
        }

        OutlinedButton(onClick = onPickBackupFolder, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Text(
                when {
                    folderUri != null -> "تغيير مجلد النسخ الاحتياطي"
                    else -> "تحديد مجلد النسخ الاحتياطي"
                }
            )
        }
        Text(
            if (lastBackupAt > 0L) "آخر نسخة احتياطية: ${com.alarsheef.archive.util.FileUtils.formatDate(lastBackupAt)}"
            else "لم تُنفَّذ أي نسخة احتياطية بعد.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun ExportImportPanelContent(onExport: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.padding(20.dp)) {
        Text("تصدير / استيراد الأرشيف", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("تصدير الأرشيف كامل (ZIP)") }
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("استيراد أرشيف من ملف ZIP") }
    }
}

@Composable
fun AboutPanelContent() {
    Column(Modifier.padding(20.dp)) {
        Text("حول التطبيق", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        Text(
            "تطبيق الأرشيف — الإصدار 5.0.0\nأرشفة تلقائية للصور وملفات PDF من واتساب والاستديو والملفات، مرتبة سنة ← شهر ← يوم، مع تحكم كامل يدوي (نقل، حذف، مشاركة) وتصدير/استيراد ZIP ونسخ احتياطي فعلي.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FrequencyChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}