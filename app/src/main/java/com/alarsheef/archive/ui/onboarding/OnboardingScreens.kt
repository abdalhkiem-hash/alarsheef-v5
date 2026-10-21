package com.alarsheef.archive.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alarsheef.archive.settings.OnboardingPreferences
import com.alarsheef.archive.ui.components.GlassPanel
import com.alarsheef.archive.ui.components.PrimaryActionButton
import com.alarsheef.archive.ui.components.SecondaryActionButton
import com.alarsheef.archive.ui.theme.Accent
import com.alarsheef.archive.ui.theme.Glass
import com.alarsheef.archive.ui.theme.GlassBorder
import com.alarsheef.archive.ui.theme.GlassStrong
import com.alarsheef.archive.ui.theme.Radius
import com.alarsheef.archive.ui.theme.TextMuted
import com.alarsheef.archive.util.PermissionUtils
import kotlinx.coroutines.launch

private data class Slide(val icon: ImageVector, val title: String, val desc: String)

private val slides = listOf(
    Slide(
        Icons.Filled.Sync,
        "مجلد الإدخال",
        "ضع صور المستندات وملفات PDF في مجلد واحد. التطبيق يؤرشف ما فيه، ويترك المعرض وواتساب والتنزيلات."
    ),
    Slide(
        Icons.Filled.CalendarViewMonth,
        "تنظيم واضح",
        "كل شي يترتب سنة ← شهر ← يوم تلقائيًا، عشان تلقى أي ملف بثواني مهما تراكم الأرشيف."
    ),
    Slide(
        Icons.Filled.Tune,
        "تحكم كامل بين يديك",
        "انقل، شارك، احذف، أو ادمج ملفاتك وقت ما تبي — أنت المشرف الكامل على أرشيفك."
    )
)

@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    var step by remember { mutableIntStateOf(0) } // 0..2 = شرائح الترحيب، 3 = الصلاحية
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { OnboardingPreferences(context) }

    fun completeOnboarding() {
        scope.launch {
            prefs.setOnboardingDone(true)
            // مجلد الإدخال لا يحتاج صلاحية الصور. بلا مجلد يكتفي الفحص بالخروج.
            com.alarsheef.archive.scanner.WorkScheduler.runOnce(context)
            onFinished()
        }
    }

    if (step < slides.size) {
        val slide = slides[step]
        OnboardingPage(
            icon = slide.icon,
            title = slide.title,
            desc = slide.desc,
            step = step,
            total = slides.size + 1,
            note = null,
            primaryText = if (step == slides.size - 1) "ابدأ" else "التالي",
            primaryIcon = Icons.AutoMirrored.Filled.ArrowForward,
            onPrimary = { step += 1 },
            secondaryText = "تخطي",
            onSecondary = { step = slides.size }
        )
    } else {
        PermissionScreen(total = slides.size + 1, onGranted = { completeOnboarding() })
    }
}

/**
 * قالب صفحة الترحيب (تصميم 1): أيقونة زجاجية كبيرة، عنوان، وصف، مؤشر خطوات، زر رئيسي وزر ثانوي.
 */
@Composable
private fun OnboardingPage(
    icon: ImageVector,
    title: String,
    desc: String,
    step: Int,
    total: Int,
    note: String?,
    primaryText: String,
    primaryIcon: ImageVector,
    onPrimary: () -> Unit,
    secondaryText: String,
    onSecondary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            HeroIcon(icon)
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodyLarge,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            if (note != null) {
                Spacer(Modifier.height(16.dp))
                GlassPanel(modifier = Modifier.fillMaxWidth(), radius = Radius.Row) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            StepIndicator(step = step, total = total)
        }
        PrimaryActionButton(primaryText, onPrimary, Modifier.fillMaxWidth(), icon = primaryIcon)
        Spacer(Modifier.height(12.dp))
        SecondaryActionButton(secondaryText, onSecondary, Modifier.fillMaxWidth())
    }
}

/** أيقونة البطل: مربّع زجاجي كبير بزوايا مستديرة فوق هالة دائرية خافتة. */
@Composable
private fun HeroIcon(icon: ImageVector) {
    Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(210.dp).clip(CircleShape).background(Glass))
        Box(
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(GlassStrong)
                .border(1.dp, GlassBorder, RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(64.dp))
        }
    }
}

/** مؤشر الخطوات: عقد متصلة داخل كبسولة زجاجية (الأولى يمينًا). المنتهية مملوءة والحالية بحلقة. */
@Composable
private fun StepIndicator(step: Int, total: Int) {
    GlassPanel(radius = Radius.Control) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(total) { i ->
                if (i > 0) {
                    Box(
                        modifier = Modifier
                            .size(width = 22.dp, height = 2.dp)
                            .background(if (i <= step) Accent else GlassBorder)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (i < step) Accent else Color.Transparent)
                        .border(2.dp, if (i <= step) Accent else GlassBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (i == step) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Accent))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(total: Int, onGranted: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var denied by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onGranted()
        else denied++
    }

    // يعيد فحص الصلاحية كل مرة يرجع فيها المستخدم للتطبيق
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && PermissionUtils.hasMediaPermission(context)) {
                onGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OnboardingPage(
        icon = Icons.Filled.Archive,
        title = "احفظ مستنداتك بذكاء",
        desc = "أرشف صورك، فواتيرك وملفات PDF وابحث عنها بسهولة باستخدام البحث الذكي.",
        step = total - 1,
        total = total,
        note = if (denied > 0)
            "تم رفض الصلاحية. يمكنك تخطي هذه الخطوة واستخدام التطبيق بدون أرشفة تلقائية، أو منح الصلاحية لاحقًا من إعدادات التطبيق."
        else null,
        primaryText = "السماح بالوصول إلى الصور",
        primaryIcon = Icons.Filled.Image,
        onPrimary = { permissionLauncher.launch(PermissionUtils.mediaPermissions) },
        secondaryText = "تخطي الآن",
        onSecondary = { onGranted() }
    )
}
