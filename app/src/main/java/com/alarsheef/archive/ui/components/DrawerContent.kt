package com.alarsheef.archive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alarsheef.archive.ui.theme.GlassSoft
import com.alarsheef.archive.ui.theme.GlassStrong

enum class DrawerPanel { SETTINGS, EXPORT_IMPORT, BACKUP, ABOUT, AI }

@Composable
fun ArsheefDrawerContent(onSelect: (DrawerPanel) -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = Color.Transparent,
        modifier = Modifier.background(
            Brush.verticalGradient(listOf(Color(0xFF0F766E), Color(0xFF12A5A0), Color(0xFF149AA0)))
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(
                "الأرشيف",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 28.dp, bottom = 4.dp, start = 6.dp)
            )
            Box(
                modifier = Modifier
                    .padding(start = 6.dp, bottom = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.35f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("إصدار 5.0.0", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            DrawerRow(Icons.Filled.Tune, "الإعدادات") { onSelect(DrawerPanel.SETTINGS) }
            DrawerRow(Icons.Filled.SwapVert, "تصدير / استيراد الأرشيف") { onSelect(DrawerPanel.EXPORT_IMPORT) }
            DrawerRow(Icons.Filled.Archive, "النسخة الاحتياطية") { onSelect(DrawerPanel.BACKUP) }
            DrawerRow(Icons.Filled.Face, "الذكاء الاصطناعي") { onSelect(DrawerPanel.AI) }
            DrawerRow(Icons.Filled.Info, "حول التطبيق") { onSelect(DrawerPanel.ABOUT) }
        }
    }
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GlassSoft)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(GlassStrong),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}
