package com.alarsheef.archive.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alarsheef.archive.ai.GeminiRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** استهلاك الحصة السحابية اليومية (لعرضها في الإعدادات). */
data class GeminiQuota(val used: Long, val limit: Int)

class SettingsPreferences(private val context: Context) {

    private object Keys {
        val AUTO_IMPORT = booleanPreferencesKey("auto_import_enabled")
        val SOURCE_WHATSAPP = booleanPreferencesKey("source_whatsapp")
        val SOURCE_WHATSAPP_BUSINESS = booleanPreferencesKey("source_whatsapp_business")
        val SOURCE_GALLERY = booleanPreferencesKey("source_gallery")
        val SOURCE_DOWNLOADS = booleanPreferencesKey("source_downloads")
        val IMPORT_FOLDER_URI = stringPreferencesKey("import_folder_uri")
        val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val BACKUP_FREQUENCY = stringPreferencesKey("backup_frequency")
        val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val EXCLUDE_PERSONAL_PHOTOS = booleanPreferencesKey("exclude_personal_photos")
        /** استيراد المستندات فقط (يرفض الصور العائلية والمناظر). */
        val DOCUMENTS_ONLY = booleanPreferencesKey("documents_only")
        val LAST_SCAN_AT = longPreferencesKey("last_scan_at")
        val AI_OCR_ENABLED = booleanPreferencesKey("ai_ocr_enabled")
        val AI_LABELS_ENABLED = booleanPreferencesKey("ai_labels_enabled")
        val AI_FACES_ENABLED = booleanPreferencesKey("ai_faces_enabled")
        /** تفعيل التحليل السحابي (Gemini) — يُشغَّل فقط عند وجود مفتاح. */
        val GEMINI_CLOUD_ENABLED = booleanPreferencesKey("gemini_cloud_enabled")
        /** اختيار نموذج 2.5-pro بدل 2.5-flash (الأخير أرخص وأسرع). */
        val GEMINI_USE_PRO = booleanPreferencesKey("gemini_use_pro")
        /** تاريخ الحصة الحالية (يُعاد ضبطها كل يوم). */
        val GEMINI_QUOTA_DATE = stringPreferencesKey("gemini_quota_date")
        /** عدد الاستدعاءات السحابية المستهلكة اليوم. */
        val GEMINI_QUOTA_USED = longPreferencesKey("gemini_quota_used")
        /** معرّف شجرة SAF لمجلد صور واتساب (يختاره المستخدم). */
        val WHATSAPP_IMAGES_TREE_URI = stringPreferencesKey("whatsapp_images_tree_uri")
        /** معرّف شجرة SAF لمجلد وثائق واتساب. */
        val WHATSAPP_DOCUMENTS_TREE_URI = stringPreferencesKey("whatsapp_documents_tree_uri")
        /** معرّف شجرة SAF لمجلد صور واتساب أعمال. */
        val WHATSAPP_BUSINESS_IMAGES_TREE_URI = stringPreferencesKey("whatsapp_business_images_tree_uri")
        /** معرّف شجرة SAF لمجلد وثائق واتساب أعمال. */
        val WHATSAPP_BUSINESS_DOCUMENTS_TREE_URI = stringPreferencesKey("whatsapp_business_documents_tree_uri")
        /** معرّف شجرة SAF لمجلد التنزيلات. */
        val DOWNLOADS_TREE_URI = stringPreferencesKey("downloads_tree_uri")
        /** تفعيل التحقق النصي من محتوى المستند (افتراضي مفعّل). */
        val CONTENT_VERIFICATION_ENABLED = booleanPreferencesKey("content_verification_enabled")
        /** قصّ حدود المستند تلقائيًا عند الاستيراد من المعرض (افتراضي مفعّل). */
        val IMPORT_AUTO_CROP = booleanPreferencesKey("import_auto_crop")
        /** تحسين الصورة تلقائيًا عند الاستيراد (تباين/إزالة ظل) — افتراضي مفعّل. */
        val IMPORT_AUTO_ENHANCE = booleanPreferencesKey("import_auto_enhance")
    }

    val autoImport: Flow<Boolean> = context.appDataStore.data.map { it[Keys.AUTO_IMPORT] ?: true }
    val sourceWhatsapp: Flow<Boolean> = context.appDataStore.data.map { it[Keys.SOURCE_WHATSAPP] ?: true }
    val sourceWhatsappBusiness: Flow<Boolean> = context.appDataStore.data.map { it[Keys.SOURCE_WHATSAPP_BUSINESS] ?: true }
    val sourceGallery: Flow<Boolean> = context.appDataStore.data.map { it[Keys.SOURCE_GALLERY] ?: true }
    val sourceDownloads: Flow<Boolean> = context.appDataStore.data.map { it[Keys.SOURCE_DOWNLOADS] ?: true }
    val importFolderUri: Flow<String?> = context.appDataStore.data.map { it[Keys.IMPORT_FOLDER_URI] }
    val importAutoCrop: Flow<Boolean> = context.appDataStore.data.map { it[Keys.IMPORT_AUTO_CROP] ?: true }
    val importAutoEnhance: Flow<Boolean> = context.appDataStore.data.map { it[Keys.IMPORT_AUTO_ENHANCE] ?: true }
    val contentVerificationEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.CONTENT_VERIFICATION_ENABLED] ?: true }
    val backupEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.BACKUP_ENABLED] ?: false }
    val backupFrequency: Flow<String> = context.appDataStore.data.map { it[Keys.BACKUP_FREQUENCY] ?: "daily" }
    val backupFolderUri: Flow<String?> = context.appDataStore.data.map { it[Keys.BACKUP_FOLDER_URI] }
    val lastBackupAt: Flow<Long> = context.appDataStore.data.map { it[Keys.LAST_BACKUP_AT] ?: 0L }
    val excludePersonalPhotos: Flow<Boolean> = context.appDataStore.data.map { it[Keys.EXCLUDE_PERSONAL_PHOTOS] ?: true }
    /** افتراضيًا مفعّل: الأرشيف للمستندات (فواتير/حوالات/حركات) لا للصور العائلية. */
    val documentsOnly: Flow<Boolean> = context.appDataStore.data.map { it[Keys.DOCUMENTS_ONLY] ?: true }
    val aiOcrEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.AI_OCR_ENABLED] ?: true }
    val aiLabelsEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.AI_LABELS_ENABLED] ?: true }
    val aiFacesEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.AI_FACES_ENABLED] ?: true }
    /** افتراضيًا مفعّل: يُفعَّل فعليًا فقط عند وجود المفتاح والوصول لِـ WiFi. */
    val geminiCloudEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.GEMINI_CLOUD_ENABLED] ?: true }
    /** الافتراضي flash (الأرخص)؛ pro اختياري عبر مفتاح تبديل في الإعدادات. */
    val geminiUsePro: Flow<Boolean> = context.appDataStore.data.map { it[Keys.GEMINI_USE_PRO] ?: false }
    /** استهلاك اليوم الحالي من الاستدعاءات السحابية (يصفَّر تلقائيًا يوميًا). */
    val geminiQuota: Flow<GeminiQuota> = context.appDataStore.data.map { prefs ->
        val today = LocalDate.now().toString()
        val used = if (prefs[Keys.GEMINI_QUOTA_DATE] == today) prefs[Keys.GEMINI_QUOTA_USED] ?: 0L else 0L
        GeminiQuota(used = used, limit = GeminiRepository.DAILY_LIMIT)
    }
    /** معرّف شجرة SAF لصور واتساب (null إذا لم يُختار بعد). */
    val whatsappImagesTreeUri: Flow<String?> = context.appDataStore.data.map { it[Keys.WHATSAPP_IMAGES_TREE_URI] }
    /** معرّف شجرة SAF لوثائق واتساب. */
    val whatsappDocumentsTreeUri: Flow<String?> = context.appDataStore.data.map { it[Keys.WHATSAPP_DOCUMENTS_TREE_URI] }
    /** معرّف شجرة SAF لصور واتساب أعمال. */
    val whatsappBusinessImagesTreeUri: Flow<String?> = context.appDataStore.data.map { it[Keys.WHATSAPP_BUSINESS_IMAGES_TREE_URI] }
    /** معرّف شجرة SAF لوثائق واتساب أعمال. */
    val whatsappBusinessDocumentsTreeUri: Flow<String?> = context.appDataStore.data.map { it[Keys.WHATSAPP_BUSINESS_DOCUMENTS_TREE_URI] }
    /** معرّف شجرة SAF لمجلد التنزيلات. */
    val downloadsTreeUri: Flow<String?> = context.appDataStore.data.map { it[Keys.DOWNLOADS_TREE_URI] }

    suspend fun setAutoImport(v: Boolean) { context.appDataStore.edit { it[Keys.AUTO_IMPORT] = v } }
    suspend fun setSourceWhatsapp(v: Boolean) { context.appDataStore.edit { it[Keys.SOURCE_WHATSAPP] = v } }
    suspend fun setSourceWhatsappBusiness(v: Boolean) { context.appDataStore.edit { it[Keys.SOURCE_WHATSAPP_BUSINESS] = v } }
    suspend fun setSourceGallery(v: Boolean) { context.appDataStore.edit { it[Keys.SOURCE_GALLERY] = v } }
    suspend fun setSourceDownloads(v: Boolean) { context.appDataStore.edit { it[Keys.SOURCE_DOWNLOADS] = v } }
    suspend fun setImportFolderUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.IMPORT_FOLDER_URI) else it[Keys.IMPORT_FOLDER_URI] = v }
    }
    suspend fun setBackupEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.BACKUP_ENABLED] = v } }
    suspend fun setBackupFrequency(v: String) { context.appDataStore.edit { it[Keys.BACKUP_FREQUENCY] = v } }
    suspend fun setBackupFolderUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.BACKUP_FOLDER_URI) else it[Keys.BACKUP_FOLDER_URI] = v }
    }
    suspend fun setLastBackupAt(v: Long) { context.appDataStore.edit { it[Keys.LAST_BACKUP_AT] = v } }
    suspend fun setExcludePersonalPhotos(v: Boolean) { context.appDataStore.edit { it[Keys.EXCLUDE_PERSONAL_PHOTOS] = v } }
    suspend fun setDocumentsOnly(v: Boolean) { context.appDataStore.edit { it[Keys.DOCUMENTS_ONLY] = v } }
    suspend fun setImportAutoCrop(v: Boolean) { context.appDataStore.edit { it[Keys.IMPORT_AUTO_CROP] = v } }
    suspend fun setImportAutoEnhance(v: Boolean) { context.appDataStore.edit { it[Keys.IMPORT_AUTO_ENHANCE] = v } }
    suspend fun setLastScanAt(v: Long) { context.appDataStore.edit { it[Keys.LAST_SCAN_AT] = v } }
    suspend fun setAiOcrEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.AI_OCR_ENABLED] = v } }
    suspend fun setContentVerificationEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.CONTENT_VERIFICATION_ENABLED] = v } }
    suspend fun setAiLabelsEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.AI_LABELS_ENABLED] = v } }
    suspend fun setAiFacesEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.AI_FACES_ENABLED] = v } }
    suspend fun setGeminiCloudEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.GEMINI_CLOUD_ENABLED] = v } }
    suspend fun setGeminiUsePro(v: Boolean) { context.appDataStore.edit { it[Keys.GEMINI_USE_PRO] = v } }

    /**
     * يحجز محاولة واحدة من الحصة اليومية قبل أي استدعاء سحابي.
     * @return true إذا استُهلكت المحاولة، false إذا انتهى حد اليوم (يُحترم بلا استثناء).
     */
    suspend fun tryConsumeGeminiQuota(limit: Int): Boolean {
        val today = LocalDate.now().toString()
        var allowed = false
        context.appDataStore.edit { prefs ->
            if (prefs[Keys.GEMINI_QUOTA_DATE] != today) {
                prefs[Keys.GEMINI_QUOTA_DATE] = today
                prefs[Keys.GEMINI_QUOTA_USED] = 0L
            }
            val used = prefs[Keys.GEMINI_QUOTA_USED] ?: 0L
            if (used < limit) {
                prefs[Keys.GEMINI_QUOTA_USED] = used + 1
                allowed = true
            }
        }
        return allowed
    }
    suspend fun setWhatsappImagesTreeUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.WHATSAPP_IMAGES_TREE_URI) else it[Keys.WHATSAPP_IMAGES_TREE_URI] = v }
    }
    suspend fun setWhatsappDocumentsTreeUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.WHATSAPP_DOCUMENTS_TREE_URI) else it[Keys.WHATSAPP_DOCUMENTS_TREE_URI] = v }
    }
    suspend fun setWhatsappBusinessImagesTreeUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.WHATSAPP_BUSINESS_IMAGES_TREE_URI) else it[Keys.WHATSAPP_BUSINESS_IMAGES_TREE_URI] = v }
    }
    suspend fun setWhatsappBusinessDocumentsTreeUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.WHATSAPP_BUSINESS_DOCUMENTS_TREE_URI) else it[Keys.WHATSAPP_BUSINESS_DOCUMENTS_TREE_URI] = v }
    }
    suspend fun setDownloadsTreeUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.DOWNLOADS_TREE_URI) else it[Keys.DOWNLOADS_TREE_URI] = v }
    }
}