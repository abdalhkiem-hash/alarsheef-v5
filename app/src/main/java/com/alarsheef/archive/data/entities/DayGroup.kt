package com.alarsheef.archive.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * يوم يُنشَئ يدويًا من قِبل المستخدم تحت شهر محدد.
 * يظهر في قائمة الأيام حتى لو لم توجد له صور بعد.
 */
@Entity(
    tableName = "day_groups",
    indices = [
        Index(value = ["year", "month", "dayNumber"], unique = true)
    ]
)
data class DayGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val year: Int,
    val month: Int,
    val dayNumber: Int
)
