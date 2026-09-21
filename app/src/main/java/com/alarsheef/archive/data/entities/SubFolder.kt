package com.alarsheef.archive.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * مجلد فرعي تحت الشهر لتنظيم الأيام يدويًا.
 * مثال: سبتمبر 2026 → "اجتماعات" → أيام
 */
@Entity(
    tableName = "sub_folders",
    indices = [
        Index(value = ["year", "month", "name"], unique = true)
    ]
)
data class SubFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val year: Int,
    val month: Int,
    val name: String
)
