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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DocumentScanner
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
import com.alarsheef.archive.ui.components.DocumentScannerSaveDialog
import com.alarsheef.archive.ui.components.MergePagesDialog
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
    onImportZip: (() -> Unit)? = null,
    mergePagesYear: Int? = null,
    mergePagesMonth: Int? = null,
    mergePagesDay: Int? = null
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showScannerSaveDialog by remember { mutableStateOf(false) }
    var scannedPageBitmaps by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }

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

    val documentScannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCaptureFile != null) {
            val file = pendingCaptureFile!!
            pendingCaptureFile = null
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                scannedPageBitmaps = listOf(bitmap)
                showScannerSaveDialog = true
            }
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
            DropdownMenuItem(
                text = { Text("مسح مستند") },
                leadingIcon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        val (file, uri) = repository.createCameraCaptureTarget()
                        pendingCaptureFile = file
                        documentScannerLauncher.launch(uri)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
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
            DropdownMenuItem(
                text = { Text("دمج عدة صفحات") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    showMergeDialog = true
                }
            )
        }
    }
    if (showMergeDialog) {
        MergePagesDialog(
            repository = repository,
            snackbarHostState = snackbarHostState,
            scope = scope,
            initialYear = mergePagesYear,
            initialMonth = mergePagesMonth,
            initialDay = mergePagesDay,
            onDismiss = { showMergeDialog = false }
        )
    }
    if (showScannerSaveDialog) {
        DocumentScannerSaveDialog(
            pageBitmaps = scannedPageBitmaps,
            onSave = { name, category, ocrText, secure ->
                scope.launch {
                    scannedPageBitmaps.forEach { bitmap ->
                        val tempFile = File(context.cacheDir, "scan_${System.nanoTime()}.jpg")
                        tempFile.outputStream().use { stream ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)
                        }
                        repository.importFile(tempFile, SourceApp.DOCUMENT_SCAN)
                        tempFile.delete()
                    }
                    snackbarHostState.showSnackbar("تم حفظ ${scannedPageBitmaps.size} صفحة في أرشيف اليوم")
                    com.alarsheef.archive.work.AiAnalysisScheduler.start(context)
                    scannedPageBitmaps = emptyList()
                }
            },
            onExportPdf = { bitmaps, name ->
                scope.launch {
                    val pdfFile = repository.mergeImagesToPdf(
                        bitmaps.mapIndexed { index, bitmap ->
                            val tempFile = File(context.cacheDir, "scan_${index}_${System.nanoTime()}.jpg")
                            tempFile.outputStream().use { stream ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)
                            }
                            com.alarsheef.archive.data.entities.ArchivedImage(
                                id = 0,
                                fileName = tempFile.name,
                                storedPath = tempFile.absolutePath,
                                contentHash = "",
                                sourceApp = SourceApp.DOCUMENT_SCAN,
                                receivedCount = 1,
                                year = 0, month = 0, day = 0,
                                importedAt = 0,
                                capturedAt = 0,
                                originalPath = null
                            )
                        }
                    )
                    if (pdfFile != null) {
                        val uri = repository.getShareUriForFile(pdfFile)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "مشاركة PDF"))
                    }
                }
            },
            onDismiss = {
                scannedPageBitmaps = emptyList()
                showScannerSaveDialog = false
            }
        )
    }
}