package com.alarsheef.archive.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alarsheef.archive.settings.OnboardingPreferences
import com.alarsheef.archive.ui.theme.Amber
import com.alarsheef.archive.ui.theme.Teal
import com.alarsheef.archive.util.PermissionUtils
import kotlinx.coroutines.launch

private data class Slide(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val desc: String)

private val slides = listOf(
    Slide(
        Icons.Filled.Sync,
        "أرشفة تلقائية يومية",
        "التطبيق يسحب صورك وملفات PDF من واتساب والاستديو والملفات تلقائيًا كل يوم، بدون ما تسوي شي بنفسك."
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
    var step by remember { mutableIntStateOf(0) } // 0..2 = welcome slides, 3 = permission
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { OnboardingPreferences(context) }

    fun completeOnboarding() {
        scope.launch {
            prefs.setOnboardingDone(true)
            onFinished()
        }
    }

    if (step < slides.size) {
        WelcomeSlideScreen(
            slide = slides[step],
            index = step,
            total = slides.size,
            isLast = step == slides.size - 1,
            onSkip = { step = slides.size },
            onNext = { step += 1 }
        )
    } else {
        PermissionScreen(onGranted = { completeOnboarding() })
    }
}

@Composable
private fun WelcomeSlideScreen(
    slide: Slide,
    index: Int,
    total: Int,
    isLast: Boolean,
    onSkip: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Teal)
            .padding(24.dp, 24.dp, 24.dp, 30.dp)
    ) {
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End)) {
            Text("تخطي", color = Color.White.copy(alpha = 0.85f), fontSize = MaterialTheme.typography.bodyMedium.fontSize)
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(slide.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Text(
                slide.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
            )
            Text(
                slide.desc,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f))
            repeat(total) { i ->
                Box(
                    modifier = Modifier
                        .size(if (i == index) 20.dp else 6.dp, 6.dp)
                        .background(
                            if (i == index) Color.White else Color.White.copy(alpha = 0.4f),
                            RoundedCornerShape(3.dp)
                        )
                )
            }
            Box(modifier = Modifier.weight(1f))
        }

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Teal)
        ) {
            Text(if (isLast) "ابدأ" else "التالي", modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun PermissionScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onGranted()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Teal)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(84.dp).background(Color.White.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Text(
            "صلاحية الصور والفيديو",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)
        )
        Text(
            "لتمكين سحب صورك من المعرض وواتساب تلقائيًا، يُرجى منح صلاحية الوصول للصور والفيديو.\n\nبدون هذي الصلاحية، ما نقدر نفتح لك الأرشيف.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
        )
        Button(
            onClick = { permissionLauncher.launch(PermissionUtils.mediaPermissions) },
            modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.White)
        ) {
            Text("منح الصلاحية", modifier = Modifier.padding(vertical = 6.dp))
        }
        Text(
            "سيفتح هذا صفحة الصلاحيات",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}
