package com.alarsheef.archive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alarsheef.archive.data.entities.ArchivedImage
import kotlinx.coroutines.flow.Flow

data class YearSummary(val year: Int, val fileCount: Int, val latestMonth: Int)
data class MonthSummary(val month: Int, val fileCount: Int)
data class DaySummary(val day: Int, val fileCount: Int, val lastImportedAt: Long)
data class OverallStats(val totalFiles: Int, val albumCount: Int)

@Dao
interface ArchivedImageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(image: ArchivedImage): Long

    @Query("SELECT * FROM archived_images WHERE contentHash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): ArchivedImage?

    @Query("SELECT * FROM archived_images WHERE originalPath = :path LIMIT 1")
    suspend fun findByOriginalPath(path: String): ArchivedImage?

    @Query("SELECT originalPath FROM archived_images WHERE originalPath IS NOT NULL")
    suspend fun findAllOriginalPaths(): List<String>

    /**
     * يملأ مسار الملف الأصلي على سجل موجود لو كان فارغًا (عند اكتشاف تكرار بالبصمة).
     * يمنع إعادة فحص نفس الملف في كل يوم — بدون هذا يبقى originalPath = null للأبد
     * فتتجاهله بوابة المسارات السريعة وتُعاد معالجته كل فحص.
     */
    @Query("UPDATE archived_images SET originalPath = :path WHERE id = :id AND originalPath IS NULL")
    suspend fun claimOriginalPath(id: Long, path: String)

    @Query("UPDATE archived_images SET receivedCount = receivedCount + 1 WHERE id = :id")
    suspend fun incrementReceivedCount(id: Long)

    @Query(
        """SELECT year, COUNT(*) as fileCount, MAX(month) as latestMonth
           FROM archived_images GROUP BY year ORDER BY year DESC"""
    )
    fun observeYears(): Flow<List<YearSummary>>

    @Query(
        """SELECT month, COUNT(*) as fileCount FROM archived_images
           WHERE year = :year GROUP BY month ORDER BY month DESC"""
    )
    fun observeMonths(year: Int): Flow<List<MonthSummary>>

    @Query(
        """SELECT day, COUNT(*) as fileCount, MAX(importedAt) as lastImportedAt
           FROM archived_images WHERE year = :year AND month = :month
           GROUP BY day ORDER BY day DESC"""
    )
    fun observeDays(year: Int, month: Int): Flow<List<DaySummary>>

    @Query(
        """SELECT * FROM archived_images WHERE year = :year AND month = :month AND day = :day
           ORDER BY importedAt DESC"""
    )
    fun observeImagesForDay(year: Int, month: Int, day: Int): Flow<List<ArchivedImage>>

    @Query(
        """UPDATE archived_images SET year = :newYear, month = :newMonth, day = :newDay, storedPath = :newPath
           WHERE id = :id"""
    )
    suspend fun moveImage(id: Long, newYear: Int, newMonth: Int, newDay: Int, newPath: String)

    @Query("DELETE FROM archived_images WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query(
        """SELECT * FROM archived_images
           WHERE fileName LIKE '%' || :query || '%'
              OR id IN (SELECT imageId FROM ocr_texts WHERE text LIKE '%' || :query || '%')
              OR id IN (SELECT imageId FROM image_labels WHERE label LIKE '%' || :query || '%')
              OR id IN (SELECT imageId FROM ai_cloud_meta
                        WHERE description LIKE '%' || :query || '%'
                           OR vendor LIKE '%' || :query || '%'
                           OR details LIKE '%' || :query || '%')
              OR id IN (
                  SELECT imageId FROM face_group_members
                  WHERE groupKey IN (SELECT groupKey FROM face_groups
                                     WHERE name LIKE '%' || :query || '%')
              )
           ORDER BY year DESC, month DESC, day DESC"""
    )
    fun searchAll(query: String): Flow<List<ArchivedImage>>

    @Query("DELETE FROM archived_images WHERE year = :year")
    suspend fun deleteYear(year: Int)

    @Query("DELETE FROM archived_images WHERE year = :year AND month = :month")
    suspend fun deleteMonth(year: Int, month: Int)

    @Query("DELETE FROM archived_images WHERE year = :year AND month = :month AND day = :day")
    suspend fun deleteDay(year: Int, month: Int, day: Int)

    // ---------- حالة التحليل الذكي ----------

    @Query("SELECT * FROM archived_images WHERE aiAnalyzedAt IS NULL ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingAiImages(limit: Int): List<ArchivedImage>

    @Query("SELECT COUNT(*) FROM archived_images WHERE aiAnalyzedAt IS NULL")
    fun observePendingAiCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM archived_images WHERE aiAnalyzedAt IS NULL")
    suspend fun pendingAiCountOnce(): Int

    @Query("SELECT * FROM archived_images WHERE contentVerified = 2 ORDER BY importedAt DESC")
    fun observePendingReviewImages(): Flow<List<ArchivedImage>>

    @Query("UPDATE archived_images SET aiAnalyzedAt = :time WHERE id = :id")
    suspend fun markAiAnalyzed(id: Long, time: Long)

    @Query("UPDATE archived_images SET contentVerified = :status WHERE id = :id")
    suspend fun updateContentVerified(id: Long, status: com.alarsheef.archive.data.entities.ContentVerified)

    @Query("UPDATE archived_images SET aiAnalyzedAt = NULL")
    suspend fun resetAiAnalysis()

    // ---------- قراءات لمرة واحدة (للتصدير/المشاركة بالعناقيد) ----------

    @Query("SELECT * FROM archived_images ORDER BY year DESC, month DESC, day DESC, importedAt DESC")
    suspend fun getAllOnce(): List<ArchivedImage>

    @Query("SELECT * FROM archived_images WHERE year = :year ORDER BY month DESC, day DESC, importedAt DESC")
    suspend fun getByYearOnce(year: Int): List<ArchivedImage>

    @Query("SELECT * FROM archived_images WHERE year = :year AND month = :month ORDER BY day DESC, importedAt DESC")
    suspend fun getByMonthOnce(year: Int, month: Int): List<ArchivedImage>

    @Query("SELECT * FROM archived_images WHERE year = :year AND month = :month AND day = :day ORDER BY importedAt DESC")
    suspend fun getByDayOnce(year: Int, month: Int, day: Int): List<ArchivedImage>

    // ---------- لوحة الرئيسية (إجماليات + آخر الصور) ----------

    /** إجمالي ملفات سنة معيّنة + عدد الأشهر التي تحمل محتوى فيها ("ألبومات"). */
    @Query(
        """SELECT COUNT(*) AS totalFiles, COUNT(DISTINCT month) AS albumCount
           FROM archived_images WHERE year = :year"""
    )
    fun observeOverallStats(year: Int): Flow<OverallStats>

    /** عدد الملفات التي دخلت الأرشيف اليوم (منذ منتصف الليل بتوقيت الجهاز). */
    @Query("SELECT COUNT(*) FROM archived_images WHERE importedAt >= :startOfDayMillis")
    fun observeTodayCount(startOfDayMillis: Long): Flow<Int>

    /** آخر N ملف دخل الأرشيف، بلا اعتبار للسنة/الشهر — لشريط "آخر الصور". */
    @Query("SELECT * FROM archived_images ORDER BY importedAt DESC LIMIT :limit")
    fun observeRecentImages(limit: Int): Flow<List<ArchivedImage>>

    /** كل المسارات المخزَّنة — تُستخدم لحساب المساحة المستخدمة فعليًا على القرص (قراءة لمرة واحدة، ثقيلة نسبيًا). */
    @Query("SELECT storedPath FROM archived_images")
    suspend fun getAllStoredPaths(): List<String>
}
