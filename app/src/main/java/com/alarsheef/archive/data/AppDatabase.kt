package com.alarsheef.archive.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alarsheef.archive.data.dao.AiDao
import com.alarsheef.archive.data.dao.ArchivedImageDao
import com.alarsheef.archive.data.dao.CustomLabelDao
import com.alarsheef.archive.data.dao.DayGroupDao
import com.alarsheef.archive.data.dao.SubFolderDao
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.CustomLabel
import com.alarsheef.archive.data.entities.DayGroup
import com.alarsheef.archive.data.entities.FaceGroup
import com.alarsheef.archive.data.entities.FaceGroupMember
import com.alarsheef.archive.data.entities.ImageLabel
import com.alarsheef.archive.data.entities.OcrText
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.entities.SubFolder

class Converters {
    @TypeConverter
    fun fromSourceApp(value: SourceApp): String = value.name

    /** قيمة غير معروفة (مثلاً بعد حذف عنصر من الـ enum) ترجع MANUAL_IMPORT بدل ما يطيح التطبيق */
    @TypeConverter
    fun toSourceApp(value: String): SourceApp =
        runCatching { SourceApp.valueOf(value) }.getOrDefault(SourceApp.MANUAL_IMPORT)
}

@Database(
    entities = [
        ArchivedImage::class,
        CustomLabel::class,
        OcrText::class,
        ImageLabel::class,
        FaceGroup::class,
        FaceGroupMember::class,
        SubFolder::class,
        DayGroup::class
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun archivedImageDao(): ArchivedImageDao
    abstract fun customLabelDao(): CustomLabelDao
    abstract fun aiDao(): AiDao
    abstract fun subFolderDao(): SubFolderDao
    abstract fun dayGroupDao(): DayGroupDao

    companion object {
        /** ترقية بدون فقدان بيانات: تنظيف التكرارات ثم إضافة الفهارس */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM archived_images WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM archived_images GROUP BY contentHash)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_archived_images_contentHash " +
                        "ON archived_images (contentHash)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_archived_images_year_month_day " +
                        "ON archived_images (year, month, day)"
                )
            }
        }

        /** ترقية 2→3: إضافة عمود originalPath لتتبع مصدر الملف ومنع تضخّم عدّاد الاستلام */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE archived_images ADD COLUMN originalPath TEXT"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_archived_images_originalPath " +
                        "ON archived_images (originalPath)"
                )
            }
        }

        /**
         * ترقية 3→4: إضافة التحليل الذكي — عمود حالة التحليل لكل صورة
         * + جداول النصوص المستخرجة (OCR) والوسوم التلقائية ومجموعات الوجوه.
         * كلها جداول جديدة: لا تعبث بالبيانات الموجودة إطلاقًا.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE archived_images ADD COLUMN aiAnalyzedAt INTEGER")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ocr_texts (" +
                        "imageId INTEGER NOT NULL PRIMARY KEY, " +
                        "text TEXT NOT NULL, " +
                        "recognizedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS image_labels (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "imageId INTEGER NOT NULL, " +
                        "label TEXT NOT NULL, " +
                        "confidence REAL NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_image_labels_imageId ON image_labels (imageId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_image_labels_label ON image_labels (label)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS face_groups (" +
                        "groupKey TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS face_group_members (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "imageId INTEGER NOT NULL, " +
                        "groupKey TEXT NOT NULL, " +
                        "descriptorHash TEXT NOT NULL, " +
                        "cropPath TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_face_group_members_groupKey " +
                        "ON face_group_members (groupKey)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_face_group_members_imageId " +
                        "ON face_group_members (imageId)"
                )
            }
        }

        /** ترقية 4→5: إضافة جدول sub_folders للمجلدات الفرعية تحت الأشهر */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sub_folders (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "year INTEGER NOT NULL, " +
                        "month INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "UNIQUE(year, month, name))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sub_folders_year_month ON sub_folders (year, month)"
                )
            }
        }

        /** ترقية 5→6: إضافة جدول day_groups للأيام التي يُنشئها المستخدم يدويًا */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS day_groups (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "year INTEGER NOT NULL, " +
                        "month INTEGER NOT NULL, " +
                        "dayNumber INTEGER NOT NULL, " +
                        "UNIQUE(year, month, dayNumber))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_day_groups_year_month ON day_groups (year, month)"
                )
            }
        }

        /** ترقية 6→7: إصلاح فهارس sub_folders و day_groups */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_sub_folders_year_month")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sub_folders_year_month_name " +
                        "ON sub_folders (year, month, name)"
                )
                db.execSQL("DROP INDEX IF EXISTS index_day_groups_year_month")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_day_groups_year_month_dayNumber " +
                        "ON day_groups (year, month, dayNumber)"
                )
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arsheef.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
