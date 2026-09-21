package com.alarsheef.archive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alarsheef.archive.data.entities.FaceGroup
import com.alarsheef.archive.data.entities.FaceGroupMember
import com.alarsheef.archive.data.entities.ImageLabel
import com.alarsheef.archive.data.entities.OcrText
import kotlinx.coroutines.flow.Flow

/** مجموعة وجوه مع عدد أعضائها ومساحة أول رقم وجد فيها (للعرض كغلاف) */
data class FaceGroupRow(val groupKey: String, val name: String?, val memberCount: Int, val coverCropPath: String?)

/** ممثل مجموعة (أول بصمة) — يُستخدم لمطابقة الوجوه الجديدة عبر تشغيلات متعددة */
data class FaceGroupRep(val groupKey: String, val repHash: String)

/** عضو مجموعة مع معلومة مسار الصورة الأصلية — لتعديل العارض عند النقر */
data class FaceMemberRow(
    val memberId: Long,
    val groupKey: String,
    val cropPath: String,
    val imageId: Long,
    val imagePath: String,   // مسار الصورة الأصلية في archived_images
    val year: Int,
    val month: Int,
    val day: Int
)

@Dao
interface AiDao {

    // ---------- OCR ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOcr(ocr: OcrText)

    @Query("SELECT * FROM ocr_texts WHERE imageId = :imageId")
    suspend fun getOcrByImage(imageId: Long): OcrText?

    @Query("SELECT * FROM ocr_texts WHERE imageId = :imageId")
    fun observeOcrByImage(imageId: Long): Flow<OcrText?>

    @Query("SELECT COUNT(*) FROM ocr_texts WHERE text LIKE '%' || :word || '%' ")
    suspend fun countOcrContaining(word: String): Int

    // ---------- الوسوم التلقائية ----------

    @Insert
    suspend fun insertLabels(labels: List<ImageLabel>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabel(label: ImageLabel)

    @Query("DELETE FROM image_labels WHERE imageId = :imageId")
    suspend fun deleteLabelsForImage(imageId: Long)

    @Query("SELECT * FROM image_labels WHERE imageId = :imageId ORDER BY confidence DESC")
    suspend fun getLabelsForImage(imageId: Long): List<ImageLabel>

    @Query("SELECT * FROM image_labels WHERE imageId = :imageId ORDER BY confidence DESC")
    fun observeLabelsForImage(imageId: Long): Flow<List<ImageLabel>>

    // ---------- مجموعات الوجوه ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFaceGroup(group: FaceGroup)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFaceMember(member: FaceGroupMember)

    @Query("SELECT * FROM face_groups")
    fun observeFaceGroups(): Flow<List<FaceGroup>>

    @Query(
        """SELECT g.groupKey AS groupKey, g.name AS name, COUNT(m.id) AS memberCount,
                  MIN(m.cropPath) AS coverCropPath
           FROM face_groups g LEFT JOIN face_group_members m ON g.groupKey = m.groupKey
           GROUP BY g.groupKey ORDER BY memberCount DESC"""
    )
    fun observeFaceGroupRows(): Flow<List<FaceGroupRow>>

    @Query(
        """SELECT m.id AS memberId, m.groupKey AS groupKey, m.cropPath AS cropPath,
                  i.id AS imageId, i.storedPath AS imagePath, i.year AS year, i.month AS month, i.day AS day
           FROM face_group_members m JOIN archived_images i ON m.imageId = i.id
           WHERE m.groupKey = :groupKey ORDER BY i.year DESC, i.month DESC, i.day DESC"""
    )
    fun observeFaceMembers(groupKey: String): Flow<List<FaceMemberRow>>

    @Query("UPDATE face_groups SET name = :name WHERE groupKey = :groupKey")
    suspend fun renameFaceGroup(groupKey: String, name: String?)

    @Query("DELETE FROM face_group_members WHERE groupKey = :groupKey")
    suspend fun deleteFaceGroupMembers(groupKey: String)

    @Query("DELETE FROM face_groups WHERE groupKey = :groupKey")
    suspend fun deleteFaceGroup(groupKey: String)

    // ---------- التنظيف: إعادة تحليل / حذف صور ----------

    @Query("SELECT * FROM face_group_members WHERE imageId = :imageId")
    suspend fun membersForImage(imageId: Long): List<FaceGroupMember>

    @Query("DELETE FROM face_group_members WHERE imageId = :imageId")
    suspend fun deleteMembersForImage(imageId: Long)

    @Query("SELECT cropPath FROM face_group_members WHERE imageId IN (:ids)")
    suspend fun cropPathsForImageIds(ids: List<Long>): List<String>

    @Query("DELETE FROM ocr_texts WHERE imageId IN (:ids)")
    suspend fun deleteOcrForImageIds(ids: List<Long>)

    @Query("DELETE FROM image_labels WHERE imageId IN (:ids)")
    suspend fun deleteLabelsForImageIds(ids: List<Long>)

    @Query("DELETE FROM face_group_members WHERE imageId IN (:ids)")
    suspend fun deleteMembersForImageIds(ids: List<Long>)

    @Query("SELECT COUNT(DISTINCT imageId) FROM face_group_members")
    suspend fun countImagesWithFaces(): Int

    @Query("SELECT COUNT(DISTINCT groupKey) FROM face_groups")
    suspend fun countFaceGroups(): Int

    @Query("SELECT groupKey, MIN(descriptorHash) AS repHash FROM face_group_members GROUP BY groupKey")
    suspend fun faceGroupRepresentatives(): List<FaceGroupRep>

    @Query("SELECT groupKey, name FROM face_groups WHERE name IS NOT NULL AND name != ''")
    suspend fun namedFaceGroups(): List<FaceGroup>

    @Query(
        """SELECT DISTINCT g.name FROM face_groups g
           JOIN face_group_members m ON g.groupKey = m.groupKey
           WHERE m.imageId IN (:ids) AND g.name IS NOT NULL AND g.name != ''"""
    )
    suspend fun namedGroupsForImages(ids: List<Long>): List<String>

    // ---------- استعلامات مساعدة للبحث والاقتراحات ----------

    @Query("SELECT label FROM image_labels WHERE imageId = :imageId")
    suspend fun labelsForImage(imageId: Long): List<String>

    @Query("SELECT text FROM ocr_texts WHERE imageId = :imageId")
    suspend fun textForImage(imageId: Long): String?
}