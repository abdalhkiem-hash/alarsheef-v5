package com.alarsheef.archive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarsheef.archive.scanner.WorkScheduler
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.ui.theme.GlassBorder
import com.alarsheef.archive.ui.theme.GlassSoft
import com.alarsheef.archive.ui.theme.TealBright
import com.alarsheef.archive.work.BackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---------- عناصر بصرية مشتركة بأسلوب معاينة الشاشات (srow / seg / btn) ----------

/** عنوان اللوحة: كبير وعريض ووسطًا (panelTitle في المعاينة) */
@Composable
internal fun PanelTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    )
}

/** صف شفاف18% بحواف14 — يُستخدم داخل كل لوحات القائمة الجانبية */
@Composable
internal fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(GlassSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = TealBright,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = Color.White.copy(alpha = 0.3f),
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** عنوان قسم فرعي عريض (subhead في المعاينة) */
@Composable
internal fun SubHead(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

/** زر كامل العرض: أبيض90% + نص تركوازي غامق (btnFull في المعاينة) */
@Composable
internal fun BtnFull(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.9f),
            contentColor = Color(0xFF0F766E)
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/** زر حدّي شفاف بحدود بيضاء50% (btnOutline في المعاينة) */
@Composable
internal fun BtnOutline(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

/** مقطع اختياري (seg في المعاينة): مُفعَّل = أبيض90% ونص تركوازي */
@Composable
internal fun SegChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) Color(0xFF0F766E) else Color.White,
        modifier = modifier
            .background(
                if (selected) Color.White.copy(alpha = 0.9f) else GlassSoft,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp)
    )
}

/** نص ملاحظة صغير باستعلاء65% (note2 في المعاينة) */
@Composable
internal fun NoteSmall(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.65f),
        modifier = modifier.padding(top = 8.dp)
    )
}

// ---------- اللوحات ----------

@Composable
fun SettingsPanelContent(
    prefs: SettingsPreferences,
    scope: CoroutineScope,
    onPickImportFolder: () -> Unit,
    onPickWhatsappDocs: () -> Unit,
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
    val documentsOnly by prefs.documentsOnly.collectAsStateWithLifecycle(initialValue = true)
    val importFolderUri by prefs.importFolderUri.collectAsStateWithLifecycle(initialValue = null)

    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
        PanelTitle("الإعدادات")
        SettingRow("الاستيراد التلقائي اليومي", autoImport) { enabled ->
            scope.launch { prefs.setAutoImport(enabled) }
            if (enabled) {
                WorkScheduler.scheduleDailyScan(context)
                WorkScheduler.runOnce(context)
            } else {
                WorkScheduler.cancelDailyScan(context)
            }
        }

        SubHead("مصادر السحب")
        SettingRow("واتساب", whatsapp) { scope.launch { prefs.setSourceWhatsapp(it) } }
        SettingRow("واتساب أعمال", whatsappBusiness) { enabled ->
            scope.launch { prefs.setSourceWhatsappBusiness(enabled) }
            // صور الأعمال تُسحب من MediaStore بمفتاح المصدر فقط (بلا اختيار SAF) —
            // تشغيل الفحص فورًا يُظهر النتيجة مباشرة عند التفعيل.
            if (enabled) WorkScheduler.runOnce(context)
        }
        SettingRow("الاستديو / المعرض", gallery) { scope.launch { prefs.setSourceGallery(it) } }
        SettingRow("الملفات / التنزيلات", downloads) { scope.launch { prefs.setSourceDownloads(it) } }

        SubHead("فلترة المحتوى")
        SettingRow("استثناء الصور الشخصية/العائلية (كشف الوجوه)", excludePersonal) {
            scope.launch { prefs.setExcludePersonalPhotos(it) }
        }
        SettingRow("استيراد المستندات فقط (فواتير/حوالات)", documentsOnly) {
            scope.launch { prefs.setDocumentsOnly(it) }
        }

        SubHead("مجلدات الوثائق (SAF)")
        Text(
            "اخترها من منتقي النظام — الصور تُستورد تلقائيًا عبر MediaStore.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        BtnOutline("تحديد مجلد وثائق واتساب", onPickWhatsappDocs, Modifier.padding(top = 4.dp))
        BtnOutline("تحديد مجلد وثائق واتساب أعمال", onPickWhatsappBizDocs, Modifier.padding(top = 8.dp))
        BtnOutline("تحديد مجلد التنزيلات", onPickDownloads, Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(14.dp))
        BtnFull("فحص الآن يدويًا", onClick = {
            WorkScheduler.runOnce(context)
            android.widget.Toast.makeText(context, "بدأ الفحص الآن — قد يستغرق دقيقة", android.widget.Toast.LENGTH_SHORT).show()
        })
        BtnOutline(
            if (importFolderUri != null) "تغيير مجلد الاستيراد المخصص" else "تحديد مجلد الاستيراد المخصص",
            onPickImportFolder,
            Modifier.padding(top = 8.dp)
        )
        if (importFolderUri != null) {
            NoteSmall("سيُفحص هذا المجلد يوميًا ضمن الفحص التلقائي.")
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
        PanelTitle("النسخة الاحتياطية")

        SettingRow("تفعيل النسخ الاحتياطي التلقائي", enabled) { on ->
            scope.launch { prefs.setBackupEnabled(on) }
            if (on) {
                BackupScheduler.schedule(context, frequency)
                BackupScheduler.runOnce(context)
            } else {
                BackupScheduler.cancel(context)
            }
        }

        if (enabled) {
            SubHead("التكرار")
            Row(Modifier.fillMaxWidth()) {
                SegChip("يومي", frequency == "daily", Modifier.weight(1f)) {
                    scope.launch { prefs.setBackupFrequency("daily") }
                    BackupScheduler.schedule(context, "daily")
                }
                Spacer(Modifier.width(8.dp))
                SegChip("أسبوعي", frequency == "weekly", Modifier.weight(1f)) {
                    scope.launch { prefs.setBackupFrequency("weekly") }
                    BackupScheduler.schedule(context, "weekly")
                }
            }
        }

        BtnOutline(
            if (folderUri != null) "تغيير مجلد النسخ الاحتياطي" else "تحديد مجلد النسخ الاحتياطي",
            onPickBackupFolder,
            Modifier.padding(top = 14.dp)
        )
        NoteSmall(
            if (lastBackupAt > 0L) "آخر نسخة احتياطية: ${com.alarsheef.archive.util.FileUtils.formatDate(lastBackupAt)}"
            else "لم تُنفَّذ أي نسخة احتياطية بعد."
        )
    }
}

@Composable
fun ExportImportPanelContent(onExport: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.padding(20.dp)) {
        PanelTitle("تصدير / استيراد الأرشيف")
        BtnFull("تصدير الأرشيف كامل (ZIP)", onExport)
        Spacer(Modifier.height(10.dp))
        BtnOutline("استيراد أرشيف من ملف ZIP", onImport)
        NoteSmall("التصدير يشمل كل الصور والملفات والتسميات؛ الاستيراد يدمج أرشيفًا سابقًا بدون حذف الحالي.")
    }
}

@Composable
fun AboutPanelContent() {
    Column(Modifier.padding(20.dp)) {
        PanelTitle("حول التطبيق")
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.24f),
                    RoundedCornerShape(18.dp)
                )
                .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Text(
                "تطبيق الأرشيف — الإصدار 5.0.0",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                "أرشفة تلقائية للصور وملفات PDF من واتساب والاستديو والملفات، مرتبة سنة ← شهر ← يوم، مع تحكم كامل يدوي (نقل، حذف، مشاركة) وتصدير/استيراد ZIP ونسخ احتياطي فعلي.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
