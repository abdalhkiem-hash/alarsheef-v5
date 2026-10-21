package com.alarsheef.archive.scanner

import android.net.Uri
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.settings.SettingsPreferences
import kotlinx.coroutines.flow.first

/**
 * الفحص التلقائي يأخذ ما وصل اليوم إلى واتساب أعمال، ومجلد الإدخال إن حُدّد.
 * واتساب العادي والمعرض والتنزيلات لا تُسحب.
 */
object SourceFolders {

    suspend fun activeFolders(prefs: SettingsPreferences): List<SourceFolder> {
        val uri = prefs.importFolderUri.first()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(
            SourceFolder(SourceApp.INBOX, "مجلد الإدخال", FolderType.Saf(Uri.parse(uri)))
        )
    }
}
