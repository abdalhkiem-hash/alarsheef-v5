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
import com.alarsheef.archive.ai.DocumentShape
import com.alarsheef.archive.ai.OcrEngine
import com.alarsheef.archive.ai.SmartClassifier
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
 *
 * في وضع «المستندات فقط» تمرّ الصورة ببوابة آمنة ([ImportGate.safeToArchive]) قبل
 * الأرشفة: غير المستند تُترك في مصدرها (لا تُرفض ولا تُخفي)، والشك يُحسم بالنص
 * (OCR محلي)، والفشل في الفحص يُبقي الملف — لا يضيع مستند صحيح أبدًا.
 */
class FileScannerWorker(context: Context, params: WorkerParameters)
    : CoroutineWorker(context, params) {

    /** إعداد «استيراد المستندات فقط» — يُقرأ مع كل فحص. */
    private var documentsOnlyFilter = true

    /** حصية قراءة نص صور «غير مستند» في الفحص الواحد حتى لا يتباطأ السير. */
    private var gateOcrBudget = 0

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
        documentsOnlyFilter = prefs.documentsOnly.first()
        gateOcrBudget = MAX_GATE_OCR_PER_SCAN
        val folderUri = prefs.importFolderUri.first()?.takeIf { it.isNotBlank() }
        val businessDocsUri = prefs.whatsappBusinessDocumentsTreeUri.first()?.takeIf { it.isNotBlank() }

        return try {
            val repository = ArchiveRepository(applicationContext)
            // نافذة الفحص تمتد إلى آخر فحص ناجح (بسقف 3 أيام): ما وصل بعد آخر فحص
            // وقبل منتصف الليل كان يسقط لأن البدء «اليوم بيومه» يتخطّاه إلى الأبد.
            val scanFrom = ImportRules.scanWindowStart(
                FileUtils.startOfToday(),
                prefs.lastScanAt.first(),
            )
            val existingPaths = ImportRules.normalizeExistingPaths(repository.findAllOriginalPaths())
            val done = repository.processedSourcePaths().toMutableSet()
            var count = scanWhatsappBusiness(repository, existingPaths, done, scanFrom)
            // المستندات قبل مجلد الصور: ملف PDF الذي وصل اليوم لا ينتظر عشرات آلاف الصور القديمة.
            var permissionLost = false
            if (businessDocsUri != null) {
                if (hasTreePermission(Uri.parse(businessDocsUri))) {
                    count += scanInbox(
                        Uri.parse(businessDocsUri),
                        repository,
                        existingPaths,
                        done,
                        SourceApp.WHATSAPP_BUSINESS,
                        scanFrom,
                    )
                } else {
                    // شجرة وثائق واتساب أعمال قيمة قديمة بلا زر في الإعدادات، وملفاتها (صورها
                    // وPDFها) تصل أصلًا عبر استعلام MediaStore — تُزالت القيمة الميتة حتى
                    // لا يبقى تحذير لا يُعالج من الواجهة.
                    prefs.setWhatsappBusinessDocumentsTreeUri(null)
                    Log.i(TAG, "أُزيلت شجرة واتساب أعمال القديمة (تغطيها قاعدة MediaStore)")
                }
            }
            if (folderUri != null) {
                if (hasTreePermission(Uri.parse(folderUri))) {
                    count += scanInbox(Uri.parse(folderUri), repository, existingPaths, done, SourceApp.INBOX, scanFrom)
                } else {
                    permissionLost = true
                    Log.w(TAG, "صلاحية مجلد الإدخال منتهية — يُتخطى الفحص")
                }
            }
            prefs.setLastScanAt(System.currentTimeMillis())
            // تحذير الواجهة: يبقى حتى تُعاد منح الصلاحية، ويُرفع حين ينجح الفحص كله.
            prefs.setScanWarning(
                if (permissionLost) "صلاحية مجلد الإدخال منتهية — أعد اختيار المجلد من الإعدادات" else null
            )
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
            runCatching {
                SettingsPreferences(applicationContext)
                    .setScanWarning("صلاحية مجلد الإدخال منتهية — أعد اختيار المجلد من الإعدادات")
            }
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "خطأ غير متوقع في الفحص", e)
            Result.failure()
        }
    }

    /** هل ما زالت منحة SAF الدائمة (للقراءة) لهذه الشجرة سارية؟ تُفقد بعد إزالة التطبيق. */
    private fun hasTreePermission(treeUri: Uri): Boolean =
        applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }

    /**
     * بوابة «المستندات فقط» قبل الأرشفة — على صور الفحص الآلي فقط (PDF لا تمرّ هنا،
     * والاستيراد اليدوي اختيار صريح لا يُفحص). شكل الصورة أولًا (بلا OCR وبلا شبكة):
     * إن لم يكن شكله مستندًا يُحسم النص بقراءة محلي واحدة، وأي تعذّر يُبقي الملف.
     *
     * @return true يُؤرشف، false يُترك في المصدر (يُعلَّم معالَجًا حتى لا يُعاد كل فحص).
     */
    private suspend fun passesContentGate(file: File, displayName: String): Boolean {
        if (!documentsOnlyFilter) return true
        val shape: DocumentShape? = try {
            SmartClassifier.documentShape(file, 0)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر فحص شكل $displayName: ${t.message}")
            null
        }
        if (shape != DocumentShape.NOT_DOCUMENT) {
            Log.i(TAG, "بوابة المستندات: يُؤرشف (شكل=$shape): $displayName")
            return true
        }
        if (gateOcrBudget <= 0) {
            Log.i(TAG, "بوابة المستندات: انتهى حد فحص النص — يُؤرشف احتياطًا: $displayName")
            return true
        }
        gateOcrBudget--
        val hasText: Boolean? = if (!OcrEngine.isAvailable(applicationContext)) {
            null
        } else try {
            val text = OcrEngine.recognizeFile(applicationContext, file)
            // null من المحرك = أقل من 3 أحرف مقروءة من كل المحركات = «لا نص» مؤكد.
            text != null && text.trim().length >= MIN_GATE_TEXT_CHARS
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر فحص نص $displayName: ${t.message}")
            null
        }
        val keep = ImportGate.safeToArchive(shape, hasText)
        Log.i(
            TAG,
            if (keep) {
                "بوابة المستندات: يُؤرشف (شكل=$shape، نص=$hasText): $displayName"
            } else {
                "بوابة المستندات: شكل غير مستند بلا نص — يُترك في المصدر: $displayName"
            },
        )
        return keep
    }

    private suspend fun scanInbox(
        treeUri: Uri,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
        sourceApp: SourceApp,
        scanFrom: Long,
    ): Int {
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return 0
        if (!root.isDirectory) return 0
        return visit(root, repository, existingPaths, done, scanFrom, sourceApp, 0)
    }

    private suspend fun visit(
        folder: DocumentFile,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
        scanFrom: Long,
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
                count += visit(doc, repository, existingPaths, done, scanFrom, sourceApp, depth + 1)
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
                ImportRules.isBeforeToday(doc.lastModified(), scanFrom)
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
            if (!passesContentGate(temp, name)) {
                repository.markSourceProcessed(identity)
                done.add(identity)
                return 0
            }
            return when (
                importOne(
                    repository = repository,
                    file = temp,
                    identity = identity,
                    originalDate = doc.lastModified(),
                    originalName = name,
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

    /** المكرر يُعلَّم في الذاكرة حتى لا يُعاد داخل نفس السير. الحفظ الدائم للملف يتم بعد اكتمال السير. */
    private suspend fun importOne(
        repository: ArchiveRepository,
        file: File,
        identity: String,
        originalDate: Long,
        originalName: String,
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
        scanFrom: Long,
    ): Int {
        if (!PermissionUtils.hasMediaPermission(applicationContext)) {
            Log.i(TAG, "لا صلاحية قراءة الصور — واتساب أعمال لا يُفحص")
            return 0
        }
        var count = 0
        count += queryBusiness(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            scanFrom,
            repository,
            existingPaths,
            done,
        )
        count += queryBusiness(
            MediaStore.Files.getContentUri("external"),
            scanFrom,
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
        scanFrom: Long,
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
                    arrayOf((scanFrom / 1000L).toString()),
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
                        if (!ImportRules.isBusinessArrival(relative, name, size, added, scanFrom)) continue
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
            if (!passesContentGate(temp, hit.name)) {
                repository.markSourceProcessed(hit.identity)
                done.add(hit.identity)
                return 0
            }
            return when (
                importOne(
                    repository = repository,
                    file = temp,
                    identity = hit.identity,
                    originalDate = hit.arrivedAt,
                    originalName = hit.name,
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

        /** أدنى نص يثبت أن صورة «شكلها غير مستند» ورقة فعلًا (يحسم ضجيج القراءة). */
        private const val MIN_GATE_TEXT_CHARS = 12

        /** سقف صور «غير مستند» يُفحص نصها في الفحص الواحد؛ ما زاد يُؤرشف احتياطًا. */
        private const val MAX_GATE_OCR_PER_SCAN = 50
    }
}
