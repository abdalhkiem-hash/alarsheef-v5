package com.alarsheef.archive.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.alarsheef.archive.util.PermissionUtils
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.ImportResult
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.util.FileUtils
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
 * يفحص واتساب أعمال: صور إشعارات الحوالات والمستندات وملفات PDF التي وصلت اليوم.
 * مجلد الإدخال، إن حُدّد، يُفحص لملفات اليوم أيضًا. لا فحص شكل ولا رفض بسبب وجه.
 * واتساب العادي والمعرض والتنزيلات لا تُسحب.
 *
 * الملف غير الصورة وغير PDF لا يدخل. التنزيل الناقص والملف الفارغ يُتركان
 * حتى يكتمل. ما تاريخه قبل اليوم يُترك في المجلد. الصفحة التي يفشل رسمها لا تزيح أرقام الصفحات بعدها.
 * التكرار بالبصمة والمسار. التاريخ والمبلغ ورقم الورقة يُقرآن بعد الأرشفة للتسمية.
 */
class FileScannerWorker(context: Context, params: WorkerParameters)
    : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!RUNNING.compareAndSet(false, true)) {
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
        val folderUri = prefs.importFolderUri.first()?.takeIf { it.isNotBlank() }
        val businessDocsUri = prefs.whatsappBusinessDocumentsTreeUri.first()?.takeIf { it.isNotBlank() }

        return try {
            val repository = ArchiveRepository(applicationContext)
            val removed = repository.removeInboxCapturedBefore(FileUtils.startOfToday())
            if (removed > 0) Log.i(TAG, "أُزيل من الأرشيف $removed ملفًا أقدم من اليوم")
            keepGateText = prefs.aiOcrEnabled.first()
            val existingPaths = ImportRules.normalizeExistingPaths(repository.findAllOriginalPaths())
            val done = repository.processedSourcePaths().toMutableSet()
            var count = scanWhatsappBusiness(repository, existingPaths, done)
            // المستندات قبل مجلد الصور: ملف PDF الذي وصل اليوم لا ينتظر عشرات آلاف الصور القديمة.
            if (businessDocsUri != null) {
                count += scanInbox(
                    Uri.parse(businessDocsUri),
                    repository,
                    existingPaths,
                    done,
                    SourceApp.WHATSAPP_BUSINESS,
                )
            }
            if (folderUri != null) {
                count += scanInbox(Uri.parse(folderUri), repository, existingPaths, done, SourceApp.INBOX)
            }
            prefs.setLastScanAt(System.currentTimeMillis())
            if (count > 0) {
                com.alarsheef.archive.work.AiAnalysisScheduler.start(applicationContext)
            }
            Log.i(TAG, "اكتمل الفحص — صفحات جديدة: $count")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "خطأ مؤقت في الفحص: ${e.message}")
            Result.retry()
        } catch (e: SecurityException) {
            Log.w(TAG, "لا صلاحية لمجلد الإدخال: ${e.message}")
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ غير متوقع في الفحص", e)
            Result.failure()
        }
    }

    private var keepGateText = false

    private suspend fun scanInbox(
        treeUri: Uri,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
        sourceApp: SourceApp,
    ): Int {
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return 0
        if (!root.isDirectory) return 0
        return visit(root, repository, existingPaths, done, FileUtils.startOfToday(), sourceApp, 0)
    }

    private suspend fun visit(
        folder: DocumentFile,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
        startOfToday: Long,
        sourceApp: SourceApp,
        depth: Int,
    ): Int {
        if (depth > MAX_DEPTH) return 0
        var count = 0
        for (doc in folder.listFiles()) {
            currentCoroutineContext().ensureActive()
            if (doc.isDirectory) {
                val dirName = doc.name.orEmpty()
                if (sourceApp == SourceApp.WHATSAPP_BUSINESS &&
                    !ImportRules.shouldVisitBusinessDirectory(dirName)
                ) {
                    continue
                }
                count += visit(doc, repository, existingPaths, done, startOfToday, sourceApp, depth + 1)
                continue
            }
            if (!doc.isFile) continue
            val name = doc.name ?: continue
            val identity = doc.uri.toString()
            // طول 0 من SAF قد يعني «غير معروف» لا «فارغ». الامتداد الناقص يُترك فورًا،
            // والصورة أو PDF تُنسخ ثم يُقاس الملف المؤقت.
            val reported = doc.length()
            val kind = if (reported == 0L) {
                ImportRules.inboxFileKind(name, 1L)
            } else {
                ImportRules.inboxFileKind(name, reported)
            }
            if ((kind == ImportRules.InboxFileKind.IMAGE || kind == ImportRules.InboxFileKind.PDF) &&
                ImportRules.isBeforeToday(doc.lastModified(), startOfToday)
            ) {
                Log.i(TAG, "قبل اليوم، يُترك: $name")
                continue
            }

            when (kind) {
                ImportRules.InboxFileKind.LEAVE -> Log.i(TAG, "يُترك حتى يكتمل: $name")
                ImportRules.InboxFileKind.IGNORE -> Log.i(TAG, "خارج القاعدة: $name")
                ImportRules.InboxFileKind.IMAGE -> {
                    if (identity in done || identity in existingPaths) continue
                    count += importImage(doc, name, identity, repository, existingPaths, done, sourceApp)
                }
                ImportRules.InboxFileKind.PDF -> {
                    // اكتمال الملف من processed_sources فقط. صفحة مخزَّنة لا تغلق الباقي،
                    // حتى يُستأنف PDF انقطع سيره في منتصف الملف.
                    if (identity in done) continue
                    count += importPdf(doc, name, identity, repository, existingPaths, done, sourceApp)
                }
            }
        }
        return count
    }

    private suspend fun importImage(
        doc: DocumentFile,
        name: String,
        identity: String,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
        sourceApp: SourceApp,
    ): Int {
        val temp = File(
            applicationContext.cacheDir,
            ImportRules.tempFileName("inbox", name, System.nanoTime())
        )
        try {
            copyTo(doc, temp) ?: return 0
            if (temp.length() == 0L) {
                Log.i(TAG, "يُترك حتى يكتمل: $name")
                return 0
            }
            val text = readText(listOf(temp))
            return when (
                importOne(
                    repository = repository,
                    file = temp,
                    identity = identity,
                    originalDate = doc.lastModified(),
                    originalName = name,
                    ocrText = text,
                    existingPaths = existingPaths,
                    sourceApp = sourceApp,
                )
            ) {
                is ImportResult.Added -> {
                    Log.i(TAG, "أُرشف: $name")
                    1
                }
                is ImportResult.Duplicate -> {
                    // البصمة موجودة بمسار آخر. نعلّم هذا المسار حتى لا يُعاد قياسه كل فحص.
                    repository.markSourceProcessed(identity)
                    done.add(identity)
                    Log.i(TAG, "مكرر (بصمة مطابقة): $name")
                    0
                }
                is ImportResult.Failed -> 0
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "رفض الوصول لـ $name: ${e.message}")
            return 0
        } catch (e: IOException) {
            Log.w(TAG, "تعذر قراءة $name: ${e.message}")
            return 0
        } finally {
            runCatching { temp.delete() }
        }
    }

    private suspend fun importPdf(
        doc: DocumentFile,
        name: String,
        identity: String,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
        sourceApp: SourceApp,
    ): Int {
        val temp = File(
            applicationContext.cacheDir,
            ImportRules.tempFileName("pdf", name, System.nanoTime())
        )
        var pages = emptyList<PdfToImageConverter.RenderedPage>()
        try {
            copyTo(doc, temp) ?: return 0
            if (temp.length() == 0L) {
                Log.i(TAG, "يُترك حتى يكتمل: $name")
                return 0
            }
            Log.i(TAG, "معالجة PDF: $name")
            pages = PdfToImageConverter.convertToPngPages(applicationContext, temp)
            if (pages.isEmpty()) {
                Log.w(TAG, "تعذر رسم صفحات $name")
                repository.markSourceProcessed(identity)
                done.add(identity)
                return 0
            }
            val text = readText(pages.take(ContentGate.MAX_PDF_PAGES).map { it.file })
            var added = 0
            var failed = false
            for (page in pages) {
                currentCoroutineContext().ensureActive()
                val pageIdentity = ImportRules.pageIdentity(identity, page.pageNumber)
                if (pageIdentity in existingPaths) continue
                when (
                    importOne(
                        repository = repository,
                        file = page.file,
                        identity = pageIdentity,
                        originalDate = doc.lastModified(),
                        originalName = ImportRules.pageDisplayName(name, page.pageNumber),
                        ocrText = text,
                        existingPaths = existingPaths,
                        sourceApp = sourceApp,
                    )
                ) {
                    is ImportResult.Added -> added++
                    is ImportResult.Duplicate -> Log.i(TAG, "صفحة مكررة: $name ص${page.pageNumber}")
                    is ImportResult.Failed -> failed = true
                }
            }
            if (!failed) {
                repository.markSourceProcessed(identity)
                done.add(identity)
            }
            if (added > 0) Log.i(TAG, "أُرشف PDF: $name ($added صفحة)")
            return added
        } catch (e: SecurityException) {
            Log.w(TAG, "رفض الوصول لـ $name: ${e.message}")
            return 0
        } catch (e: IOException) {
            Log.w(TAG, "تعذر قراءة $name: ${e.message}")
            return 0
        } finally {
            runCatching { temp.delete() }
            pages.forEach { runCatching { it.file.delete() } }
        }
    }

    private suspend fun readText(files: List<File>): String? {
        if (!keepGateText || files.isEmpty()) return null
        return runCatching { ContentGate.readText(applicationContext, files) }.getOrNull()
    }

    /** المكرر يُعلَّم في الذاكرة حتى لا يُعاد داخل نفس السير. الحفظ الدائم للملف يتم بعد اكتمال السير. */
    private suspend fun importOne(
        repository: ArchiveRepository,
        file: File,
        identity: String,
        originalDate: Long,
        originalName: String,
        ocrText: String?,
        existingPaths: MutableSet<String>,
        sourceApp: SourceApp,
    ): ImportResult {
        val result = repository.importFile(
            sourceFile = file,
            sourceApp = sourceApp,
            deleteSourceAfterImport = true,
            countDuplicate = false,
            originalPath = identity,
            originalDate = originalDate,
            contentVerified = ContentVerified.ACCEPTED,
            namePrefix = "مستند",
            originalName = originalName,
            ocrText = ocrText,
        )
        when (result) {
            is ImportResult.Added, is ImportResult.Duplicate -> existingPaths.add(identity)
            is ImportResult.Failed -> Log.w(TAG, "فشل الاستيراد: $originalName")
        }
        return result
    }

    /**
     * صور إشعارات الحوالات والمستندات وملفات PDF التي وصلت اليوم إلى واتساب أعمال.
     * المعيار وقت وصول الملف إلى الجهاز، لا تاريخ الورقة المطبوع.
     */
    private suspend fun scanWhatsappBusiness(
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
    ): Int {
        if (!PermissionUtils.hasMediaPermission(applicationContext)) {
            Log.i(TAG, "لا صلاحية قراءة الصور — واتساب أعمال لا يُفحص")
            return 0
        }
        val start = FileUtils.startOfToday()
        var count = 0
        count += queryBusiness(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            start,
            repository,
            existingPaths,
            done,
        )
        count += queryBusiness(
            MediaStore.Files.getContentUri("external"),
            start,
            repository,
            existingPaths,
            done,
        )
        return count
    }

    private data class BusinessHit(
        val uri: Uri,
        val name: String,
        val identity: String,
        val arrivedAt: Long,
        val pdf: Boolean,
    )

    private suspend fun queryBusiness(
        collection: Uri,
        startOfToday: Long,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
    ): Int {
        val hits = withContext(Dispatchers.IO) {
            val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.MediaColumns.RELATIVE_PATH
            } else {
                MediaStore.MediaColumns.DATA
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                pathColumn,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
            )
            val cursor = try {
                applicationContext.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                    arrayOf((startOfToday / 1000L).toString()),
                    null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "تعذر قراءة واتساب أعمال: ${e.message}")
                null
            } ?: return@withContext emptyList()
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(pathColumn)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(nameCol) ?: continue
                        val relative = c.getString(pathCol) ?: continue
                        val added = c.getLong(addedCol)
                        val size = c.getLong(sizeCol)
                        if (!ImportRules.isBusinessArrival(relative, name, size, added, startOfToday)) continue
                        val identity = ImportRules.originalPathOf(relative, name)
                        val pdf = ImportRules.inboxFileKind(name, size) == ImportRules.InboxFileKind.PDF
                        if (pdf) {
                            if (identity in done) continue
                        } else if (identity in done || identity in existingPaths) {
                            continue
                        }
                        add(
                            BusinessHit(
                                uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                                name = name,
                                identity = identity,
                                arrivedAt = added * 1000L,
                                pdf = pdf,
                            )
                        )
                    }
                }
            }
        }
        var count = 0
        for (hit in hits) {
            currentCoroutineContext().ensureActive()
            count += if (hit.pdf) {
                importPdfUri(hit, repository, existingPaths, done)
            } else {
                importImageUri(hit, repository, existingPaths, done)
            }
        }
        return count
    }

    private suspend fun importImageUri(
        hit: BusinessHit,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
    ): Int {
        val temp = File(applicationContext.cacheDir, ImportRules.tempFileName("w4b", hit.name, System.nanoTime()))
        try {
            copyUri(hit.uri, temp) ?: return 0
            if (temp.length() == 0L) return 0
            val text = readText(listOf(temp))
            return when (
                importOne(
                    repository = repository,
                    file = temp,
                    identity = hit.identity,
                    originalDate = hit.arrivedAt,
                    originalName = hit.name,
                    ocrText = text,
                    existingPaths = existingPaths,
                    sourceApp = SourceApp.WHATSAPP_BUSINESS,
                )
            ) {
                is ImportResult.Added -> {
                    Log.i(TAG, "أُرشف من واتساب أعمال: ${hit.name}")
                    1
                }
                is ImportResult.Duplicate -> {
                    repository.markSourceProcessed(hit.identity)
                    done.add(hit.identity)
                    0
                }
                is ImportResult.Failed -> 0
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "رفض الوصول لـ ${hit.name}: ${e.message}")
            return 0
        } catch (e: IOException) {
            Log.w(TAG, "تعذر قراءة ${hit.name}: ${e.message}")
            return 0
        } finally {
            runCatching { temp.delete() }
        }
    }

    private suspend fun importPdfUri(
        hit: BusinessHit,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
    ): Int {
        val temp = File(applicationContext.cacheDir, ImportRules.tempFileName("pdf", hit.name, System.nanoTime()))
        var pages = emptyList<PdfToImageConverter.RenderedPage>()
        try {
            copyUri(hit.uri, temp) ?: return 0
            if (temp.length() == 0L) return 0
            pages = PdfToImageConverter.convertToPngPages(applicationContext, temp)
            if (pages.isEmpty()) {
                Log.w(TAG, "تعذر رسم صفحات ${hit.name}")
                repository.markSourceProcessed(hit.identity)
                done.add(hit.identity)
                return 0
            }
            val text = readText(pages.take(ContentGate.MAX_PDF_PAGES).map { it.file })
            var added = 0
            var failed = false
            for (page in pages) {
                currentCoroutineContext().ensureActive()
                val pageIdentity = ImportRules.pageIdentity(hit.identity, page.pageNumber)
                if (pageIdentity in existingPaths) continue
                when (
                    importOne(
                        repository = repository,
                        file = page.file,
                        identity = pageIdentity,
                        originalDate = hit.arrivedAt,
                        originalName = ImportRules.pageDisplayName(hit.name, page.pageNumber),
                        ocrText = text,
                        existingPaths = existingPaths,
                        sourceApp = SourceApp.WHATSAPP_BUSINESS,
                    )
                ) {
                    is ImportResult.Added -> added++
                    is ImportResult.Duplicate -> Unit
                    is ImportResult.Failed -> failed = true
                }
            }
            if (!failed) {
                repository.markSourceProcessed(hit.identity)
                done.add(hit.identity)
            }
            if (added > 0) Log.i(TAG, "أُرشف PDF من واتساب أعمال: ${hit.name} ($added صفحة)")
            return added
        } catch (e: SecurityException) {
            Log.w(TAG, "رفض الوصول لـ ${hit.name}: ${e.message}")
            return 0
        } catch (e: IOException) {
            Log.w(TAG, "تعذر قراءة ${hit.name}: ${e.message}")
            return 0
        } finally {
            runCatching { temp.delete() }
            pages.forEach { runCatching { it.file.delete() } }
        }
    }

    private suspend fun copyUri(uri: Uri, temp: File): File? = withContext(Dispatchers.IO) {
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext null
        temp
    }

    private suspend fun copyTo(doc: DocumentFile, temp: File): File? = withContext(Dispatchers.IO) {
        applicationContext.contentResolver.openInputStream(doc.uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext null
        temp
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_archive_scan"
        const val ONE_TIME_WORK_NAME = "manual_archive_scan"
        private const val MAX_DEPTH = 4
        private const val TAG = "FileScannerWorker"
        private val RUNNING = AtomicBoolean(false)
    }
}
