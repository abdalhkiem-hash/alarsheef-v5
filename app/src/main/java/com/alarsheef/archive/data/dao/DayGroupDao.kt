package com.alarsheef.archive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.alarsheef.archive.data.entities.DayGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface DayGroupDao {

    /** @return المعرّف الجديد، أو ‎-1 لو اليوم موجود مسبقًا (ABORT كان يُسقط التطبيق عند التكرار). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: DayGroup): Long

    @Query("SELECT * FROM day_groups WHERE year = :year AND month = :month ORDER BY dayNumber ASC")
    fun observeByMonth(year: Int, month: Int): Flow<List<DayGroup>>

    @Query("DELETE FROM day_groups WHERE id = :id")
    suspend fun delete(id: Long)
}
