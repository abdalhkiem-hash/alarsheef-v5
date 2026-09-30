package com.alarsheef.archive.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// نمط زجاجي متدرّج: أسطح شفافة بيضاء + نصوص بيضاء فوق تدرّج تركوازي–نيلي
private val LightColors = lightColorScheme(
    primary = TealBright,
    onPrimary = Color(0xFF06231F),
    primaryContainer = GlassStrong,
    onPrimaryContainer = Color.White,
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0x40F0A63A),
    onSecondaryContainer = Color.White,
    background = AppBackground,
    surface = Surface,
    surfaceVariant = SurfaceTinted,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextMuted,
    outline = OutlineSoft,
    outlineVariant = Color(0x33FFFFFF),
    error = ErrorRed,
    onError = Color(0xFF3B0A06)
)

// أشكال عصرية مستديرة الملامح — تُطبَّق تلقائيًا على كل Card/Dialog عبر MaterialTheme
val ArsheefShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun ArsheefTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // شريط حالة شفاف كليًا فوق التدرّج + أيقونات فاتحة (مظهر زجاجي متدرّج)
            window.statusBarColor = Color.Transparent.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = LightColors,
        typography = ArsheefTypography,
        shapes = ArsheefShapes,
        content = content
    )
}
