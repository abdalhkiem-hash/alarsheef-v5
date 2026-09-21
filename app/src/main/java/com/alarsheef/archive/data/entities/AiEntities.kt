package com.alarsheef.archive.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * النص المستخرج من الصورة/المستند عبر OCR (على الجهاز).
 * imageId هو معرّف الصورة في archived_images.
 * يُستخدم لجعل الكلمات داخل الملفات قابلة للبحث وكقاعدة لاقتراح الأسماء الذكية.
 */
@Entity(tableName = "ocr_texts")
data class OcrText(
    @PrimaryKey val imageId: Long,
    val text: String,
    val recognizedAt: Long
)

/**
 * وسم تلقائي يُصنّف محتوى الصورة (مستند، لقطة شاشة، طبيعة...) مع درجة الثقة.
 * الصورة الواحدة قد تحمل أكثر من وسم مرتبًا بدرجة ثقته.
 */
@Entity(
    tableName = "image_labels",
    indices = [Index(value = ["imageId"]), Index(value = ["label"])]
)
data class ImageLabel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageId: Long,
    val label: String,
    val confidence: Float,
    val createdAt: Long
)

/**
 * مجموعة وجوه = شخص واحد مُجمَّع تلقائيًا من عدة صور.
 * groupKey معرّف ثابت، وname اسم يضعه المستخدم (أو null لعرض "مجموعة N").
 */
@Entity(tableName = "face_groups")
data class FaceGroup(
    @PrimaryKey val groupKey: String,
    val name: String?
)

/**
 * عضو في مجموعة وجوه: علاقة بين صورة ووجه فيها (ببصمة dHash).
 * cropPath: مسار صورة الوجه المقصوصة محليًا (تُستخدم كغلاف للمجموعة).
 */
@Entity(
    tableName = "face_group_members",
    indices = [Index(value = ["groupKey"]), Index(value = ["imageId"])]
)
data class FaceGroupMember(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageId: Long,
    val groupKey: String,
    val descriptorHash: String,
    val cropPath: String
)