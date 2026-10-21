package com.alarsheef.archive.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * نوع مستند يضيفه المستخدم مرة واحدة: الاسم في أول الملف، وكلمات تظهر على الورقة.
 */
@Entity(
    tableName = "document_types",
    indices = [Index(value = ["name"], unique = true)]
)
data class DocumentType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val keywords: String,
    val createdAt: Long,
)
