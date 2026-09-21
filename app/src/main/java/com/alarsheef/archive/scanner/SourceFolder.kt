package com.alarsheef.archive.scanner

import android.net.Uri
import com.alarsheef.archive.data.entities.SourceApp

/**
 * مجلد مصدر واحد يفحصه الفحص.
 * نوعاه:
 *   - [FolderType.MediaStoreRelativePath] — يُستعلام عبر MediaStore بصلاحية [READ_MEDIA_IMAGES].
 *   - [FolderType.Saf] — يُفتح عبر Storage Access Framework (المستخدم يختاره مرة واحدة).
 */
data class SourceFolder(
    val source: SourceApp,
    val name: String,
    val type: FolderType,
)

sealed class FolderType {
    /** مسار نسبي داخل MediaStore (مثل "DCIM/Camera" أو "WhatsApp/Media/WhatsApp Images"). */
    data class MediaStoreRelativePath(val relativePath: String) : FolderType()
    /** معرّف شجرة SAF (tree URI) يمنح الوصول لهذا المجلد وأبنائه. */
    data class Saf(val treeUri: Uri) : FolderType()
}