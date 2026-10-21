package com.alarsheef.archive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alarsheef.archive.data.entities.DocumentType
import com.alarsheef.archive.data.entities.ProcessedSource
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentTypeDao {

    @Query("SELECT * FROM document_types ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<DocumentType>>

    @Query("SELECT * FROM document_types ORDER BY createdAt ASC")
    suspend fun all(): List<DocumentType>

    @Query("SELECT * FROM document_types WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): DocumentType?

    @Insert
    suspend fun insert(type: DocumentType): Long

    @Query("UPDATE document_types SET keywords = :keywords WHERE id = :id")
    suspend fun updateKeywords(id: Long, keywords: String)
}

@Dao
interface ProcessedSourceDao {

    @Query("SELECT path FROM processed_sources")
    suspend fun allPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ProcessedSource)
}
