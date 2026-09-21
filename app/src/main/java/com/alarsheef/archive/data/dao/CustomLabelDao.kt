package com.alarsheef.archive.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.alarsheef.archive.data.entities.CustomLabel
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomLabelDao {

    @Upsert
    suspend fun setLabel(label: CustomLabel)

    @Query("DELETE FROM custom_labels WHERE scopeKey = :scopeKey")
    suspend fun clearLabel(scopeKey: String)

    @Query("SELECT * FROM custom_labels")
    fun observeAll(): Flow<List<CustomLabel>>

    @Query("DELETE FROM custom_labels WHERE scopeKey LIKE :prefix || '%'")
    suspend fun clearAllUnderPrefix(prefix: String)
}
