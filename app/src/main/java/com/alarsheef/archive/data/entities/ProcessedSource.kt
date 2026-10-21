package com.alarsheef.archive.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ملف في مجلد الإدخال اكتمل السير عليه (صُوِّرت صفحاته أو تعذر رسمها).
 * الصفحة الناجحة تُعرَف بمسارها في الأرشيف؛ هذا الصف يمنع إعادة تحويل الملف كل فحص.
 */
@Entity(tableName = "processed_sources")
data class ProcessedSource(
    @PrimaryKey val path: String,
)
