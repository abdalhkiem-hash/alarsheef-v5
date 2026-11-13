package com.alarsheef.archive.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.alarsheef.archive.onedrive.OneDriveAuth
import com.alarsheef.archive.onedrive.OneDriveClient
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
            keepGateText = prefs.aiOcrEnabled.first()
            val existingPaths = ImportRules.normalizeExistingPaths(repository.findAllOriginalPaths())
            val done = repository.processedSourcePaths().toMutableSet()
            var count = scanWhatsappBusiness(repository, existingPaths, done)
            // ملف اليومية أولًا: فحصه سريع (مجلد واحد)، أما مجلد مستندات واتساب فيمرّ على آلاف
            // الملفات القديمة في كل دورة وقد يستغرق دقائق.
            if (OneDriveAuth.isSignedIn(applicationContext)) {
                count += scanOneDriveJournal(prefs.oneDrivePath.first(), repository, existingPaths, done)
            } else if (folderUri != null) {
                count += scanDailyJournal(Uri.parse(folderUri), repository, existingPaths, done)
            }
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

    /**
     * مجلد الإدخال = جذر أرشيف OneDrive. يدخل مجلد اليوم (`dd-MM-yyyy`) أينما كان تحت الجذر
     * ويستورد منه ملف «اليوميه العامة» وحده، مرة واحدة. تاريخ الأرشفة تاريخ المجلد لا وقت الرفع.
     */
    private suspend fun scanDailyJournal(
        treeUri: Uri,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
    ): Int {
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return 0
        if (!root.isDirectory) return 0
        val candidates = ImportRules.dailyFolderCandidates(System.currentTimeMillis())
        val folders = findDailyFolders(root, candidates)
        if (folders.isEmpty()) {
            Log.i(TAG, "لا مجلد لليوم بعد (${candidates.joinToString { it.name }})")
            return 0
        }
        var imported = 0
        for ((folder, day) in folders) {
            currentCoroutineContext().ensureActive()
            val doc = folder.listFiles().firstOrNull { f ->
                val n = f.name.orEmpty()
                f.isFile && ImportRules.isDailyFileName(n) &&
                    ImportRules.inboxFileKind(n, 1L).let {
                        it == ImportRules.InboxFileKind.PDF || it == ImportRules.InboxFileKind.IMAGE
                    }
            } ?: continue
            val name = doc.name.orEmpty()
            val identity = doc.uri.toString()
            if (identity in done || identity in existingPaths) continue
            val kind = ImportRules.inboxFileKind(name, doc.length().takeIf { it > 0L } ?: 1L)
            imported += when (kind) {
                ImportRules.InboxFileKind.PDF ->
                    importPdfAsIs(
                        { copyTo(doc, it) != null }, doc.lastModified(),
                        name, identity, repository, existingPaths, done, SourceApp.INBOX, day.noonMillis,
                    )
                ImportRules.InboxFileKind.IMAGE ->
                    importImage(doc, name, identity, repository, existingPaths, done, SourceApp.INBOX, day.noonMillis)
                else -> 0
            }
        }
        return imported
    }

    /**
     * الحساب مربوط بـ OneDrive: يبحث عن مجلد اليوم تحت [pathTemplate] (و{year} سنة المجلد)
     * ويستورد «اليوميه العامة» مرة واحدة. فشل الشبكة لا يوقف بقية الفحص، ويُعاد في الدورة التالية.
     */
    private suspend fun scanOneDriveJournal(
        pathTemplate: String,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
    ): Int {
        val client = OneDriveClient { OneDriveAuth.accessToken(applicationContext) }
        var imported = 0
        try {
            for (day in ImportRules.dailyFolderCandidates(System.currentTimeMillis())) {
                currentCoroutineContext().ensureActive()
                val year = day.name.takeLast(4).toInt()
                val base = pathTemplate.replace("{year}", year.toString())
                val folder = client.findFolder(base, day.name, year)
                if (folder == null) {
                    Log.i(TAG, "OneDrive: لا مجلد ${day.name} بعد تحت $base")
                    continue
                }
                val file = client.children(folder.id).firstOrNull {
                    !it.isFolder && ImportRules.isDailyFileName(it.name) && FileUtils.isPdf(it.name)
                }
                if (file == null) {
                    Log.i(TAG, "OneDrive: «${ImportRules.DAILY_FILE_NAME}» غير موجود في ${day.name} بعد")
                    continue
                }
                Log.i(TAG, "OneDrive: وُجد الملف ${file.name} في ${day.name}")
                val identity = "onedrive:${file.id}"
                if (identity in done || identity in existingPaths) {
                    Log.i(TAG, "OneDrive: ${file.name} في ${day.name} مستورد من قبل")
                    continue
                }
                imported += importPdfAsIs(
                    { client.downloadTo(file.id, it) }, day.noonMillis,
                    file.name, identity, repository, existingPaths, done, SourceApp.INBOX, day.noonMillis,
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "OneDrive: تعذّر القراءة الآن (${e.message}) — يُعاد لاحقًا")
        }
        return imported
    }

    /**
     * بحث بعرض المستويات عن مجلدات اليوم تحت [root]، بترتيب أولوية [candidates].
     * يُتجاوز كل مجلد اسمه سنة غير سنة اليوم، فلا يتغيّر شيء مع سنة جديدة. السقف يحمي من
     * شجرة كبيرة في السحابة.
     */
    private suspend fun findDailyFolders(
        root: DocumentFile,
        candidates: List<ImportRules.DailyFolder>,
    ): List<Pair<DocumentFile, ImportRules.DailyFolder>> {
        val years = candidates.map { it.name.takeLast(4).toInt() }
        val found = HashMap<String, DocumentFile>()
        var level = listOf(root)
        var listings = 0
        var depth = 0
        while (level.isNotEmpty() && depth <= MAX_DAILY_SEARCH_DEPTH && listings < MAX_DAILY_SEARCH_LISTINGS) {
            val next = ArrayList<DocumentFile>()
            for (folder in level) {
                if (listings >= MAX_DAILY_SEARCH_LISTINGS) break
                currentCoroutineContext().ensureActive()
                listings++
                for (child in folder.listFiles()) {
                    if (!child.isDirectory) continue
                    val childName = child.name.orEmpty()
                    if (candidates.any { it.name == childName }) {
                        found.putIfAbsent(childName, child)
                    } else if (years.any { ImportRules.shouldVisitDailySearchFolder(childName, it) }) {
                        next.add(child)
                    }
                }
            }
            if (candidates.all { it.name in found }) break
            // ما يحمل السنة في اسمه (2026، «اكتوبر 2026») أولًا.
            level = next.sortedBy { f -> if (Regex("20\\d{2}").containsMatchIn(f.name.orEmpty())) 0 else 1 }
            depth++
        }
        Log.i(TAG, "بحث مجلد اليوم: $listings قائمة، وُجد ${found.size}")
        return candidates.mapNotNull { c -> found[c.name]?.let { it to c } }
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
        dateOverride: Long? = null,
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
                    originalDate = dateOverride ?: doc.lastModified(),
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

    /**
     * ملف PDF يُؤرشف كما هو (نفس البايتات، بامتداد .pdf) دون تحويله إلى صور.
     * يُعرض بصفحاته عند الفتح. لا OCR ولا تحليل ذكي له؛ اسمه المرجع الوحيد.
     */
    private suspend fun importPdfAsIs(
        fetch: suspend (File) -> Boolean,
        fallbackDate: Long,
        name: String,
        identity: String,
        repository: ArchiveRepository,
        existingPaths: MutableSet<String>,
        done: MutableSet<String>,
        sourceApp: SourceApp,
        dateOverride: Long?,
    ): Int {
        val temp = File(applicationContext.cacheDir, ImportRules.tempFileName("pdf", name, System.nanoTime()))
        try {
            if (!fetch(temp)) {
                Log.w(TAG, "تعذّر جلب الملف $name")
                return 0
            }
            if (temp.length() == 0L) {
                Log.i(TAG, "يُترك حتى يكتمل: $name")
                return 0
            }
            return when (
                importOne(
                    repository = repository,
                    file = temp,
                    identity = identity,
                    originalDate = dateOverride ?: fallbackDate,
                    originalName = name,
                    ocrText = null,
                    existingPaths = existingPaths,
                    sourceApp = sourceApp,
                    preferredBaseName = name.substringBeforeLast('.'),
                )
            ) {
                is ImportResult.Added -> {
                    repository.markSourceProcessed(identity)
                    done.add(identity)
                    Log.i(TAG, "أُرشف PDF كما هو: $name")
                    1
                }
                is ImportResult.Duplicate -> {
                    repository.markSourceProcessed(identity)
                    done.add(identity)
                    Log.i(TAG, "PDF مكرر (بصمة مطابقة): $name")
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
        dateOverride: Long? = null,
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
                        originalDate = dateOverride ?: doc.lastModified(),
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

    /**
     * بوابة الصورة: الشكل والوجه ثم النص. تعيد حالة الاستيراد مع النص المقروء،
     * أو null إن لم تكن مستندًا (صورة شخصية/عائلية أو لا نص فيها).
     */
    private suspend fun gateImage(file: File, name: String): Pair<ContentVerified, String?>? {
        val hasFace = runCatching { FaceDetectionUtil.hasFace(file) }.getOrDefault(false)
        val assessment = ImportGate.evaluate(file, hasFace, excludePersonalPhotos = true, documentsOnly = true, displayName = name)
        if (assessment.decision == ImportGate.Decision.REJECT) return null
        val outcome = if (keepGateText) {
            runCatching {
                ContentGate.evaluate(
                    applicationContext, listOf(file), allowCloud = false,
                    escalateWhenPoor = assessment.decision == ImportGate.Decision.STRONG,
                )
            }
                .getOrDefault(ContentGate.Outcome.Unavailable)
        } else {
            ContentGate.Outcome.Unavailable
        }
        val (evidence, text) = when (outcome) {
            is ContentGate.Outcome.Accepted -> ImportGate.TextEvidence.ACCEPTED to outcome.text
            is ContentGate.Outcome.Ambiguous -> ImportGate.TextEvidence.PARTIAL to outcome.text
            is ContentGate.Outcome.Rejected ->
                (if (outcome.clear) ImportGate.TextEvidence.REJECTED_CLEAR else ImportGate.TextEvidence.REJECTED) to null
            ContentGate.Outcome.NoText -> ImportGate.TextEvidence.NO_TEXT to null
            ContentGate.Outcome.Unavailable -> ImportGate.TextEvidence.UNREADABLE to null
        }
        val status = ImportGate.finalStatus(assessment.decision, evidence, assessment.shape) ?: return null
        return status to text
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
        verified: ContentVerified = ContentVerified.ACCEPTED,
        preferredBaseName: String? = null,
    ): ImportResult {
        val result = repository.importFile(
            preferredBaseName = preferredBaseName,
            sourceFile = file,
            sourceApp = sourceApp,
            deleteSourceAfterImport = true,
            countDuplicate = false,
            originalPath = identity,
            originalDate = originalDate,
            contentVerified = verified,
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
            // واتساب أعمال يصله الشخصي والعائلي أيضًا: الصورة تمر على البوابة، وغير المستند لا يدخل.
            val gated = gateImage(temp, hit.name)
            if (gated == null) {
                repository.markSourceProcessed(hit.identity)
                done.add(hit.identity)
                Log.i(TAG, "غير مستند — لم تُؤرشف: ${hit.name}")
                return 0
            }
            return when (
                importOne(
                    repository = repository,
                    file = temp,
                    identity = hit.identity,
                    originalDate = hit.arrivedAt,
                    originalName = hit.name,
                    ocrText = gated.second,
                    existingPaths = existingPaths,
                    sourceApp = SourceApp.WHATSAPP_BUSINESS,
                    verified = gated.first,
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
        private const val MAX_DAILY_SEARCH_DEPTH = 5
        private const val MAX_DAILY_SEARCH_LISTINGS = 80
        private const val TAG = "FileScannerWorker"
        private val RUNNING = AtomicBoolean(false)
    }
}
