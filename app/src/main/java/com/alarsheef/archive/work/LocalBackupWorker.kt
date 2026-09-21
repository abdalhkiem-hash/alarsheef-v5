package com.alarsheef.archive.work

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.porter.ArchivePorter
import com.alarsheef.archive.porter.PorterResult
import com.alarsheef.archive.settings.SettingsPreferences
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * نسخ احتياطي فعلي منفّذ بالخلفية: يصدّر الأرشيف كاملاً كملف ZIP داخل
 * مجلد الوجهة الذي اختاره المستخدم، ويحدّث وقت آخر نسخة بعد النجاح.
 * ينتهي بنجاح هادئ لو النسخ الاحتياطي معطّل أو لم يُحدَّد مجلد وجهة.
 */
class LocalBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = SettingsPreferences(applicationContext)
        if (!prefs.backupEnabled.first()) return Result.success()

        val folderUri = prefs.backupFolderUri.first() ?: return Result.success()
        val folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folderUri))
            ?: return Result.success()

        val name = "alarsheef-backup-${System.currentTimeMillis()}.zip"
        val file = folder.createFile("application/zip", name) ?: return Result.retry()

        val repository = ArchiveRepository(applicationContext)
        val porter = ArchivePorter(applicationContext, repository)
        val images = repository.allForExport()
        val result = porter.exportTo(file.uri, images)

        return when (result) {
            is PorterResult.Success -> {
                prefs.setLastBackupAt(System.currentTimeMillis())
                Result.success()
            }
            is PorterResult.Failure -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "local_archive_backup"
    }
}