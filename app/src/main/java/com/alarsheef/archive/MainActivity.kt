package com.alarsheef.archive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.settings.OnboardingPreferences
import com.alarsheef.archive.ui.navigation.Screen
import com.alarsheef.archive.ui.onboarding.OnboardingFlow
import com.alarsheef.archive.ui.screens.day.DayScreen
import com.alarsheef.archive.ui.screens.files.FilesScreen
import com.alarsheef.archive.ui.screens.faces.FacesScreen
import com.alarsheef.archive.ui.screens.home.HomeScreen
import com.alarsheef.archive.ui.screens.month.MonthScreen
import com.alarsheef.archive.ui.theme.Amber
import com.alarsheef.archive.ui.theme.ArsheefTheme
import com.alarsheef.archive.ui.theme.GlowCyan
import com.alarsheef.archive.ui.theme.GlowIndigo
import com.alarsheef.archive.ui.theme.GradientEnd
import com.alarsheef.archive.ui.theme.GradientMid
import com.alarsheef.archive.ui.theme.GradientStart

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = ArchiveRepository(applicationContext)

        setContent {
            ArsheefTheme {
                ArsheefRoot(repository)
            }
        }
    }
}

@Composable
fun ArsheefRoot(repository: ArchiveRepository) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { OnboardingPreferences(context) }
    val onboardingDone by prefs.onboardingDone.collectAsStateWithLifecycle(initialValue = null)

    // خلفية زجاجية متدرّجة مشتركة لكل الشاشات (نمط B) + كرات ضوئية ناعمة
    val gradient = remember {
        Brush.verticalGradient(listOf(GradientStart, GradientMid, GradientEnd))
    }

    Box(modifier = Modifier.fillMaxSize().background(gradient)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            fun glow(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to color.copy(alpha = alpha),
                        1f to color.copy(alpha = 0f),
                        center = Offset(cx, cy),
                        radius = radius
                    ),
                    radius = radius,
                    center = Offset(cx, cy)
                )
            }
            val w = size.width
            val h = size.height
            glow(GlowCyan, w * 0.95f, h * 0.03f, w * 0.75f, 0.40f)
            glow(GlowIndigo, w * 1.05f, h * 0.86f, w * 0.85f, 0.55f)
            glow(Amber, -w * 0.18f, h * 0.70f, w * 0.60f, 0.14f)
        }

        when (onboardingDone) {
            null -> Surface(modifier = Modifier.fillMaxSize()) { /* لحظة تحميل قصيرة لقراءة الإعداد المحفوظ */ }
            false -> OnboardingFlow(onFinished = { /* الانتقال يصير تلقائيًا عبر تحديث الإعداد المحفوظ */ })
            true -> ArsheefNavGraph(repository)
        }
    }
}

@Composable
fun ArsheefNavGraph(repository: ArchiveRepository) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Home.route) {

        composable(Screen.Home.route) {
            HomeScreen(
                repository = repository,
                onOpenYear = { year -> navController.navigate(Screen.Month.build(year)) },
                onOpenFaces = { navController.navigate(Screen.Faces.route) },
                onOpenImage = { img ->
                    navController.navigate(
                        Screen.Files.build(img.year, img.month, img.day, openImageId = img.id)
                    )
                }
            )
        }

        composable(
            route = Screen.Month.route,
            arguments = listOf(navArgument("year") { type = NavType.IntType })
        ) { backStackEntry ->
            val year = backStackEntry.arguments?.getInt("year") ?: return@composable
            MonthScreen(
                repository = repository,
                year = year,
                onOpenMonth = { month -> navController.navigate(Screen.Day.build(year, month)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Day.route,
            arguments = listOf(
                navArgument("year") { type = NavType.IntType },
                navArgument("month") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val year = backStackEntry.arguments?.getInt("year") ?: return@composable
            val month = backStackEntry.arguments?.getInt("month") ?: return@composable
            DayScreen(
                repository = repository,
                year = year,
                month = month,
                onOpenDay = { day -> navController.navigate(Screen.Files.build(year, month, day)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Files.route,
            arguments = listOf(
                navArgument("year") { type = NavType.IntType },
                navArgument("month") { type = NavType.IntType },
                navArgument("day") { type = NavType.IntType },
                navArgument(Screen.Files.OPEN_IMAGE_ID_ARG) {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStackEntry ->
            val year = backStackEntry.arguments?.getInt("year") ?: return@composable
            val month = backStackEntry.arguments?.getInt("month") ?: return@composable
            val day = backStackEntry.arguments?.getInt("day") ?: return@composable
            val openImageId = backStackEntry.arguments?.getLong(Screen.Files.OPEN_IMAGE_ID_ARG) ?: -1L
            FilesScreen(
                repository = repository,
                year = year,
                month = month,
                day = day,
                openImageId = openImageId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Faces.route) {
            FacesScreen(
                repository = repository,
                onOpenImage = { y, m, d, imageId ->
                    navController.navigate(Screen.Files.build(y, m, d, openImageId = imageId))
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
