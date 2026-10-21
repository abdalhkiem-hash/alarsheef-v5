package com.alarsheef.archive.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alarsheef.archive.ui.theme.Accent
import com.alarsheef.archive.ui.theme.Glass
import com.alarsheef.archive.ui.theme.GlassBorder
import com.alarsheef.archive.ui.theme.GlassStrong
import com.alarsheef.archive.ui.theme.OnAccent
import com.alarsheef.archive.ui.theme.Radius
import com.alarsheef.archive.ui.theme.TextMuted

/**
 * مكوّنات الزجاج المشتركة لكل الشاشات (تحلّ محل نسخ "رقاقة الشريط العلوي" و"الصف الزجاجي"
 * التي كانت مكرّرة في 6 ملفات). اتجاه الواجهة RTL: أول عنصر في Row يظهر يمينًا.
 */

/** سطح زجاجي موحّد: قص + خلفية + خيط حدّي (+ نقر اختياري). بلا Card فلا ظل ولا بلاطة داخلية. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.Row,
    background: Color = Glass,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, GlassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content
    )
}

/** زر أيقونة زجاجي مربّع بزوايا Control. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(Radius.Control))
            .background(GlassStrong)
            .border(1.dp, GlassBorder, RoundedCornerShape(Radius.Control))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** بلاطة أيقونة/نص (مثل أيقونة الشهر أو رقم اليوم). */
@Composable
fun IconTile(
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    circle: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(Radius.Control)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(GlassStrong)
            .border(1.dp, GlassBorder, shape),
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

/** شارة عدد صغيرة بلون مميّز (مثل عدد ملفات اليوم). */
@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.Small))
            .background(Accent.copy(alpha = 0.18f))
            .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(Radius.Small))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** الشريط العلوي الزجاجي: رجوع (يمين) + عنوان وسطي (+ عنوان فرعي) + إجراءات (يسار). */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        radius = Radius.Hero
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (leading != null) {
                leading()
            } else if (onBack != null) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", onBack)
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

/** الزر الرئيسي: لون مميّز، زوايا 18، ارتفاع مريح، أيقونة اختيارية. */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(Radius.Row),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Box(modifier = Modifier.size(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** الزر الثانوي: زجاجي بحدّ شعري ونص أبيض. */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(Radius.Row),
        border = BorderStroke(1.dp, GlassBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Glass, contentColor = Color.White)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** حالة فارغة موحّدة: لوح زجاجي بأيقونة وعنوان وشرح اختياري. */
@Composable
fun GlassEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        GlassPanel(modifier = Modifier.fillMaxWidth(), radius = Radius.Hero) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = null, tint = Accent.copy(alpha = 0.8f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
