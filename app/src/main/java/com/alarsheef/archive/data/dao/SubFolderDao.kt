package com.alarsheef.archive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alarsheef.archive.data.entities.SubFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface SubFolderDao {

    /** @return المعرّف الجديد، أو ‎-1 لو الاسم موجود مسبقًا في الشهر نفسه. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: SubFolder): Long

    @Query("SELECT * FROM sub_folders WHERE year = :year AND month = :month ORDER BY name ASC")
    fun observeByMonth(year: Int, month: Int): Flow<List<SubFolder>>

    @Query("DELETE FROM sub_folders WHERE id = :id")
    suspend fun delete(id: Long)
}
