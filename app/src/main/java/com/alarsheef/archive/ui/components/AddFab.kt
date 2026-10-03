package com.alarsheef.archive.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.ImportResult
import com.alarsheef.archive.ui.theme.TealDark
import com.alarsheef.archive.util.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AddFab(
    repository: ArchiveRepository,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    onAddFolder: () -> Unit,
    onAddDay: (() -> Unit)? = null,
    onExportAll: (() -> Unit)? = null,
    onImportZip: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

fun showResult(result: ImportResult, note: String? = null) {
    val baseMessage = when (result) {
        is ImportResult.Added -> "تمت الإضافة إلى الأرشيف"
        is ImportResult.Duplicate -> "هذي الصورة موجودة من قبل — استُلمت ${result.newCount} مرات"
        is ImportResult.Failed -> "تعذّر استيراد الملف"
    }
    val notePart = note ?: ""
    val message = if (notePart.isNotEmpty()) "$baseMessage $notePart" else baseMessage
    scope.launch {
        snackbarHostState.showSnackbar(message)
        if (result is ImportResult.Added) {
            com.alarsheef.archive.work.AiAnalysisScheduler.start(context)
        }
    }
}

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (success && file != null) {
            scope.launch { showResult(repository.importFile(file, SourceApp.MANUAL_CAMERA), null) }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val (file, uri) = repository.createCameraCaptureTarget()
            pendingCaptureFile = file
            cameraLauncher.launch(uri)
        } else {
            scope.launch { snackbarHostState.showSnackbar("صلاحية الكاميرا مطلوبة للالتقاط") }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch { showResult(repository.importFromUri(uri, SourceApp.MANUAL_IMPORT), null) }
        }
    }

    Box {
        ExtendedFloatingActionButton(
            onClick = { menuExpanded = true },
            containerColor = Color.White,
            contentColor = TealDark,
            text = { Text("إضافة") },
            icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) }
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("إنشاء — التقاط صورة") },
                leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        val (file, uri) = repository.createCameraCaptureTarget()
                        pendingCaptureFile = file
                        cameraLauncher.launch(uri)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )
            DropdownMenuItem(
                text = { Text("استيراد — من المعرض/الجهاز") },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
            if (FeatureFlags.SUB_FOLDERS_ENABLED) {
                DropdownMenuItem(
                    text = { Text("إضافة مجلد") },
                    leadingIcon = { Icon(Icons.Filled.Create, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onAddFolder()
                    }
                )
            }
            if (onAddDay != null) {
                DropdownMenuItem(
                    text = { Text("إضافة يوم") },
                    leadingIcon = { Icon(Icons.Filled.CalendarViewDay, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onAddDay()
                    }
                )
            }
            if (onExportAll != null) {
                DropdownMenuItem(
                    text = { Text("تصدير كل الملفات (ZIP)") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = { menuExpanded = false; onExportAll() }
                )
            }
            if (onImportZip != null) {
                DropdownMenuItem(
                    text = { Text("استيراد أرشيف (ZIP)") },
                    leadingIcon = { Icon(Icons.Filled.Create, contentDescription = null) },
                    onClick = { menuExpanded = false; onImportZip() }
                )
            }
        }
    }
}
