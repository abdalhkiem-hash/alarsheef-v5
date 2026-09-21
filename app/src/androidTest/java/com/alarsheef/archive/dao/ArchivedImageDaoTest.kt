package com.alarsheef.archive.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarsheef.archive.data.AppDatabase
import com.alarsheef.archive.data.dao.ArchivedImageDao
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.SourceApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchivedImageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ArchivedImageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.archivedImageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun image(hash: String, year: Int = 2026, month: Int = 1, day: Int = 1, originalPath: String? = null) =
        ArchivedImage(
            fileName = "IMG_$hash.jpg",
            storedPath = "/data/nonexistent/$hash.jpg",
            contentHash = hash,
            sourceApp = SourceApp.WHATSAPP,
            receivedCount = 1,
            year = year, month = month, day = day,
            importedAt = 0L,
            capturedAt = 0L,
            originalPath = originalPath
        )

    @Test
    fun insert_findByContentHash_roundTrip() = runBlocking {
        val id = dao.insert(image("abc123"))
        val found = dao.findByContentHash("abc123")
        assertNotNull(found)
        assertEquals(id, found!!.id)
        assertEquals("IMG_abc123.jpg", found.fileName)
        assertEquals(SourceApp.WHATSAPP, found.sourceApp)
    }

    @Test
    fun duplicateContentHash_isRejectedByDatabase() = runBlocking {
        dao.insert(image("dup-hash"))
        val second = try {
            dao.insert(image("dup-hash"))
            null
        } catch (e: Exception) {
            e
        }
        // الفهرس الفريد contentHash يجب أن يمنع التكرار على مستوى قاعدة البيانات
        assertTrue(second != null)
        assertEquals(1, dao.getAllOnce().size)
    }

    @Test
    fun findByOriginalPath_returnsOnlyMatchingRow() = runBlocking {
        dao.insert(image("h1", originalPath = "/whatsapp/media/f1.jpg"))
        dao.insert(image("h2", originalPath = "/whatsapp/media/f2.jpg"))
        val found = dao.findByOriginalPath("/whatsapp/media/f2.jpg")
        assertNotNull(found)
        assertEquals("IMG_h2.jpg", found!!.fileName)
        assertNull(dao.findByOriginalPath("/unknown"))
    }

    @Test
    fun incrementReceivedCount_accumulates() = runBlocking {
        val id = dao.insert(image("cnt"))
        dao.incrementReceivedCount(id)
        dao.incrementReceivedCount(id)
        val row = dao.getAllOnce().first()
        assertEquals(3, row.receivedCount)
    }

    @Test
    fun observeYears_groupsByYearDesc() = runBlocking {
        dao.insert(image("a", year = 2026, month = 5, day = 3))
        dao.insert(image("b", year = 2025, month = 12, day = 1))
        dao.insert(image("c", year = 2026, month = 8, day = 2))
        val groups = dao.observeYears().first()
        assertEquals(2, groups.size)
        assertEquals(2026, groups[0].year)
        assertEquals(2, groups[0].fileCount)
        assertEquals(8, groups[0].latestMonth)
        assertEquals(2025, groups[1].year)
    }

    @Test
    fun observeMonthsAndDays_groupCorrectly() = runBlocking {
        dao.insert(image("m1", year = 2026, month = 3, day = 5))
        dao.insert(image("m2", year = 2026, month = 3, day = 9))
        dao.insert(image("m3", year = 2026, month = 7, day = 1))
        val months = dao.observeMonths(2026).first()
        assertEquals(2, months.size)
        assertEquals(7, months[0].month)
        val days = dao.observeDays(2026, 3).first()
        assertEquals(2, days.size)
        assertEquals(9, days[0].day)
        assertEquals(1, days[0].fileCount)
    }

    @Test
    fun searchAll_matchesFileNameSubstring() = runBlocking {
        dao.insert(image("one"))
        dao.insert(image("two"))
        val results = dao.searchAll("IMG_two").first()
        assertEquals(1, results.size)
        assertEquals("IMG_two.jpg", results[0].fileName)
    }

    @Test
    fun deleteDay_removesOnlyThatDay() = runBlocking {
        dao.insert(image("d1", year = 2026, month = 2, day = 1))
        dao.insert(image("d2", year = 2026, month = 2, day = 2))
        dao.deleteDay(2026, 2, 1)
        val remaining = dao.getAllOnce()
        assertEquals(1, remaining.size)
        assertEquals("IMG_d2.jpg", remaining[0].fileName)
    }

    @Test
    fun deleteMonth_removesAllDaysOfMonth() = runBlocking {
        dao.insert(image("e1", year = 2026, month = 4, day = 1))
        dao.insert(image("e2", year = 2026, month = 4, day = 15))
        dao.insert(image("e3", year = 2025, month = 4, day = 15))
        dao.deleteMonth(2026, 4)
        assertEquals(1, dao.getAllOnce().size)
    }

    @Test
    fun deleteYear_removesEverythingOfThatYear() = runBlocking {
        dao.insert(image("f1", year = 2026, month = 1, day = 1))
        dao.insert(image("f2", year = 2025, month = 1, day = 1))
        dao.deleteYear(2026)
        assertEquals(1, dao.getAllOnce().size)
    }

    @Test
    fun moveImage_updatesScopeAndPath() = runBlocking {
        val id = dao.insert(image("g1", year = 2026, month = 1, day = 1))
        dao.moveImage(id, 2027, 6, 15, "/moved/path.jpg")
        val row = dao.getByDayOnce(2027, 6, 15).first()
        assertEquals("/moved/path.jpg", row.storedPath)
        assertTrue(dao.getByDayOnce(2026, 1, 1).isEmpty())
    }
}