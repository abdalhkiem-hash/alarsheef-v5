package com.alarsheef.archive.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.alarsheef.archive.data.entities.ContentVerified
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * اختبارات الترحيل على ملف SQLite حقيقي (دون الاعتماد على مخططات Room القديمة
 * التي لم تُصدَّر سابقًا): نبني قاعدة بـ user_version القديم يدويًا، ثم نفتحها
 * عبر Room مع سلسلة الترحيلات الكاملة حتى الإصدار الحالي. فتح القاعدة نفسه هو
 * التحقق الأهم: Room يطابق المخطط الناتج مع الكيانات ويرمي استثناء عند أي فرق.
 *
 * كانت تُفتح بترحيلات 1→4 فقط، فمنذ الإصدار 5 تفشل كلها بـ"ترحيل ناقص" ولا تختبر شيئًا.
 *
 * متطلب التنفيذ: جهاز/محاكّي Android.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    /** ينشئ قواعد الإصدارات المصدَّرة (app/schemas) — يُستخدم لاختبار 8→الحالي. */
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    // ---------------------------------------------------------------------
    // بناء المخططات القديمة
    // ---------------------------------------------------------------------

    private fun archivedImagesDdl(version: Int): String {
        val columns = StringBuilder(
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "fileName TEXT NOT NULL, " +
                "storedPath TEXT NOT NULL, " +
                "contentHash TEXT NOT NULL, " +
                "sourceApp TEXT NOT NULL, " +
                "receivedCount INTEGER NOT NULL, " +
                "year INTEGER NOT NULL, " +
                "month INTEGER NOT NULL, " +
                "day INTEGER NOT NULL, " +
                "importedAt INTEGER NOT NULL, " +
                "capturedAt INTEGER NOT NULL"
        )
        if (version >= 3) columns.append(", originalPath TEXT")
        return "CREATE TABLE archived_images ($columns)"
    }

    private val customLabelsDdl =
        "CREATE TABLE custom_labels (scopeKey TEXT NOT NULL, label TEXT NOT NULL, PRIMARY KEY(scopeKey))"

    /** يُنشئ ملف قاعدة ببنية الإصدار المعطى و user_version الموافق ثم يغلقه. */
    private fun createDatabaseAtVersion(version: Int, config: SQLiteDatabase.() -> Unit) {
        context.deleteDatabase(dbName)
        val raw = checkNotNull(
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        )
        try {
            raw.execSQL(archivedImagesDdl(version))
            raw.execSQL(customLabelsDdl)
            if (version >= 2) raw.execSQL(
                "CREATE UNIQUE INDEX index_archived_images_contentHash ON archived_images (contentHash)"
            )
            if (version >= 2) raw.execSQL(
                "CREATE INDEX index_archived_images_year_month_day ON archived_images (year, month, day)"
            )
            if (version >= 3) raw.execSQL(
                "CREATE INDEX index_archived_images_originalPath ON archived_images (originalPath)"
            )
            raw.config()
            raw.version = version
        } finally {
            raw.close()
        }
    }

    private fun insertV1V2(raw: SQLiteDatabase, fileName: String, hash: String) {
        raw.execSQL(
            "INSERT INTO archived_images " +
                "(fileName, storedPath, contentHash, sourceApp, receivedCount, year, month, day, importedAt, capturedAt) " +
                "VALUES ('$fileName', '/x/$fileName', '$hash', 'WHATSAPP', 1, 2026, 1, 1, 0, 0)"
        )
    }

    private fun insertV3(raw: SQLiteDatabase, fileName: String, hash: String, originalPath: String?) {
        raw.execSQL(
            "INSERT INTO archived_images " +
                "(fileName, storedPath, contentHash, sourceApp, receivedCount, year, month, day, importedAt, capturedAt, originalPath) " +
                "VALUES ('$fileName', '/x/$fileName', '$hash', 'WHATSAPP', 1, 2026, 1, 1, 0, 0, " +
                (if (originalPath == null) "NULL" else "'$originalPath'") + ")"
        )
    }

    private fun openWithMigrations(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    // ---------------------------------------------------------------------
    // أدوات تحقق
    // ---------------------------------------------------------------------

    private fun rowsCount(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info($table)", emptyArray()).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun objectExists(db: SupportSQLiteDatabase, type: String, name: String): Boolean =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?", arrayOf(type, name))
            .use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }

    private fun fileNameForHash(db: SupportSQLiteDatabase, hash: String): String? =
        db.query("SELECT fileName FROM archived_images WHERE contentHash = '$hash'", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    // ---------------------------------------------------------------------
    // الاختبارات
    // ---------------------------------------------------------------------

    /** 1→2: إزالة التكرارات (إبقاء أصغر id) وإنشاء الفهارس */
    @Test
    fun migrate1To2_dedupsDuplicatesAndCreatesIndexes() {
        createDatabaseAtVersion(1) {
            insertV1V2(this, "a1.jpg", "dup")
            insertV1V2(this, "a2.jpg", "dup")
            insertV1V2(this, "b1.jpg", "uniq")
        }

        val db = openWithMigrations()
        try {
            val schema = db.openHelper.readableDatabase
            assertEquals(2, rowsCount(schema, "archived_images"))
            assertEquals("a1.jpg", fileNameForHash(schema, "dup"))
            assertTrue(objectExists(schema, "index", "index_archived_images_contentHash"))
            assertTrue(objectExists(schema, "index", "index_archived_images_year_month_day"))

            val rows = runBlocking { db.archivedImageDao().getAllOnce() }
            assertEquals(setOf("a1.jpg", "b1.jpg"), rows.map { it.fileName }.toSet())
        } finally {
            db.close()
        }
    }

    /** 2→3: إضافة عمود originalPath مع الحفاظ على البيانات */
    @Test
    fun migrate2To3_addsOriginalPathAndKeepsData() {
        createDatabaseAtVersion(2) {
            insertV1V2(this, "c1.jpg", "hashc")
        }

        val db = openWithMigrations()
        try {
            val schema = db.openHelper.readableDatabase
            assertTrue(tableColumns(schema, "archived_images").contains("originalPath"))
            assertTrue(objectExists(schema, "index", "index_archived_images_originalPath"))
            assertEquals(1, rowsCount(schema, "archived_images"))
            assertEquals("hashc", runBlocking { db.archivedImageDao().getAllOnce().first().contentHash })
        } finally {
            db.close()
        }
    }

    /** 3→4: إضافة aiAnalyzedAt وجداول الذكاء الأربعة دون أي فقدان للبيانات */
    @Test
    fun migrate3To4_addsAiTablesAndPreservesData() {
        createDatabaseAtVersion(3) {
            insertV3(this, "d1.jpg", "hashd1", "/original/d1.jpg")
            insertV3(this, "d2.jpg", "hashd2", null)
            execSQL("INSERT INTO custom_labels (scopeKey, label) VALUES ('y-2026', 'رحلة العائلة')")
        }

        val db = openWithMigrations()
        try {
            val schema = db.openHelper.readableDatabase
            // عمود التحليل الذكي
            assertTrue(tableColumns(schema, "archived_images").contains("aiAnalyzedAt"))
            // الجداول الجديدة
            for (table in listOf("ocr_texts", "image_labels", "face_groups", "face_group_members")) {
                assertTrue("الجدول $table لم يُنشأ", objectExists(schema, "table", table))
            }
            // فهارسها
            assertTrue(objectExists(schema, "index", "index_image_labels_imageId"))
            assertTrue(objectExists(schema, "index", "index_face_group_members_groupKey"))
            // الحفاظ على البيانات
            assertEquals(2, rowsCount(schema, "archived_images"))

            val rows = runBlocking { db.archivedImageDao().getAllOnce() }
            assertEquals("/original/d1.jpg", rows.first { it.contentHash == "hashd1" }.originalPath)
            assertNull(rows.first { it.contentHash == "hashd2" }.originalPath)
            val labels = runBlocking { db.customLabelDao().observeAll().first() }
            assertEquals("رحلة العائلة", labels.first { it.scopeKey == "y-2026" }.label)
            assertEquals(1, rowsCount(schema, "custom_labels"))
            // البيانات غير المحلَّلة بعد تبقى null
            assertTrue(rows.all { it.aiAnalyzedAt == null })
        } finally {
            db.close()
        }
    }

    /**
     * 8→الحالي على قاعدة من المخطط المصدَّر: إعادة بناء الجدول في 8→9 ثم عمودا 10→11.
     * يكشف أي فهرس/عمود زائد يتركه ترحيل (Room يرفض الفتح)، ويتحقق من بقاء البيانات.
     */
    @Test
    fun migrate8ToCurrent_keepsDataAndPassesRoomValidation() {
        helper.createDatabase(dbName, 8).apply {
            execSQL(
                "INSERT INTO archived_images " +
                    "(fileName, storedPath, contentHash, sourceApp, receivedCount, year, month, day, " +
                    "importedAt, capturedAt, originalPath, aiAnalyzedAt) " +
                    "VALUES ('v8.jpg', '/x/v8.jpg', 'hash8', 'GALLERY', 2, 2026, 9, 30, 5, 6, 'DCIM/Camera/v8.jpg', 77)"
            )
            execSQL("INSERT INTO custom_labels (scopeKey, label) VALUES ('m-2026-9', 'سبتمبر العمل')")
            close()
        }

        val db = openWithMigrations()
        try {
            // أول وصول يشغّل الترحيلات والتحقق من المخطط — يرمي لو لم تطابق الكيانات
            val schema = db.openHelper.writableDatabase
            assertEquals(1, rowsCount(schema, "archived_images"))
            assertTrue(tableColumns(schema, "archived_images").containsAll(listOf("contentVerified", "originalName", "nameLocked")))
            assertFalse(
                "فهرس لا يعلنه الكيان يُسقط التحقق",
                objectExists(schema, "index", "index_archived_images_contentVerified")
            )
            assertTrue(objectExists(schema, "index", "index_archived_images_contentHash"))
            assertTrue(objectExists(schema, "index", "index_archived_images_originalPath"))

            val row = runBlocking { db.archivedImageDao().getAllOnce() }.single()
            assertEquals("v8.jpg", row.fileName)
            assertEquals("hash8", row.contentHash)
            assertEquals(2, row.receivedCount)
            assertEquals("DCIM/Camera/v8.jpg", row.originalPath)
            assertEquals(77L, row.aiAnalyzedAt)
            assertEquals(ContentVerified.UNVERIFIED, row.contentVerified)
            assertNull(row.originalName)
            assertFalse(row.nameLocked)

            val labels = runBlocking { db.customLabelDao().observeAll().first() }
            assertEquals("سبتمبر العمل", labels.single().label)
        } finally {
            db.close()
        }
    }

    /** قاعدة قديمة جدًا (v1) تصل للإصدار الحالي وتقبل الإدراج والاستعلام بالأعمدة الجديدة. */
    @Test
    fun migrate1ToCurrent_databaseIsFullyUsable() {
        createDatabaseAtVersion(1) {
            insertV1V2(this, "old.jpg", "oldhash")
        }

        val db = openWithMigrations()
        try {
            val dao = db.archivedImageDao()
            val rows = runBlocking { dao.getAllOnce() }
            assertEquals("old.jpg", rows.single().fileName)
            runBlocking { dao.claimOriginalPath(rows.single().id, "Download/old.jpg") }
            assertEquals(listOf("Download/old.jpg"), runBlocking { dao.findAllOriginalPaths() })
            assertTrue(runBlocking { db.subFolderDao().observeByMonth(2026, 1).first() }.isEmpty())
            assertTrue(runBlocking { db.dayGroupDao().observeByMonth(2026, 1).first() }.isEmpty())
        } finally {
            db.close()
        }
    }
}