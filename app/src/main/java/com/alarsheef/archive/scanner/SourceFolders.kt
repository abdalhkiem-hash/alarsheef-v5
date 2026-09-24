package com.alarsheef.archive.scanner

import android.net.Uri
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.settings.SettingsPreferences
import kotlinx.coroutines.flow.first

/**
 * تُحدّد المجلدات المصدر التي يفحصها الفحص اليومي.
 *
 * المبدأ بعد هجرة التخزين (إزالة MANAGE_EXTERNAL_STORAGE) + التبسيط (P1):
 * - **الصور** (المعرض، واتساب، واتساب أعمال، تنزيلات): تُضاف كمجلدات **MediaStore**
 *   (placeholder) — لا تحتاج أي اختيار مجلد يدوي؛ صلاحية READ_MEDIA_IMAGES تكفي،
 *   واستعلام MediaStore واحد سريع يكتشفها كلها.
 * - **الوثائق وملفات PDF** (وثائق واتساب، تنزيلات، مخصص): عبر **SAF**
 *   (يختارها المستخدم مرة واحدة من منتقي النظام ويُحفظ معرّف الشجرة).
 */
object SourceFolders {

    /** مجلدات MediaStore التابعة للمعرض (يحتاج READ_MEDIA_IMAGES فقط). */
    fun galleryMediaStoreFolders(): List<SourceFolder> = listOf(
        SourceFolder(SourceApp.GALLERY, "الكاميرا", FolderType.MediaStoreRelativePath("DCIM/Camera")),
        SourceFolder(SourceApp.GALLERY, "معرض الصور", FolderType.MediaStoreRelativePath("Pictures")),
    )

    /** المجلدات الفعّالة حسب الإعدادات. */
    suspend fun activeFolders(prefs: SettingsPreferences): List<SourceFolder> {
        val list = mutableListOf<SourceFolder>()

        // المعرض: MediaStore فقط
        if (prefs.sourceGallery.first()) list.addAll(galleryMediaStoreFolders())

        // واتساب: placeholder MediaStore للصور (لا يحتاج SAF) + SAF للوثائق فقط
        if (prefs.sourceWhatsapp.first()) {
            list.add(SourceFolder(SourceApp.WHATSAPP, "صور واتساب",
                FolderType.MediaStoreRelativePath("WhatsApp/Media/WhatsApp Images")))
            prefs.whatsappDocumentsTreeUri.first()?.let {
                list.add(SourceFolder(SourceApp.WHATSAPP, "وثائق واتساب", FolderType.Saf(Uri.parse(it))))
            }
        }

        // واتساب أعمال: placeholder MediaStore للصور (لا يحتاج SAF) + SAF للوثائق فقط
        if (prefs.sourceWhatsappBusiness.first()) {
            list.add(SourceFolder(SourceApp.WHATSAPP_BUSINESS, "صور واتساب أعمال",
                FolderType.MediaStoreRelativePath("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images")))
            prefs.whatsappBusinessDocumentsTreeUri.first()?.let {
                list.add(SourceFolder(SourceApp.WHATSAPP_BUSINESS, "وثائق واتساب أعمال", FolderType.Saf(Uri.parse(it))))
            }
        }

        // التنزيلات: placeholder MediaStore للصور + SAF لملفات غير الصور
        if (prefs.sourceDownloads.first()) {
            list.add(SourceFolder(SourceApp.DOWNLOADS, "التنزيلات",
                FolderType.MediaStoreRelativePath("Download")))
            prefs.downloadsTreeUri.first()?.let {
                list.add(SourceFolder(SourceApp.DOWNLOADS, "التنزيلات (ملفات)", FolderType.Saf(Uri.parse(it))))
            }
            prefs.importFolderUri.first()?.let {
                list.add(SourceFolder(SourceApp.DOWNLOADS, "مجلد الاستيراد المخصص", FolderType.Saf(Uri.parse(it))))
            }
        }

        return list
    }
}
