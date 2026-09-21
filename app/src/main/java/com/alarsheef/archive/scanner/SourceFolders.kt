package com.alarsheef.archive.scanner

import android.net.Uri
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.settings.SettingsPreferences
import kotlinx.coroutines.flow.first

/**
 * تُحدّد المجلدات المصدر التي يفحصها الفحص اليومي.
 *
 * المبدأ بعد هجرة التخزين (إزالة MANAGE_EXTERNAL_STORAGE):
 * - **معرض الصور** (DCIM، Pictures): يُستعلم عبر **MediaStore** بصلاحية READ_MEDIA_IMAGES
 *   — لا حاجة لصلاحية الوصول لكل الملفات.
 * - **واتساب** (صور + وثائق) و**التنزيلات** وأي مجلد مخصص: عبر **SAF**
 *   (يختارها المستخدم مرة واحدة من منتقي النظام ويُحفظ معرّف الشجرة).
 */
object SourceFolders {

    /** مجلدات MediaStore التابعة للمعرض (يحتاج READ_MEDIA_IMAGES فقط). */
    fun galleryMediaStoreFolders(): List<SourceFolder> = listOf(
        SourceFolder(SourceApp.GALLERY, "الكاميرا", FolderType.MediaStoreRelativePath("DCIM/Camera")),
        SourceFolder(SourceApp.GALLERY, "معرض الصور", FolderType.MediaStoreRelativePath("Pictures")),
    )

    /** مجلدات SAF من الإعدادات (واتساب صور، وثائق، تنزيلات، مخصص). */
    suspend fun safFolders(prefs: SettingsPreferences): List<SourceFolder> = buildList {
        prefs.whatsappImagesTreeUri.first()?.let {
            add(SourceFolder(SourceApp.WHATSAPP, "صور واتساب", FolderType.Saf(Uri.parse(it))))
        }
        prefs.whatsappDocumentsTreeUri.first()?.let {
            add(SourceFolder(SourceApp.WHATSAPP, "وثائق واتساب", FolderType.Saf(Uri.parse(it))))
        }
        prefs.downloadsTreeUri.first()?.let {
            add(SourceFolder(SourceApp.DOWNLOADS, "التنزيلات", FolderType.Saf(Uri.parse(it))))
        }
        prefs.importFolderUri.first()?.let {
            add(SourceFolder(SourceApp.DOWNLOADS, "مجلد الاستيراد المخصص", FolderType.Saf(Uri.parse(it))))
        }
    }

    /** المجلدات الفعّالة حسب الإعدادات. */
    suspend fun activeFolders(prefs: SettingsPreferences): List<SourceFolder> {
        val list = mutableListOf<SourceFolder>()
        if (prefs.sourceGallery.first()) list.addAll(galleryMediaStoreFolders())
        list.addAll(safFolders(prefs))
        return list
    }
}