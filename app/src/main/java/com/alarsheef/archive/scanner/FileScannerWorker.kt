package com.alarsheef.archive.scanner

import android.content.ContentUris
import android.content.Context
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * يعمل يوميًا (وعند الطلب اليدوي) لفحص المجلدات المفعّلة بالإعدادات
 * وأرشفة أي صورة أو PDF جديد.
 *
 * بعد هجرة التخزين (إزالة MANAGE_EXTERNAL_STORAGE):
 * - **الصور** (المعرض، واتساب، واتساب أعمال، تنزيلات): تُستعلم عبر **MediaStore**
 *   باستعلام واحد سريع بصلاحية READ_MEDIA_IMAGES/VIDEO فقط.
 * - **الوثائق وملفات PDF** وملفات غير صور (وثائق واتساب، تنزيلات، مخصص): عبر **SAF**
 *   (يختارها المستخدم مرة واحدة من منتقي النظام).
 *
 * منع التكرار (ثلاث طبقات):
 * 1. بوابة المسارات السريعة `existingPaths` — تُحمَّل مرة واحدة وتُحدَّث بعد كل
 *    استيراد؛ صفحات PDF تحمل هوية `uri#page=N` والصفحة الأولى تحمل `uri` المجرد
 *    كعلامة دائمة على أن الملف كله عولج.
 * 2. بصمة SHA-256 للمحتوى — تلتقط أي ملف مكرر فارغًا من مساره، وتُملأ على
 *    السجل الموجود عبر `claimOriginalPath` حتى لا يتكرر الاكتشاف.
 * 3. فهرس التفرّد في قاعدة البيانات.
 *
 * بوابة المحتوى [ImportGate]: في وضع "المستندات فقط" تُرفض الصور العائلية
 * (وجوه) والمناظر الطبيعية، ولا يُستورد إلا ما يبدو مستندًا/فاتورة/لقطة شاشة.
 *
 * حماية من السباق: فحص واحد فقط يعمل في كل وقت (`RUNNING`)، ويتجاوز الآخر
 * بصمت بدل أن يتراكم ملفات يتيمة على القرص.
 */
class FileScannerWorker(context: Context, params: WorkerParameters)
    : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // فحص واحد فقط في كل وقت — الفحص اليومي واليدوي قد يعملان معًا
        if (!RUNNING.compareAndSet(false, true)) {
            // قد يكون الفحص السابق يُلغى نفسه الآن (زِد "فحص الآن" يستبدل طلبًا
            // قائمًا) — ننتظر تفريغه قليلًا بدل إسقاط طلب المستخدم بصمت.
            val deadline = System.currentTimeMillis() + 3_000
            var acquired = false
            while (System.currentTimeMillis() < deadline) {
                delay(100)
                if (RUNNING.compareAndSet(false, true)) {
                    acquired = true
                    break
                }
            }
            if (!acquired) {
                Log.i(TAG, "فحص آخر قيد التشغيل — تُترك هذه المحاولة")
                return Result.success()
            }
        }
        try {
            return runScan()
        } finally {
            RUNNING.set(false)
        }
    }

    private suspend fun runScan(): Result {
        val prefs = SettingsPreferences(applicationContext)
        if (!prefs.autoImport.first()) {
            Log.i(TAG, "الاستيراد التلقائي معطّل — يُتخطى الفحص")
            return Result.success()
        }
        if (!PermissionUtils.hasMediaPermission(applicationContext)) {
            Log.i(TAG, "لا توجد صلاحية وسائط — يُتخطى الفحص")
            return Result.success()
        }

        return try {
            val repository = ArchiveRepository(applicationContext)
            val todayStart = FileUtils.startOfToday()
            val excludePersonalPhotos = prefs.excludePersonalPhotos.first()
            val documentsOnly = prefs.documentsOnly.first()
            val folders = SourceFolders.activeFolders(prefs)

            // تحميل جميع المسارات الموجودة مرة واحدة لتجنب استعلام قاعدة البيانات لكل صورة
            val existingPaths = repository.findAllOriginalPaths().toMutableSet()
            // سجلات قديمة كُتبت بشرطة مزدوجة "Download//name" — تُطبَّع هنا للتطابق
            // مع المسار الجديد المبني بشرطة واحدة (مسار content:// لا يُمسّ).
            existingPaths.filterTo(mutableSetOf()) {
                it.contains("//") && !it.startsWith("content:")
            }.forEach { existingPaths.add(it.replace("//", "/")) }

            var newImagesCount = 0

            // فحص MediaStore: كل الصور (المعرض + واتساب + واتساب أعمال + تنزيلات)
            newImagesCount += scanMediaStore(repository, todayStart, excludePersonalPhotos,
                documentsOnly, folders, existingPaths)

            // فحص SAF: الوثائق وملفات PDF فقط (الصور تُعالَج عبر MediaStore أعلاه)
            for (folder in folders) {
                if (folder.type is FolderType.Saf) {
                    newImagesCount += scanSafFolder(folder.type.treeUri, folder.source, repository,
                        todayStart, existingPaths)
                }
            }

            prefs.setLastScanAt(System.currentTimeMillis())
            if (newImagesCount > 0) {
                com.alarsheef.archive.work.AiAnalysisScheduler.start(applicationContext)
            }
            Log.i(TAG, "اكتمل الفحص — صور جديدة مؤرشفة: $newImagesCount")
            Result.success()
        } catch (e: CancellationException) {
            // إلغاء عادي (استبدال طلب يدوي أو إيقاف التطبيق) — ليس خطأ
            throw e
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

    // ---------- MediaStore (صور فقط — استعلام واحد سريع) ----------

    /**
     * يستعلم MediaStore عن الصور المضافة منذ بداية اليوم، ويحدد مصدر كل صورة
     * من مسارها النسبي، ثم يمرّرها على بوابة المحتوى ويستوردها إن قبلتها.
     *
     * يقرأ DATE_TAKEN (تاريخ الالتقاط الأصلي) أو DATE_MODIFIED كتاريخ للأرشفة
     * بدل تاريخ الاستيراد.
     *
     * @param existingPaths مجموعة (قابلة للتعديل) المسارات المأرشفة — تُحدَّث بعد كل استيراد.
     * @return عدد الصور الجديدة التي تم استيرادها.
     */
    private suspend fun scanMediaStore(
        repository: ArchiveRepository,
        todayStart: Long,
        excludePersonalPhotos: Boolean,
        documentsOnly: Boolean,
        folders: List<SourceFolder>,
        existingPaths: MutableSet<String>,
    ): Int {
        val enabledSources = folders.map { it.source }.toSet()
        val todayStartSeconds = (todayStart / 1000).toString()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
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
                val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol)
                    val relativePath = cursor.getString(pathCol)
                    Log.i(TAG, "صف MediaStore: id=$id path=$relativePath name=$displayName")
                    if (displayName == null || relativePath == null) {
                        Log.i(TAG, "تخطي (اسم أو مسار مفقود): id=$id")
                        continue
                    }
                    val source = sourceFromRelativePath(relativePath)
                    if (source == null) {
                        Log.i(TAG, "تخطي (مصدر غير معروف): $relativePath$displayName")
                        continue
                    }
                    if (source !in enabledSources) {
                        Log.i(TAG, "تخطي (المصدر غير مفعّل): $displayName")
                        continue
                    }
                    val originalPath = relativePath.trimEnd('/') + "/" + displayName
                    if (originalPath in existingPaths) {
                        Log.i(TAG, "تخطي (أُرشف سابقًا): $displayName")
                        continue
                    }
                    if (!FileUtils.isImage(displayName)) {
                        Log.i(TAG, "تخطي (ليس صورة): $displayName")
                        continue
                    }

                    // تاريخ الأرشفة: DATE_TAKEN (الالتقاط الأصلي) ثم DATE_MODIFIED
                    val dateTakenMs = if (takenCol >= 0 && !cursor.isNull(takenCol)) cursor.getLong(takenCol) else 0L
                    val dateModifiedSec = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L
                    val originalDate = dateTakenMs.takeIf { it > 0 } ?: dateModifiedSec * 1000

                    // نسخة واحدة تكفي لكشف الوجوه والتصنيف والاستيراد
                    // (بدل نسخة لكشف الوجوه عبر URI وأخرى للاستيراد)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val temp = try {
                        copyMediaStoreImageToTemp(uri, displayName)
                    } catch (e: IOException) {
                        Log.w(TAG, "تعذر نسخ $displayName: ${e.message}")
                        continue
                    } catch (e: SecurityException) {
                        Log.w(TAG, "رفض الوصول لـ $displayName: ${e.message}")
                        continue
                    }
                    try {
                        val faces = if (excludePersonalPhotos || documentsOnly) {
                            FaceDetectionUtil.hasFace(temp)
                        } else false

                        if (!ImportGate.shouldArchive(temp, faces, excludePersonalPhotos,
                                documentsOnly, displayName)) {
                            continue
                        }

                        when (val result = repository.importFile(
                            sourceFile = temp, sourceApp = source,
                            deleteSourceAfterImport = true, countDuplicate = false,
                            originalPath = originalPath,
                            originalDate = originalDate,
                        )) {
                            is ImportResult.Added -> {
                                count++
                                existingPaths.add(originalPath)
                                Log.i(TAG, "أُرشف: $displayName")
                            }
                            is ImportResult.Duplicate -> {
                                existingPaths.add(originalPath)
                                Log.i(TAG, "مكرر (بصمة مطابقة): $displayName")
                            }
                            is ImportResult.Failed -> Log.w(TAG, "فشل الاستيراد: $displayName")
                        }
                    } catch (e: IOException) {
                        // ملف واحد لا يُسقط كامل الفحص — نسجّل ونكمل
                        Log.w(TAG, "تعذر معالجة $displayName: ${e.message}")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "رفض الوصول أثناء معالجة $displayName: ${e.message}")
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

    /**
     * نسخة واحدة من MediaStore للكاش — تحافظ على امتداد الملف الأصلي
     * (لا تُجبر .jpg) حتى يستورد الملف بامتداده الصحيح في الأرشيف.
     */
    private suspend fun copyMediaStoreImageToTemp(uri: Uri, displayName: String): File = withContext(Dispatchers.IO) {
        val ext = FileUtils.extensionOf(displayName).ifBlank { "jpg" }
        val temp = File(applicationContext.cacheDir, "media_${System.nanoTime()}.$ext")
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("فشل فتح صورة MediaStore")
        temp
    }

    // ---------- SAF (وثائق وPDF فقط — الصور عبر MediaStore) ----------

    private suspend fun scanSafFolder(
        treeUri: Uri,
        source: SourceApp,
        repository: ArchiveRepository,
        todayStart: Long,
        existingPaths: MutableSet<String>,
    ): Int {
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return 0
        return visitDocuments(root, source, repository, todayStart, 0, existingPaths)
    }

    private suspend fun visitDocuments(
        folder: DocumentFile,
        source: SourceApp,
        repository: ArchiveRepository,
        todayStart: Long,
        depth: Int,
        existingPaths: MutableSet<String>,
    ): Int {
        if (depth > MAX_DEPTH) return 0
        var count = 0
        for (doc in folder.listFiles()) {
            currentCoroutineContext().ensureActive()
            if (doc.isDirectory) {
                count += visitDocuments(doc, source, repository, todayStart, depth + 1, existingPaths)
                continue
            }
            if (!doc.isFile || doc.name == null) continue
            if (doc.lastModified() < todayStart) continue
            val docIdentity = doc.uri.toString()
            if (docIdentity in existingPaths) continue

            val name = doc.name ?: continue

            // الصور تُعالج عبر MediaStore (أسرع بكثير) — نتخطاها هنا
            if (FileUtils.isImage(name)) continue

            if (FileUtils.isPdf(name)) {
                val temp = File(applicationContext.cacheDir, "pdf_${System.nanoTime()}_$name")
                try {
                    applicationContext.contentResolver.openInputStream(doc.uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: continue
                    Log.i(TAG, "معالجة PDF: $name")
                    val pages = PdfToImageConverter.convertToPngPages(applicationContext, temp)
                    Log.i(TAG, "صفحات PDF ${name}: ${pages.size}")
                    var pdfCount = 0
                    pages.forEachIndexed { index, page ->
                        // الهوية: الصفحة الأولى تحمل uri المجرد كعلامة دائمة على
                        // أن الملف كله عولج، وبقية الصفحات تحمل uri#page=N.
                        val pageIdentity = if (index == 0) docIdentity else "$docIdentity#page=$index"
                        val result = repository.importFile(
                            sourceFile = page, sourceApp = source,
                            deleteSourceAfterImport = true, countDuplicate = false,
                            originalPath = pageIdentity,
                            originalDate = doc.lastModified(),
                        )
                        if (result is ImportResult.Added) pdfCount++
                        page.delete()
                    }
                    // علامة داخل التشغيل الحالي: لا نعيد معالجة نفس الملف في المسارين
                    existingPaths.add(docIdentity)
                    count += pdfCount
                } catch (e: SecurityException) {
                    Log.w(TAG, "رفض الوصول لـ $name: ${e.message}")
                } catch (e: IOException) {
                    // ملف واحد لا يُسقط كامل الفحص — نسجّل ونكمل
                    Log.w(TAG, "تعذر قراءة $name: ${e.message}")
                } finally {
                    runCatching { temp.delete() }
                }
            } else {
                // ملف غير صور ولا PDF (وثيقة نصية، سجل، إلخ) — استيراد خام
                val temp = File(applicationContext.cacheDir, "custom_${System.nanoTime()}_$name")
                try {
                    applicationContext.contentResolver.openInputStream(doc.uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: continue
                    val result = repository.importFile(
                        sourceFile = temp, sourceApp = source,
                        deleteSourceAfterImport = true, countDuplicate = false,
                        originalPath = docIdentity,
                        originalDate = doc.lastModified(),
                    )
                    if (result is ImportResult.Added) count++
                    if (result is ImportResult.Added || result is ImportResult.Duplicate) {
                        existingPaths.add(docIdentity)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "رفض الوصول لـ $name: ${e.message}")
                } catch (e: IOException) {
                    Log.w(TAG, "تعذر قراءة $name: ${e.message}")
                } finally {
                    runCatching { temp.delete() }
                }
            }
        }
        return count
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_archive_scan"
        const val ONE_TIME_WORK_NAME = "manual_archive_scan"
        private const val MAX_DEPTH = 4
        private const val TAG = "FileScannerWorker"

        /** يمنع تشغيل فحصين معًا (يومي + يدوي) → سباق وملفات يتيمة على القرص. */
        private val RUNNING = AtomicBoolean(false)
    }
}
