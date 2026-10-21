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
 * بعد النجاح تُبقى أحدث نسختين باسم alarsheef-backup-*.zip ويُحذف الأقدم.
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
                pruneOldBackups(folder)
                Result.success()
            }
            is PorterResult.Failure -> {
                // كل إعادة محاولة تنشئ ملفًا جديدًا — الناقص يُحذف حتى لا تتراكم ملفات ZIP تالفة
                runCatching { file.delete() }
                Result.retry()
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "local_archive_backup"
        private const val BACKUP_PREFIX = "alarsheef-backup-"
        const val BACKUPS_TO_KEEP = 2

        /** أسماء النسخ التي تُحذف بعد النجاح. تُبقى أحدث [keep] نسخة من ملفاتنا فقط. */
        internal fun namesToDelete(names: List<String?>, keep: Int = BACKUPS_TO_KEEP): List<String> {
            val ours = names.mapNotNull { name ->
                val stamp = stampOf(name) ?: return@mapNotNull null
                name.orEmpty() to stamp
            }.filter { it.first.isNotEmpty() }
            return ours.sortedByDescending { it.second }.drop(keep.coerceAtLeast(0)).map { it.first }
        }

        private fun stampOf(name: String?): Long? {
            if (name == null || !name.startsWith(BACKUP_PREFIX) || !name.endsWith(".zip")) return null
            return name.removePrefix(BACKUP_PREFIX).removeSuffix(".zip").toLongOrNull()
        }
    }
}

private fun pruneOldBackups(folder: DocumentFile) {
    val names = folder.listFiles().map { it.name }
    val drop = LocalBackupWorker.namesToDelete(names).toSet()
    if (drop.isEmpty()) return
    folder.listFiles().forEach { file ->
        if (file.name in drop) runCatching { file.delete() }
    }
}