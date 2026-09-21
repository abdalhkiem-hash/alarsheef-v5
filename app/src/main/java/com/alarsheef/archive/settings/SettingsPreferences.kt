package com.alarsheef.archive.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsPreferences(private val context: Context) {

    private object Keys {
        val AUTO_IMPORT = booleanPreferencesKey("auto_import_enabled")
        val SOURCE_WHATSAPP = booleanPreferencesKey("source_whatsapp")
        val SOURCE_GALLERY = booleanPreferencesKey("source_gallery")
        val SOURCE_DOWNLOADS = booleanPreferencesKey("source_downloads")
        val IMPORT_FOLDER_URI = stringPreferencesKey("import_folder_uri")
        val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val BACKUP_FREQUENCY = stringPreferencesKey("backup_frequency")
        val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val EXCLUDE_PERSONAL_PHOTOS = booleanPreferencesKey("exclude_personal_photos")
        val LAST_SCAN_AT = longPreferencesKey("last_scan_at")
        val AI_OCR_ENABLED = booleanPreferencesKey("ai_ocr_enabled")
        val AI_LABELS_ENABLED = booleanPreferencesKey("ai_labels_enabled")
        val AI_FACES_ENABLED = booleanPreferencesKey("ai_faces_enabled")
        /** معرّف شجرة SAF لمجلد صور واتساب (يختاره المستخدم). */
        val WHATSAPP_IMAGES_TREE_URI = stringPreferencesKey("whatsapp_images_tree_uri")
        /** معرّف شجرة SAF لمجلد وثائق واتساب. */
        val WHATSAPP_DOCUMENTS_TREE_URI = stringPreferencesKey("whatsapp_documents_tree_uri")
        /** معرّف شجرة SAF لمجلد التنزيلات. */
        val DOWNLOADS_TREE_URI = stringPreferencesKey("downloads_tree_uri")
    }

    val autoImport: Flow<Boolean> = context.appDataStore.data.map { it[Keys.AUTO_IMPORT] ?: true }
    val sourceWhatsapp: Flow<Boolean> = context.appDataStore.data.map { it[Keys.SOURCE_WHATSAPP] ?: true }
    val sourceGallery: Flow<Boolean> = context.appDataStore.data.map { it[Keys.SOURCE_GALLERY] ?: true }
    val sourceDownloads: Flow<Boolean> = context.appDataStore.data.map { it[Keys.SOURCE_DOWNLOADS] ?: true }
    val importFolderUri: Flow<String?> = context.appDataStore.data.map { it[Keys.IMPORT_FOLDER_URI] }
    val backupEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.BACKUP_ENABLED] ?: false }
    val backupFrequency: Flow<String> = context.appDataStore.data.map { it[Keys.BACKUP_FREQUENCY] ?: "daily" }
    val backupFolderUri: Flow<String?> = context.appDataStore.data.map { it[Keys.BACKUP_FOLDER_URI] }
    val lastBackupAt: Flow<Long> = context.appDataStore.data.map { it[Keys.LAST_BACKUP_AT] ?: 0L }
    val excludePersonalPhotos: Flow<Boolean> = context.appDataStore.data.map { it[Keys.EXCLUDE_PERSONAL_PHOTOS] ?: true }
    val aiOcrEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.AI_OCR_ENABLED] ?: true }
    val aiLabelsEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.AI_LABELS_ENABLED] ?: true }
    val aiFacesEnabled: Flow<Boolean> = context.appDataStore.data.map { it[Keys.AI_FACES_ENABLED] ?: true }
    /** معرّف شجرة SAF لصور واتساب (null إذا لم يُختار بعد). */
    val whatsappImagesTreeUri: Flow<String?> = context.appDataStore.data.map { it[Keys.WHATSAPP_IMAGES_TREE_URI] }
    /** معرّف شجرة SAF لوثائق واتساب. */
    val whatsappDocumentsTreeUri: Flow<String?> = context.appDataStore.data.map { it[Keys.WHATSAPP_DOCUMENTS_TREE_URI] }
    /** معرّف شجرة SAF لمجلد التنزيلات. */
    val downloadsTreeUri: Flow<String?> = context.appDataStore.data.map { it[Keys.DOWNLOADS_TREE_URI] }

    suspend fun setAutoImport(v: Boolean) { context.appDataStore.edit { it[Keys.AUTO_IMPORT] = v } }
    suspend fun setSourceWhatsapp(v: Boolean) { context.appDataStore.edit { it[Keys.SOURCE_WHATSAPP] = v } }
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
    suspend fun setLastScanAt(v: Long) { context.appDataStore.edit { it[Keys.LAST_SCAN_AT] = v } }
    suspend fun setAiOcrEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.AI_OCR_ENABLED] = v } }
    suspend fun setAiLabelsEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.AI_LABELS_ENABLED] = v } }
    suspend fun setAiFacesEnabled(v: Boolean) { context.appDataStore.edit { it[Keys.AI_FACES_ENABLED] = v } }
    suspend fun setWhatsappImagesTreeUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.WHATSAPP_IMAGES_TREE_URI) else it[Keys.WHATSAPP_IMAGES_TREE_URI] = v }
    }
    suspend fun setWhatsappDocumentsTreeUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.WHATSAPP_DOCUMENTS_TREE_URI) else it[Keys.WHATSAPP_DOCUMENTS_TREE_URI] = v }
    }
    suspend fun setDownloadsTreeUri(v: String?) {
        context.appDataStore.edit { if (v == null) it.remove(Keys.DOWNLOADS_TREE_URI) else it[Keys.DOWNLOADS_TREE_URI] = v }
    }
}