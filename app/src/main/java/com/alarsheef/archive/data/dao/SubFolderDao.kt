package com.alarsheef.archive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alarsheef.archive.data.entities.SubFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface SubFolderDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: SubFolder): Long

    @Query("SELECT * FROM sub_folders WHERE year = :year AND month = :month ORDER BY name ASC")
    fun observeByMonth(year: Int, month: Int): Flow<List<SubFolder>>

    @Query("SELECT * FROM sub_folders WHERE id = :id")
    suspend fun getById(id: Long): SubFolder?

    @Query("DELETE FROM sub_folders WHERE id = :id")
    suspend fun delete(id: Long)
}
