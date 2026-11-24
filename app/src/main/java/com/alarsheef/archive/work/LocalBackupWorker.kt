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

        val folderUri = prefs.backupFolderUri.first()
        val name = "alarsheef-backup-${System.currentTimeMillis()}.zip"
        val folder: DocumentFile? = folderUri?.let { DocumentFile.fromTreeUri(applicationContext, Uri.parse(it)) }
        // لم يُحدَّد مجلد: نسخة افتراضية في Download/AlarsheefBackup (تبقى بعد حذف التطبيق)
        val targetUri: Uri = if (folder != null) {
            folder.createFile("application/zip", name)?.uri ?: return Result.retry()
        } else {
            createDefaultBackupUri(applicationContext, name) ?: return Result.success()
        }

        val repository = ArchiveRepository(applicationContext)
        val porter = ArchivePorter(applicationContext, repository)
        val images = repository.allForExport()
        val result = porter.exportTo(targetUri, images)

        return when (result) {
            is PorterResult.Success -> {
                prefs.setLastBackupAt(System.currentTimeMillis())
                if (folder != null) pruneOldBackups(folder) else pruneDefaultBackups(applicationContext)
                Result.success()
            }
            is PorterResult.Failure -> {
                // كل إعادة محاولة تنشئ ملفًا جديدًا — الناقص يُحذف حتى لا تتراكم ملفات ZIP تالفة
                runCatching { if (folder != null) DocumentFile.fromSingleUri(applicationContext, targetUri)?.delete() else applicationContext.contentResolver.delete(targetUri, null, null) }
                Result.retry()
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "local_archive_backup"
        private const val BACKUP_PREFIX = "alarsheef-backup-"
        const val BACKUPS_TO_KEEP = 2
        /** نسخة يومية: نحتفظ بأسبوع كامل. */
        const val DAILY_BACKUPS_TO_KEEP = 7
        const val DEFAULT_BACKUP_DIR = "AlarsheefBackup"

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
    val drop = LocalBackupWorker.namesToDelete(names, LocalBackupWorker.DAILY_BACKUPS_TO_KEEP).toSet()
    if (drop.isEmpty()) return
    folder.listFiles().forEach { file ->
        if (file.name in drop) runCatching { file.delete() }
    }
}

/** ينشئ ملف نسخة في Download/AlarsheefBackup عبر MediaStore (أندرويد 10+)؛ null لو غير متاح. */
private fun createDefaultBackupUri(context: Context, name: String): Uri? {
    if (android.os.Build.VERSION.SDK_INT < 29) return null
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        put(
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
            android.os.Environment.DIRECTORY_DOWNLOADS + "/" + LocalBackupWorker.DEFAULT_BACKUP_DIR
        )
    }
    return runCatching {
        context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()
}

private fun pruneDefaultBackups(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < 29) return
    runCatching {
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val rows = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(
            collection,
            arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%${LocalBackupWorker.DEFAULT_BACKUP_DIR}/%"),
            null
        )?.use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
        }
        val drop = LocalBackupWorker.namesToDelete(rows.map { it.second }, LocalBackupWorker.DAILY_BACKUPS_TO_KEEP).toSet()
        rows.filter { it.second in drop }.forEach {
            runCatching {
                context.contentResolver.delete(android.content.ContentUris.withAppendedId(collection, it.first), null, null)
            }
        }
    }
}
