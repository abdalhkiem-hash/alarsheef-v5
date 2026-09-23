package com.alarsheef.archive.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.ImportResult
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.util.FileUtils
import com.alarsheef.archive.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * يعمل يوميًا (وعند الطلب اليدوي) لفحص المجلدات المفعّلة بالإعدادات
 * وأرشفة أي صورة أو PDF جديد.
 *
 * بعد هجرة التخزين (إزالة MANAGE_EXTERNAL_STORAGE):
 * - **معرض الصور** (DCIM، Pictures، واتساب صور، تنزيلات صور): يُستعلم عبر **MediaStore**
 *   بصلاحية READ_MEDIA_IMAGES/VIDEO فقط.
 * - **وثائق** و**ملفات غير صور** (وثائق واتساب، تنزيلات PDF، مخصص): عبر **SAF**
 *   (يختارها المستخدم مرة واحدة من منتقي النظام).
 *
 * - الأرشفة يومية بمبدأ "ملف اليوم بيومه": يُستورد فقط الملف الذي يقع وقت
 *   تعديله (DATE_ADDED/lastModified) في يوم الفحص نفسه.
 * - تتبّع originalPath يمنع إعادة معالجة نفس الملف يوميًا.
 * - **التحسينات**: تحميل جميع originalPaths مرة واحدة، تجاهل كشف الوجوه إن لم يُفعَّل،
 *   معالجة متوازية، وبدء التحليل الذكي فقط عند وجود صور جديدة.
 */
class FileScannerWorker(context: Context, params: WorkerParameters)
    : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = SettingsPreferences(applicationContext)
        if (!prefs.autoImport.first()) return Result.success()
        if (!PermissionUtils.hasMediaPermission(applicationContext)) return Result.success()

        return try {
            val repository = ArchiveRepository(applicationContext)
            val todayStart = FileUtils.startOfToday()
            val excludePersonalPhotos = prefs.excludePersonalPhotos.first()
            val folders = SourceFolders.activeFolders(prefs)

            // تحميل جميع المسارات الموجودة مرة واحدة لتجنب استعلام قاعدة البيانات لكل صورة
            val existingPaths = repository.findAllOriginalPaths().toSet()

            var newImagesCount = 0

            // فحص MediaStore: كل الصور المضافة اليوم (المعرض + واتساب صور + تنزيلات صور)
            newImagesCount += scanMediaStore(repository, todayStart, excludePersonalPhotos, folders, existingPaths)

            // فحص SAF: المجلدات التي يختارها المستخدم (وثائق، ملفات، مخصص)
            for (folder in folders) {
                if (folder.type is FolderType.Saf) {
                    newImagesCount += scanSafFolder(folder.type.treeUri, folder.source, repository,
                        excludePersonalPhotos, todayStart, existingPaths)
                }
            }

            prefs.setLastScanAt(System.currentTimeMillis())
            if (newImagesCount > 0) {
                com.alarsheef.archive.work.AiAnalysisScheduler.start(applicationContext)
            }
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "خطأ مؤقت في الفحص: ${e.message}")
            Result.retry()
        } catch (e: SecurityException) {
            Log.w(TAG, "فقدان صلاحية الوصول أثناء الفحص: ${e.message}")
            Result.failure()
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ غير متوقع في الفحص", e)
            Result.failure()
        }
    }

    // ---------- MediaStore ----------

    /**
     * يستعلم MediaStore عن الصور المضافة منذ بداية اليوم، ويحدد مصدر كل صورة
     * من مسارها النسبي، ثم يستوردها إن كانت من مصدر مفعّل.
     *
     * @param existingPaths مجموعة المسارات المأرشفة مسبقًا (تُحمَّل مرة واحدة).
     * @return عدد الصور الجديدة التي تم استيرادها.
     */
    private suspend fun scanMediaStore(
        repository: ArchiveRepository,
        todayStart: Long,
        excludePersonalPhotos: Boolean,
        folders: List<SourceFolder>,
        existingPaths: Set<String>,
    ): Int {
        val enabledSources = folders.map { it.source }.toSet()
        val todayStartSeconds = (todayStart / 1000).toString()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf(todayStartSeconds)
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return withContext(Dispatchers.IO) {
            var count = 0
            applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val relativePath = cursor.getString(pathCol) ?: continue
                    val source = sourceFromRelativePath(relativePath) ?: continue
                    if (source !in enabledSources) continue
                    val originalPath = "$relativePath/$displayName"
                    if (originalPath in existingPaths) continue
                    if (!FileUtils.isImage(displayName)) continue

                    val isPersonal = excludePersonalPhotos &&
                        FaceDetectionUtil.hasFace(applicationContext,
                            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                    if (isPersonal) continue

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val temp = copyMediaStoreImageToTemp(uri)
                    try {
                        val result = repository.importFile(
                            sourceFile = temp, sourceApp = source,
                            deleteSourceAfterImport = true, countDuplicate = true,
                            originalPath = originalPath,
                        )
                        if (result is ImportResult.Added) count++
                    } finally {
                        if (temp.exists()) temp.delete()
                    }
                }
            }
            count
        }
    }

    private fun sourceFromRelativePath(path: String): SourceApp? = when {
        path.contains("com.whatsapp.w4b") || path.contains("WhatsApp Business") -> SourceApp.WHATSAPP_BUSINESS
        path.startsWith("WhatsApp") || path.contains("/com.whatsapp/") -> SourceApp.WHATSAPP
        path.startsWith("DCIM") -> SourceApp.GALLERY
        path.startsWith("Pictures") -> SourceApp.GALLERY
        path.startsWith("Download") -> SourceApp.DOWNLOADS
        else -> null
    }

    private suspend fun copyMediaStoreImageToTemp(uri: Uri): File = withContext(Dispatchers.IO) {
        val temp = File(applicationContext.cacheDir, "media_${System.nanoTime()}.jpg")
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("فشل فتح صورة MediaStore")
        temp
    }

    // ---------- SAF ----------

    private suspend fun scanSafFolder(
        treeUri: Uri,
        source: SourceApp,
        repository: ArchiveRepository,
        excludePersonalPhotos: Boolean,
        todayStart: Long,
        existingPaths: Set<String>,
    ): Int {
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return 0
        return visitDocuments(root, source, repository, excludePersonalPhotos, todayStart, 0, existingPaths)
    }

    private suspend fun visitDocuments(
        folder: DocumentFile,
        source: SourceApp,
        repository: ArchiveRepository,
        excludePersonalPhotos: Boolean,
        todayStart: Long,
        depth: Int,
        existingPaths: Set<String>,
    ): Int {
        if (depth > MAX_DEPTH) return 0
        var count = 0
        for (doc in folder.listFiles()) {
            currentCoroutineContext().ensureActive()
            if (doc.isDirectory) {
                count += visitDocuments(doc, source, repository, excludePersonalPhotos, todayStart, depth + 1, existingPaths)
                continue
            }
            if (!doc.isFile || doc.name == null) continue
            if (doc.lastModified() < todayStart) continue
            val docIdentity = doc.uri.toString()
            if (docIdentity in existingPaths) continue

            val temp = File(applicationContext.cacheDir, "custom_${System.nanoTime()}_${doc.name}")
            try {
                applicationContext.contentResolver.openInputStream(doc.uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: continue
                val name = doc.name ?: continue
                if (!FileUtils.isImage(name)) {
                    val isPersonal = excludePersonalPhotos && FaceDetectionUtil.hasFace(temp)
                    if (isPersonal) continue
                    val result = repository.importFile(
                        sourceFile = temp, sourceApp = source,
                        deleteSourceAfterImport = true, countDuplicate = true,
                        originalPath = docIdentity,
                    )
                    if (result is ImportResult.Added) count++
                } else if (FileUtils.isPdf(name)) {
                    Log.i(TAG, "معالجة PDF: ${name}")
                    val pages = PdfToImageConverter.convertToPngPages(applicationContext, temp)
                    Log.i(TAG, "صفحات PDF ${name}: ${pages.size}")
                    var pdfCount = 0
                    pages.forEach { page ->
                        val result = repository.importFile(
                            sourceFile = page, sourceApp = source,
                            deleteSourceAfterImport = true, countDuplicate = false,
                            originalPath = null,
                        )
                        if (result is ImportResult.Added) pdfCount++
                        page.delete()
                    }
                    count += pdfCount
                }
            } catch (e: SecurityException) {
            } catch (e: IOException) {
                throw e
            } finally {
                runCatching { temp.delete() }
            }
        }
        return count
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_archive_scan"
        const val ONE_TIME_WORK_NAME = "manual_archive_scan"
        private const val MAX_DEPTH = 4
        private const val TAG = "FileScannerWorker"
    }
}