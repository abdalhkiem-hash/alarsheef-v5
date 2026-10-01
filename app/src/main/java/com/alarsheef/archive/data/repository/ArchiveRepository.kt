package com.alarsheef.archive.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.alarsheef.archive.data.AppDatabase
import com.alarsheef.archive.data.dao.DayGroupDao
import com.alarsheef.archive.data.dao.SubFolderDao
import com.alarsheef.archive.data.dao.YearSummary
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.CustomLabel
import com.alarsheef.archive.data.entities.DayGroup
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.entities.SubFolder
import com.alarsheef.archive.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class YearRow(val year: Int, val fileCount: Int, val latestMonth: Int, val label: String?)
data class MonthRow(val month: Int, val fileCount: Int, val label: String?)
data class DayRow(val day: Int, val fileCount: Int, val lastImportedAt: Long, val label: String?)
data class SubFolderRow(val id: Long, val name: String)
data class DayGroupRow(val id: Long, val dayNumber: Int)

sealed class ImportResult {
    data object Added : ImportResult()
    data class Duplicate(val newCount: Int) : ImportResult()
    data object Failed : ImportResult()
}

class ArchiveRepository(context: Context) {

    private val context = context.applicationContext
    private val db = AppDatabase.getInstance(this.context)
    private val imageDao = db.archivedImageDao()
    private val labelDao = db.customLabelDao()
    private val aiDao = db.aiDao()
    private val subFolderDao = db.subFolderDao()
    private val dayGroupDao = db.dayGroupDao()
    private val rootDir get() = this.context.filesDir

    // ---------- قراءة ----------

    fun observeYearRows(): Flow<List<YearRow>> =
        combine(imageDao.observeYears(), labelDao.observeAll()) { years, labels ->
            val labelMap = labels.associate { it.scopeKey to it.label }
            years.map { y: YearSummary ->
                YearRow(y.year, y.fileCount, y.latestMonth, labelMap["y-${y.year}"])
            }
        }

    fun searchAll(query: String): Flow<List<ArchivedImage>> = imageDao.searchAll(query)

    fun observeMonthRows(year: Int): Flow<List<MonthRow>> =
        combine(imageDao.observeMonths(year), labelDao.observeAll()) { months, labels ->
            val labelMap = labels.associate { it.scopeKey to it.label }
            months.map { m -> MonthRow(m.month, m.fileCount, labelMap["m-$year-${m.month}"]) }
        }

    fun observeDayRows(year: Int, month: Int): Flow<List<DayRow>> =
        combine(imageDao.observeDays(year, month), labelDao.observeAll()) { days, labels ->
            val labelMap = labels.associate { it.scopeKey to it.label }
            days.map { d -> DayRow(d.day, d.fileCount, d.lastImportedAt, labelMap["d-$year-$month-${d.day}"]) }
        }

    fun observeSubFolders(year: Int, month: Int): Flow<List<SubFolderRow>> =
        subFolderDao.observeByMonth(year, month).map { list ->
            list.map { SubFolderRow(it.id, it.name) }
        }

    suspend fun createSubFolder(year: Int, month: Int, name: String): Long =
        withContext(Dispatchers.IO) {
            subFolderDao.insert(SubFolder(year = year, month = month, name = name))
        }

    suspend fun deleteSubFolder(id: Long) = withContext(Dispatchers.IO) {
        subFolderDao.delete(id)
    }

    fun observeDayGroups(year: Int, month: Int): Flow<List<DayGroupRow>> =
        dayGroupDao.observeByMonth(year, month).map { list ->
            list.map { DayGroupRow(it.id, it.dayNumber) }
        }

    suspend fun createDayGroup(year: Int, month: Int, dayNumber: Int): Long =
        withContext(Dispatchers.IO) {
            dayGroupDao.insert(DayGroup(year = year, month = month, dayNumber = dayNumber))
        }

    suspend fun deleteDayGroup(id: Long) = withContext(Dispatchers.IO) {
        dayGroupDao.delete(id)
    }

    suspend fun deleteDayOrGroup(year: Int, month: Int, day: Int) = withContext(Dispatchers.IO) {
        val group = dayGroupDao.observeByMonth(year, month).first().find { it.dayNumber == day }
        if (group != null) {
            dayGroupDao.delete(group.id)
        } else {
            imageDao.deleteDay(year, month, day)
        }
    }

    fun observeDayImages(year: Int, month: Int, day: Int): Flow<List<ArchivedImage>> =
        imageDao.observeImagesForDay(year, month, day)

    // ---------- لوحة الرئيسية (بطاقة الإحصاء + آخر الصور) ----------

    /** إحصاء شامل جاهز لعرضه في بطاقة الرئيسية: الملفات، الألبومات، المستندات، الوجوه، صور اليوم. */
    data class DashboardStats(
        val totalFiles: Int,
        val albumCount: Int,
        val documentCount: Int,
        val faceCount: Int,
        val todayCount: Int
    )

    fun observeDashboardStats(year: Int): Flow<DashboardStats> {
        val startOfDay = FileUtils.startOfToday()
        return combine(
            imageDao.observeOverallStats(year),
            imageDao.observeTodayCount(startOfDay),
            aiDao.observeDocumentCount(year),
            aiDao.observeFaceGroupRows()
        ) { overall, today, docs, faceGroups ->
            DashboardStats(
                totalFiles = overall.totalFiles,
                albumCount = overall.albumCount,
                documentCount = docs,
                faceCount = faceGroups.size,
                todayCount = today
            )
        }
    }

    fun observeRecentImages(limit: Int = 8): Flow<List<ArchivedImage>> =
        imageDao.observeRecentImages(limit)

    /**
     * المساحة الفعلية المستخدمة على القرص لكل الملفات المؤرشفة (بايت).
     * قراءة IO لمرة واحدة (لا Flow) — تُستدعى عند فتح الرئيسية فقط، لا تتكرر مع كل تغيير.
     */
    suspend fun computeStorageUsedBytes(): Long = withContext(Dispatchers.IO) {
        imageDao.getAllStoredPaths().sumOf { path ->
            runCatching { File(path).length() }.getOrDefault(0L)
        }
    }

    // ---------- قراءات لمرة واحدة للتصدير والمشاركة بالعناقيد ----------

    suspend fun allForExport(): List<ArchivedImage> = imageDao.getAllOnce()

    suspend fun byYearForExport(year: Int): List<ArchivedImage> = imageDao.getByYearOnce(year)

    suspend fun byMonthForExport(year: Int, month: Int): List<ArchivedImage> =
        imageDao.getByMonthOnce(year, month)

    suspend fun byDayForExport(year: Int, month: Int, day: Int): List<ArchivedImage> =
        imageDao.getByDayOnce(year, month, day)

    // ---------- تسميات مخصصة ----------

    suspend fun setLabel(scopeKey: String, label: String) {
        if (label.isBlank()) labelDao.clearLabel(scopeKey) else labelDao.setLabel(CustomLabel(scopeKey, label))
    }

    // ---------- حذف ----------

    /**
     * يحذف كل بقايا التحليل الذكي لصور محددة (نصوص OCR + وسوم + أعضاء وجوه)
     * مع ملفات الوجوه المقصوصة، ليبقى الأرشيف متماسكًا عند حذف الصور أو إعادة التحليل.
     * التقسيم إلى دفعات حماية من حد أقصى المتغيرات في SQLite (999).
     */
    private suspend fun cleanupAiRowsForImages(images: List<ArchivedImage>) {
        val ids = images.map { it.id }
        if (ids.isEmpty()) return
        ids.chunked(400).forEach { chunk ->
            aiDao.cropPathsForImageIds(chunk).forEach { runCatching { File(it).delete() } }
            aiDao.deleteOcrForImageIds(chunk)
            aiDao.deleteLabelsForImageIds(chunk)
            aiDao.deleteMembersForImageIds(chunk)
            aiDao.deleteCloudMetaForImageIds(chunk)
        }
    }

    suspend fun deleteYear(year: Int) = withContext(Dispatchers.IO) {
        cleanupAiRowsForImages(imageDao.getByYearOnce(year))
        File(rootDir, "archive/$year").deleteRecursively()
        imageDao.deleteYear(year)
        labelDao.clearAllUnderPrefix("y-$year")
        labelDao.clearAllUnderPrefix("m-$year-")
        labelDao.clearAllUnderPrefix("d-$year-")
    }

    suspend fun deleteMonth(year: Int, month: Int) = withContext(Dispatchers.IO) {
        cleanupAiRowsForImages(imageDao.getByMonthOnce(year, month))
        val monthStr = FileUtils.twoDigits(month)
        File(rootDir, "archive/$year/$monthStr").deleteRecursively()
        imageDao.deleteMonth(year, month)
        labelDao.clearLabel("m-$year-$month")
        labelDao.clearAllUnderPrefix("d-$year-$month-")
    }

    suspend fun deleteDay(year: Int, month: Int, day: Int) = withContext(Dispatchers.IO) {
        cleanupAiRowsForImages(imageDao.getByDayOnce(year, month, day))
        val monthStr = FileUtils.twoDigits(month)
        val dayStr = FileUtils.twoDigits(day)
        File(rootDir, "archive/$year/$monthStr/$dayStr").deleteRecursively()
        imageDao.deleteDay(year, month, day)
        labelDao.clearLabel("d-$year-$month-$day")
    }

    // ---------- نقل / حذف ملفات محددة ----------

    /** ينقل الملفات فعليًا ثم يحدّث قاعدة البيانات. لو الملف الأصلي مفقود ما نحدّث السجل حتى لا يصير مسار وهمي */
    suspend fun moveImages(
        images: List<ArchivedImage>,
        targetYear: Int,
        targetMonth: Int,
        targetDay: Int
    ): Int = withContext(Dispatchers.IO) {
        val targetDir = FileUtils.dayFolder(rootDir, targetYear, targetMonth, targetDay)
        var moved = 0
        images.forEach { img ->
            val source = File(img.storedPath)
            if (!source.exists()) return@forEach
            val ext = source.extension.ifBlank { "jpg" }
            val newName = FileUtils.uniqueName(source.nameWithoutExtension, ext, targetDir)
            val destination = File(targetDir, newName)
            val ok = source.renameTo(destination) || runCatching {
                source.copyTo(destination, overwrite = true); source.delete()
            }.isSuccess
            if (ok) {
                imageDao.moveImage(img.id, targetYear, targetMonth, targetDay, destination.absolutePath)
                moved++
            }
        }
        moved
    }

    suspend fun deleteImages(images: List<ArchivedImage>) = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext
        cleanupAiRowsForImages(images)
        images.forEach { File(it.storedPath).delete() }
        images.map { it.id }.chunked(400).forEach { imageDao.deleteByIds(it) }
    }

    // ---------- مشاركة ----------

    fun getShareUri(image: ArchivedImage): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(image.storedPath))

    fun getShareUriForFile(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * يدمج الصور المحددة بترتيبها في ملف PDF واحد داخل مساحة الكاش.
     * الصور اللي تعذّر فك ترميزها تُتخطى بدل ما يطيح التطبيق، ويُرجع null لو ما نجحت ولا صفحة.
     */
    suspend fun mergeImagesToPdf(images: List<ArchivedImage>): File? = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext null
        val document = PdfDocument()
        var pageNumber = 0
        try {
            images.forEach { img ->
                val bitmap = BitmapFactory.decodeFile(img.storedPath) ?: return@forEach
                try {
                    pageNumber++
                    val pageInfo = PdfDocument.PageInfo
                        .Builder(bitmap.width, bitmap.height, pageNumber)
                        .create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            if (pageNumber == 0) return@withContext null
            val outFile = File(context.cacheDir, "merged_${System.currentTimeMillis()}.pdf")
            FileOutputStream(outFile).use { document.writeTo(it) }
            outFile
        } catch (e: Exception) {
            null
        } finally {
            document.close()
        }
    }

    // ---------- استيراد مع فحص التكرار بالبصمة الرقمية ----------

    /**
     * @param countDuplicate يزيد عدّاد "استُلمت N مرات" عند التكرار.
     * @param originalPath المسار الأصلي للملف في مصدره؛ يُعبَّأ لملفات الفحص
     *        التلقائي الثابتة فقط (يُستخدم لتخطي إعادة معالجة نفس الملف يوميًا).
     * @param originalDate تاريخ الملف الأصلي (التقاط/استلام) بالمللي ثانية؛
     *        يُستخدم لوضعه في مجلد تاريخه الصحيح بدل تاريخ الاستيراد.
     */
    suspend fun importFile(
        sourceFile: File,
        sourceApp: SourceApp,
        deleteSourceAfterImport: Boolean = true,
        countDuplicate: Boolean = true,
        originalPath: String? = null,
        originalDate: Long? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        var destination: File? = null
        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) return@withContext ImportResult.Failed

            val hash = FileUtils.sha256Of(sourceFile)
            val existing = imageDao.findByContentHash(hash)
            if (existing != null) {
                if (countDuplicate) imageDao.incrementReceivedCount(existing.id)
                // تملأ مسار المصدر على السجل الموجود إن كان فارغًا، حتى لا يُعاد
                // اكتشاف نفس الملف في كل فحص (بوابة المسارات السريعة تعتمد عليه).
                if (originalPath != null && existing.originalPath == null) {
                    imageDao.claimOriginalPath(existing.id, originalPath)
                }
                if (deleteSourceAfterImport) sourceFile.delete()
                return@withContext ImportResult.Duplicate(existing.receivedCount + if (countDuplicate) 1 else 0)
            }

            val dateMillis = originalDate?.takeIf { it > 0 }
                ?: sourceFile.lastModified().takeIf { it > 0 }
                ?: System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = dateMillis }
            val year = cal.get(java.util.Calendar.YEAR)
            val month = cal.get(java.util.Calendar.MONTH) + 1
            val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val targetDir = FileUtils.dayFolder(rootDir, year, month, day)
            val ext = sourceFile.extension.ifBlank { "jpg" }
            val baseName = "IMG_${year}${FileUtils.twoDigits(month)}${FileUtils.twoDigits(day)}_${System.currentTimeMillis() % 100000}"
            val destName = FileUtils.uniqueName(baseName, ext, targetDir)
            val destFile = File(targetDir, destName)
            destination = destFile

            sourceFile.copyTo(destFile, overwrite = true)
            val capturedAt = dateMillis
            if (deleteSourceAfterImport) sourceFile.delete()

            imageDao.insert(
                ArchivedImage(
                    fileName = destName,
                    storedPath = destFile.absolutePath,
                    contentHash = hash,
                    sourceApp = sourceApp,
                    receivedCount = 1,
                    year = year, month = month, day = day,
                    importedAt = System.currentTimeMillis(),
                    capturedAt = capturedAt,
                    originalPath = originalPath
                )
            )
            ImportResult.Added
        } catch (e: Exception) {
            // لو فشل الإدراج (تضارب فهرس التفرّد عند سباق فحصين) يبقى الملف المنسوخ
            // على القرص بلا سجل — نحذفه حتى لا يتراكم كملف يتيم.
            destination?.let { dest -> runCatching { if (dest.exists()) dest.delete() } }
            ImportResult.Failed
        }
    }

    /**
     * يستورد ملفًا عبر content URI (MediaStore أو SAF): ينسخه مؤقتًا إلى الكاش
     * ثم يستدعي [importFile] العادي. الملف المؤقت يُحذف بعد الاستيراد.
     */
    suspend fun importFile(
        sourceUri: Uri,
        sourceApp: SourceApp,
        deleteSourceAfterImport: Boolean = true,
        countDuplicate: Boolean = false,
        originalPath: String? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val ext = contentExtension(context, sourceUri)
            val temp = File(context.cacheDir, "import_${System.nanoTime()}_$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult.Failed
            try {
                importFile(temp, sourceApp, deleteSourceAfterImport, countDuplicate, originalPath)
            } finally {
                runCatching { temp.delete() }
            }
        } catch (e: Exception) {
            ImportResult.Failed
        }
    }

    private fun contentExtension(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else uri.lastPathSegment ?: "file"
                } else uri.lastPathSegment ?: "file"
            } ?: uri.lastPathSegment ?: "file"
        }.getOrNull() ?: "file"
        return name.substringAfterLast('.', "").ifBlank { "jpg" }
    }

    /**
     * استعادة ملف من أرشيف ZIP سابق: يُثبَّت في تاريخه الأصلي
     */
    suspend fun importIntoDate(
        sourceFile: File,
        sourceApp: SourceApp,
        year: Int,
        month: Int,
        day: Int,
        capturedAt: Long,
        preferredName: String?
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) return@withContext ImportResult.Failed

            val hash = FileUtils.sha256Of(sourceFile)
            if (imageDao.findByContentHash(hash) != null) return@withContext ImportResult.Duplicate(-1)

            val targetDir = FileUtils.dayFolder(rootDir, year, month, day)
            val ext = sourceFile.extension.ifBlank { "jpg" }
            val baseName = preferredName?.substringBeforeLast('.', "")?.ifBlank { null }
                ?: "IMG_${year}${FileUtils.twoDigits(month)}${FileUtils.twoDigits(day)}_${System.currentTimeMillis() % 100000}"
            val destName = FileUtils.uniqueName(baseName, ext, targetDir)
            val destination = File(targetDir, destName)

            sourceFile.copyTo(destination, overwrite = true)

            imageDao.insert(
                ArchivedImage(
                    fileName = destName,
                    storedPath = destination.absolutePath,
                    contentHash = hash,
                    sourceApp = sourceApp,
                    receivedCount = 1,
                    year = year, month = month, day = day,
                    importedAt = System.currentTimeMillis(),
                    capturedAt = capturedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    originalPath = null
                )
            )
            ImportResult.Added
        } catch (e: Exception) {
            ImportResult.Failed
        }
    }

     suspend fun findByOriginalPath(path: String): ArchivedImage? =
         imageDao.findByOriginalPath(path)

     suspend fun findAllOriginalPaths(): List<String> =
         imageDao.findAllOriginalPaths()

     // ---------- الذكاء الاصطناعي (التحليل + الوجوه + البحث) ----------

    fun observeLabelsForImage(imageId: Long) = aiDao.observeLabelsForImage(imageId)

    fun observeOcrForImage(imageId: Long) = aiDao.observeOcrByImage(imageId)

    fun observeFaceGroupRows() = aiDao.observeFaceGroupRows()

    fun observeFaceMembers(groupKey: String) = aiDao.observeFaceMembers(groupKey)

    fun observePendingAiCount(): Flow<Int> = imageDao.observePendingAiCount()

    suspend fun pendingAiCountOnce(): Int = imageDao.pendingAiCountOnce()

    suspend fun pendingAiImages(limit: Int) = imageDao.getPendingAiImages(limit)

    suspend fun markAiAnalyzed(id: Long) = imageDao.markAiAnalyzed(id, System.currentTimeMillis())

    suspend fun resetAiAnalysisForReanalyze() = imageDao.resetAiAnalysis()

    suspend fun saveOcrText(imageId: Long, text: String) =
        aiDao.upsertOcr(com.alarsheef.archive.data.entities.OcrText(imageId, text, System.currentTimeMillis()))

    suspend fun saveLabels(imageId: Long, labels: List<com.alarsheef.archive.ai.ClassifiedLabel>) {
        aiDao.deleteLabelsForImage(imageId)
        val now = System.currentTimeMillis()
        aiDao.insertLabels(labels.map { com.alarsheef.archive.data.entities.ImageLabel(imageId = imageId, label = it.label, confidence = it.confidence, createdAt = now) })
    }

    fun observeCloudMeta(imageId: Long) = aiDao.observeCloudMeta(imageId)

    /** يحفظ نتائج التصنيف السحابي (وصف + فاتورة) لصورة — يستبدل أي نتيجة سابقة. */
    suspend fun saveCloudMeta(
        imageId: Long,
        description: String,
        invoice: com.alarsheef.archive.ai.InvoiceInfo?,
        model: String
    ) = aiDao.upsertCloudMeta(
        com.alarsheef.archive.data.entities.AiCloudMeta(
            imageId = imageId,
            description = description,
            vendor = invoice?.vendor,
            invoiceDate = invoice?.date,
            amount = invoice?.amount,
            currency = invoice?.currency,
            details = invoice?.details,
            model = model,
            analyzedAt = System.currentTimeMillis()
        )
    )

    suspend fun upsertFaceGroup(groupKey: String, name: String?) =
        aiDao.insertFaceGroup(com.alarsheef.archive.data.entities.FaceGroup(groupKey, name))

    suspend fun addFaceMember(groupKey: String, imageId: Long, descriptorHash: String, cropPath: String) =
        aiDao.insertFaceMember(
            com.alarsheef.archive.data.entities.FaceGroupMember(
                imageId = imageId, groupKey = groupKey,
                descriptorHash = descriptorHash, cropPath = cropPath
            )
        )

    suspend fun faceGroupRepresentatives() = aiDao.faceGroupRepresentatives()

    suspend fun renameFaceGroup(groupKey: String, name: String?) = aiDao.renameFaceGroup(groupKey, name)

    suspend fun deleteFaceGroup(groupKey: String) {
        aiDao.deleteFaceGroupMembers(groupKey)
        aiDao.deleteFaceGroup(groupKey)
    }

    /**
     * يمسح أعضاء الوجوه + ملفات القصّ الخاصة بصورة واحدة قبل إعادة تحليلها،
     * لضمان أن "إعادة التحليل" Idempotent (لا تكرار ولا تراكم ملفات).
     */
    suspend fun purgeFaceDataForImage(imageId: Long) {
        aiDao.membersForImage(imageId).forEach { runCatching { File(it.cropPath).delete() } }
        aiDao.deleteMembersForImage(imageId)
    }

    /** اقتراحات أسماء ذكية لنطاق التسمية، تُحسب من وسومه ونصوصه وأسماء مجموعاته */
    suspend fun getScopeAiSuggestions(scopeKey: String): List<String> = withContext(Dispatchers.IO) {
        if (scopeKey.startsWith("y-")) {
            val year = scopeKey.removePrefix("y-").toIntOrNull() ?: return@withContext emptyList()
            val images = imageDao.getByYearOnce(year)
            computeSuggestions(images)
        } else if (scopeKey.startsWith("m-")) {
            val parts = scopeKey.removePrefix("m-").split("-")
            val year = parts.getOrNull(0)?.toIntOrNull() ?: return@withContext emptyList()
            val month = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext emptyList()
            val images = imageDao.getByMonthOnce(year, month)
            computeSuggestions(images)
        } else if (scopeKey.startsWith("d-")) {
            val parts = scopeKey.removePrefix("d-").split("-")
            val year = parts.getOrNull(0)?.toIntOrNull() ?: return@withContext emptyList()
            val month = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext emptyList()
            val day = parts.getOrNull(2)?.toIntOrNull() ?: return@withContext emptyList()
            val images = imageDao.getByDayOnce(year, month, day)
            computeSuggestions(images)
        } else {
            emptyList()
        }
    }

    private suspend fun computeSuggestions(images: List<ArchivedImage>): List<String> {
        if (images.isEmpty()) return emptyList()
        val ids = images.map { it.id }
        val allLabels = mutableListOf<String>()
        val textBuilder = StringBuilder()
        ids.take(400).forEach { id ->
            allLabels += aiDao.labelsForImage(id)
            aiDao.textForImage(id)?.let {
                if (textBuilder.length < 4000) textBuilder.append(it).append('\n')
            }
        }
        // أسماء مجموعات الوجوه المسماة الظاهرة في هذه الصور تحديدًا
        val faceNames = if (ids.isEmpty()) emptyList() else aiDao.namedGroupsForImages(ids)
        return com.alarsheef.archive.ai.NameSuggester.suggestFor(
            labels = allLabels,
            ocrText = textBuilder.toString(),
            faceNames = faceNames
        )
    }

    suspend fun importFromUri(uri: Uri, sourceApp: SourceApp): ImportResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val mime = context.contentResolver.getType(uri)
            val ext = when {
                mime?.contains("png") == true -> "png"
                mime?.contains("webp") == true -> "webp"
                else -> "jpg"
            }
            val temp = File(context.cacheDir, "import_${System.currentTimeMillis()}.$ext")
            tempFile = temp
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult.Failed
            importFile(temp, sourceApp)
        } catch (e: Exception) {
            tempFile?.delete()
            ImportResult.Failed
        }
    }

    /** يجهّز ملف وجهة مؤقت + رابط FileProvider لاستخدامهما مع كاميرا النظام */
    fun createCameraCaptureTarget(): Pair<File, Uri> {
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }
}
