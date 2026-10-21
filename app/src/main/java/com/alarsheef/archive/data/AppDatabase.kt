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
import com.alarsheef.archive.data.dao.DocumentTypeDao
import com.alarsheef.archive.data.dao.ProcessedSourceDao
import com.alarsheef.archive.data.dao.SubFolderDao
import com.alarsheef.archive.data.entities.AiCloudMeta
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.entities.CustomLabel
import com.alarsheef.archive.data.entities.DayGroup
import com.alarsheef.archive.data.entities.DocumentType
import com.alarsheef.archive.data.entities.ProcessedSource
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

    @TypeConverter
    fun fromContentVerified(value: ContentVerified): Int = value.ordinal

    @TypeConverter
    fun toContentVerified(value: Int): ContentVerified =
        ContentVerified.values().getOrNull(value) ?: ContentVerified.UNVERIFIED
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
        DayGroup::class,
        AiCloudMeta::class,
        DocumentType::class,
        ProcessedSource::class,
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun archivedImageDao(): ArchivedImageDao
    abstract fun customLabelDao(): CustomLabelDao
    abstract fun aiDao(): AiDao
    abstract fun subFolderDao(): SubFolderDao
    abstract fun dayGroupDao(): DayGroupDao
    abstract fun documentTypeDao(): DocumentTypeDao
    abstract fun processedSourceDao(): ProcessedSourceDao

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

        /** ترقية 7→8: جدول نتائج التحليل السحابي (Gemini: وصف + فاتورة) — جدول جديد فقط */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ai_cloud_meta (" +
                        "imageId INTEGER NOT NULL PRIMARY KEY, " +
                        "description TEXT NOT NULL, " +
                        "vendor TEXT, " +
                        "invoiceDate TEXT, " +
                        "amount TEXT, " +
                        "currency TEXT, " +
                        "details TEXT, " +
                        "model TEXT NOT NULL, " +
                        "analyzedAt INTEGER NOT NULL)"
                )
            }
        }

        /** ترقية 8→9: إضافة عمود contentVerified للتحقق النصي من محتوى المستند — إعادة إنشاء الجدول بنفس ترتيب أعمدة الكيان */
        /**
         * 9→10: لا تغيير فعلي بالبنية. الإصدار 10 أُعلن فقط لأن الكيان فقد `defaultValue="0"`
         * على contentVerified (الجدول الفعلي ما زال `INTEGER NOT NULL DEFAULT 0` وهو متوافق).
         * كان الإصدار 10 معلنًا بلا ترحيل، فمع fallbackToDestructiveMigration كانت أي قاعدة v9
         * تُمسح بصمت عند التحديث — هذا الترحيل الفارغ يسدّ تلك الثغرة.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) { /* لا شيء */ }
        }

        /** 10→11: الاسم الأصلي للملف + قفل الاسم اليدوي. عمودان فقط، لا إعادة بناء جدول. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE archived_images ADD COLUMN originalName TEXT")
                db.execSQL("ALTER TABLE archived_images ADD COLUMN nameLocked INTEGER NOT NULL DEFAULT 0")
                // احتياط لقاعدة مرّت بنسخة 8→9 القديمة التي أنشأت هذا الفهرس الزائد (انظر MIGRATION_8_9)
                db.execSQL("DROP INDEX IF EXISTS index_archived_images_contentVerified")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("BEGIN TRANSACTION")
                try {
                    // 1. إنشاء جدول جديد بنفس ترتيب أعمدة الكيان بالضبط
                    db.execSQL(
                        "CREATE TABLE archived_images_new (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "fileName TEXT NOT NULL, " +
                            "storedPath TEXT NOT NULL, " +
                            "contentHash TEXT NOT NULL, " +
                            "sourceApp TEXT NOT NULL, " +
                            "receivedCount INTEGER NOT NULL DEFAULT 1, " +
                            "year INTEGER NOT NULL, " +
                            "month INTEGER NOT NULL, " +
                            "day INTEGER NOT NULL, " +
                            "importedAt INTEGER NOT NULL, " +
                            "capturedAt INTEGER NOT NULL, " +
                            "originalPath TEXT, " +
                            "aiAnalyzedAt INTEGER, " +
                            "contentVerified INTEGER NOT NULL DEFAULT 0, " +
                            "UNIQUE(contentHash))"
                    )
                    // 2. نسخ البيانات مع القيمة الافتراضية للعمود الجديد
                    db.execSQL(
                        "INSERT INTO archived_images_new (" +
                            "id, fileName, storedPath, contentHash, sourceApp, receivedCount, " +
                            "year, month, day, importedAt, capturedAt, originalPath, aiAnalyzedAt, contentVerified" +
                            ") SELECT " +
                            "id, fileName, storedPath, contentHash, sourceApp, receivedCount, " +
                            "year, month, day, importedAt, capturedAt, originalPath, aiAnalyzedAt, 0 " +
                            "FROM archived_images"
                    )
                    // 3. حذف الجدول القديم وإعادة تسمية الجديد
                    db.execSQL("DROP TABLE archived_images")
                    db.execSQL("ALTER TABLE archived_images_new RENAME TO archived_images")
                    // 4. إعادة إنشاء الفهارس
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_archived_images_contentHash " +
                            "ON archived_images (contentHash)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_archived_images_year_month_day " +
                            "ON archived_images (year, month, day)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_archived_images_originalPath " +
                            "ON archived_images (originalPath)"
                    )
                    // لا فهرس على contentVerified: الكيان لا يعلنه، وأي فهرس زائد يجعل Room
                    // يرفض المخطط بعد الترحيل ("Migration didn't properly handle") فينهار الفتح.
                    db.execSQL("COMMIT")
                } catch (e: Exception) {
                    db.execSQL("ROLLBACK")
                    throw e
                }
            }
        }

        /** 11→12: أنواع المستندات التي يضيفها المستخدم، وملفات الإدخال التي اكتمل سيرها. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS document_types (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "keywords TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_document_types_name " +
                        "ON document_types (name)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS processed_sources (" +
                        "path TEXT NOT NULL PRIMARY KEY)"
                )
            }
        }

        /** السلسلة الكاملة 1←12 — مصدر واحد للتطبيق ولاختبارات الترحيل. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
            MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
            MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
        )

        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arsheef.db"
                ).addMigrations(*ALL_MIGRATIONS)
                    // لا fallbackToDestructiveMigration: أي ترحيل ناقص يجب أن يفشل بصوت عالٍ
                    // (استثناء عند الفتح) لا أن يمسح فهرس الأرشيف بصمت. السلسلة 1←12 كاملة.
                    // تنبيه: تثبيت نسخة بإصدار قاعدة أقدم فوق أحدث يفشل أيضًا (لا خفض إصدار).
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
