package com.alarsheef.archive.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

enum class DrawerPanel { SETTINGS, EXPORT_IMPORT, BACKUP, ABOUT, AI }

@Composable
fun ArsheefDrawerContent(onSelect: (DrawerPanel) -> Unit) {
    ModalDrawerSheet {
        Text(
            "الأرشيف",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(20.dp)
        )
        HorizontalDivider()
        DrawerRow(Icons.Filled.Tune, "الإعدادات") { onSelect(DrawerPanel.SETTINGS) }
        DrawerRow(Icons.Filled.SwapVert, "تصدير / استيراد الأرشيف") { onSelect(DrawerPanel.EXPORT_IMPORT) }
        DrawerRow(Icons.Filled.Archive, "النسخة الاحتياطية") { onSelect(DrawerPanel.BACKUP) }
        DrawerRow(Icons.Filled.Face, "الذكاء الاصطناعي") { onSelect(DrawerPanel.AI) }
        DrawerRow(Icons.Filled.Info, "حول التطبيق") { onSelect(DrawerPanel.ABOUT) }
    }
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Text(label, modifier = Modifier.padding(start = 16.dp))
    }
}
