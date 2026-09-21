package com.alarsheef.archive

import android.app.Application
import com.alarsheef.archive.scanner.WorkScheduler
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.work.AiAnalysisScheduler
import com.alarsheef.archive.work.BackupScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * عند بدء التطبيق نطمئن أن جدولة الفحص اليومي والنسخ الاحتياطي موجودة
 * وفق الإعدادات المحفوظة (WorkManager يحتفظ بالجدولة عبر إعادة التشغيل
 * أصلاً، لكن هذا يضمنها حتى لو تغيّرت بياناته الداخلية).
 */
class ArsheefApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = SettingsPreferences(this@ArsheefApplication)

            if (prefs.autoImport.first()) {
                WorkScheduler.scheduleDailyScan(this@ArsheefApplication)
            }

            if (prefs.backupEnabled.first()) {
                val folderUri = prefs.backupFolderUri.first()
                if (folderUri != null) {
                    BackupScheduler.schedule(this@ArsheefApplication, prefs.backupFrequency.first())
                }
            }

            // استئناف التحليل الذكي لأي صور لم تُحلَّل بعد (خروج آمن فوري لو لا شيء معلّق)
            AiAnalysisScheduler.start(this@ArsheefApplication)
        }
    }
}