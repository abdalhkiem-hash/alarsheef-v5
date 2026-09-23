package com.alarsheef.archive.ui.screens.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.porter.ArchivePorter
import com.alarsheef.archive.porter.PorterResult
import com.alarsheef.archive.ui.components.AddFab
import com.alarsheef.archive.ui.components.NewFolderDialog
import com.alarsheef.archive.ui.components.SearchField
import com.alarsheef.archive.ui.theme.Amber
import com.alarsheef.archive.ui.theme.Teal
import com.alarsheef.archive.util.FileUtils
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    repository: ArchiveRepository,
    year: Int,
    month: Int,
    day: Int,
    onBack: () -> Unit,
    openImageId: Long = -1L
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val images by repository.observeDayImages(year, month, day).collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var moveDialogOpen by remember { mutableStateOf(false) }
    var shareDialogOpen by remember { mutableStateOf(false) }
    var confirmDeleteOpen by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var pendingExportDay by remember { mutableStateOf<Int?>(null) }
    val porter = remember { ArchivePorter(context, repository) }
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val day = pendingExportDay
        pendingExportDay = null
        if (uri != null && day != null) {
            scope.launch {
                val images = repository.byDayForExport(year, month, day)
                val result = porter.exportTo(uri, images)
                snackbarHostState.showSnackbar(
                    when (result) {
                        is PorterResult.Success -> "تم تصدير ${result.count} ملف"
                        is PorterResult.Failure -> "تعذّر تصدير اليوم"
                    }
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = porter.importFrom(uri)
                snackbarHostState.showSnackbar(
                    when (result) {
                        is PorterResult.Success -> "تم استيراد ${result.count} ملف جديد (المكرر يُتجاهل)"
                        is PorterResult.Failure -> "تعذّر استيراد الأرشيف"
                    }
                )
            }
        }
    }

    val filtered = images.filter { query.isBlank() || it.fileName.contains(query) }
    val hasSelection = selected.isNotEmpty()
    val selectedImages = images.filter { selected.contains(it.id) }

    fun toggleSelect(id: Long) {
        selected = if (selected.contains(id)) selected - id else selected + id
    }

    // القادم من نتائج البحث الشامل بالشاشة الرئيسية: يفتح الصورة في العارض مباشرة
    var autoOpened by remember { mutableIntStateOf(0) }
    LaunchedEffect(filtered) {
        if (openImageId > 0L && autoOpened == 0) {
            val idx = filtered.indexOfFirst { it.id == openImageId }
            if (idx >= 0) {
                viewerIndex = idx
                autoOpened = 1
            }
        }
    }

    // زر الرجوع يقفل العارض أو يلغي التحديد أولاً بدل ما يطلع من الشاشة
    BackHandler(enabled = viewerIndex != null) { viewerIndex = null }
    BackHandler(enabled = viewerIndex == null && hasSelection) { selected = emptySet() }

    fun shareUris(uris: List<android.net.Uri>, mimeType: String) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        // ClipData ضرورية عشان التطبيق المستقبِل يحصل فعليًا على صلاحية قراءة الملفات
        intent.clipData = android.content.ClipData.newUri(context.contentResolver, "files", uris.first()).apply {
            uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    }

    fun shareScope() {
        scope.launch {
            val uris = images.mapNotNull { repository.getShareUri(it) }
            if (uris.isNotEmpty()) shareUris(uris, "image/*")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (hasSelection) {
                TopAppBar(
                    title = { Text("${selected.size} محدد") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "إلغاء التحديد", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selected = if (selected.size == filtered.size) emptySet() else filtered.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Filled.Check, contentDescription = "تحديد الكل", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White)
                )
            } else {
                TopAppBar(
                    title = { Text(String.format(Locale.ROOT, "%02d %s %d", day, FileUtils.monthArabicName(month), year)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { shareScope() }) {
                            Icon(Icons.Filled.Share, contentDescription = "مشاركة", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White)
                )
            }
        },
        floatingActionButton = {
            AddFab(
                repository = repository,
                snackbarHostState = snackbarHostState,
                scope = scope,
                onAddFolder = { showNewFolderDialog = true },
                onAddDay = {},
                onExportAll = { pendingExportDay = day; exportLauncher.launch("alarsheef-$year-${FileUtils.twoDigits(month)}-${FileUtils.twoDigits(day)}.zip") },
                onImportZip = { importLauncher.launch(arrayOf("application/zip")) }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchField(query = query, onQueryChange = { query = it }, placeholder = "ابحث في هذا اليوم...")

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (images.isEmpty()) "لا توجد ملفات في هذا اليوم" else "لا توجد نتائج")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, if (hasSelection) 90.dp else 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filtered, key = { _, img -> img.id }) { index, img ->
                        FileRow(
                            image = img,
                            isSelected = selected.contains(img.id),
                            onToggleSelect = { toggleSelect(img.id) },
                            onClick = {
                                if (hasSelection) toggleSelect(img.id) else viewerIndex = index
                            }
                        )
                    }
                }
            }

            if (hasSelection) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        BottomActionButton(Icons.Filled.Share, "مشاركة", Teal) { shareDialogOpen = true }
                        BottomActionButton(Icons.Filled.SwapHoriz, "نقل", Teal) { moveDialogOpen = true }
                        BottomActionButton(Icons.Filled.Delete, "حذف", MaterialTheme.colorScheme.error) { confirmDeleteOpen = true }
                    }
                }
            }
        }
    }

    if (moveDialogOpen) {
        MoveDialog(
            defaultYear = year, defaultMonth = month, defaultDay = day,
            onDismiss = { moveDialogOpen = false },
            onConfirm = { ty, tm, td ->
                scope.launch {
                    val toMove = selectedImages
                    val moved = repository.moveImages(toMove, ty, tm, td)
                    val remaining = images.size - moved
                    selected = emptySet()
                    moveDialogOpen = false
                    when {
                        moved == 0 -> snackbarHostState.showSnackbar("تعذّر نقل الملفات — الملفات الأصلية غير موجودة")
                        remaining == 0 -> onBack()
                        else -> snackbarHostState.showSnackbar("تم نقل $moved ملف إلى $td/$tm/$ty")
                    }
                }
            }
        )
    }

    if (confirmDeleteOpen) {
        AlertDialog(
            onDismissRequest = { confirmDeleteOpen = false },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل تريد حذف ${selected.size} ملف نهائيًا؟ لا يمكن التراجع عن هذا الإجراء.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val remaining = images.size - selectedImages.size
                        repository.deleteImages(selectedImages)
                        selected = emptySet()
                        confirmDeleteOpen = false
                        if (remaining == 0) onBack()
                    }
                }) { Text("حذف نهائيًا", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteOpen = false }) { Text("إلغاء") } }
        )
    }

    if (shareDialogOpen) {
        AlertDialog(
            onDismissRequest = { shareDialogOpen = false },
            title = { Text("مشاركة ${selected.size} ملف") },
            text = {
                Column {
                    TextButton(onClick = {
                        shareDialogOpen = false
                        val uris = selectedImages.map { repository.getShareUri(it) }
                        shareUris(uris, "image/*")
                    }) { Text("مشاركة كل ملف لحاله") }
                    TextButton(onClick = {
                        shareDialogOpen = false
                        val toMerge = selectedImages
                        scope.launch {
                            val pdfFile = repository.mergeImagesToPdf(toMerge)
                            if (pdfFile == null) {
                                snackbarHostState.showSnackbar("تعذّر إنشاء ملف PDF من الصور المحددة")
                            } else {
                                shareUris(listOf(repository.getShareUriForFile(pdfFile)), "application/pdf")
                            }
                        }
                    }) { Text("دمج الكل في PDF واحد ومشاركته") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { shareDialogOpen = false }) { Text("إلغاء") } }
        )
    }

    viewerIndex?.let { idx ->
        ImageViewer(
            repository = repository,
            images = filtered,
            startIndex = idx,
            onClose = { viewerIndex = null },
            onShare = { img -> shareUris(listOf(repository.getShareUri(img)), "image/*") }
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onSave = { name -> scope.launch { repository.createSubFolder(year, month, name) } },
            onDismiss = { showNewFolderDialog = false }
        )
    }
}

@Composable
private fun FileRow(
    image: ArchivedImage,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(46.dp)) {
                AsyncImage(
                    model = File(image.storedPath),
                    contentDescription = image.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                )
                if (image.receivedCount > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(Amber, RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp),
                    ) {
                        Text("×${image.receivedCount}", color = Color.White, fontSize = MaterialTheme.typography.labelLarge.fontSize)
                    }
                }
            }
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(image.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${sourceLabel(image)} · ${FileUtils.formatTime(image.importedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            androidx.compose.material3.Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
        }
    }
}

private fun sourceLabel(image: ArchivedImage): String = when (image.sourceApp) {
    com.alarsheef.archive.data.entities.SourceApp.WHATSAPP -> "من واتساب"
    com.alarsheef.archive.data.entities.SourceApp.GALLERY -> "من الاستديو"
    com.alarsheef.archive.data.entities.SourceApp.DOWNLOADS -> "من التنزيلات"
    com.alarsheef.archive.data.entities.SourceApp.MANUAL_CAMERA -> "التقاط صورة"
    com.alarsheef.archive.data.entities.SourceApp.MANUAL_IMPORT -> "استيراد يدوي"
}

@Composable
private fun BottomActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = color) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun MoveDialog(
    defaultYear: Int, defaultMonth: Int, defaultDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int) -> Unit
) {
    var y by remember { mutableStateOf(defaultYear.toString()) }
    var m by remember { mutableStateOf(defaultMonth.toString()) }
    var d by remember { mutableStateOf(defaultDay.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("نقل إلى يوم آخر") },
        text = {
            Column {
                OutlinedTextField(value = y, onValueChange = { y = it }, label = { Text("السنة") })
                OutlinedTextField(value = m, onValueChange = { m = it }, label = { Text("الشهر (1-12)") })
                OutlinedTextField(value = d, onValueChange = { d = it }, label = { Text("اليوم (1-31)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ty = y.toIntOrNull() ?: defaultYear
                val tm = (m.toIntOrNull() ?: defaultMonth).coerceIn(1, 12)
                val td = (d.toIntOrNull() ?: defaultDay).coerceIn(1, 31)
                onConfirm(ty, tm, td)
            }) { Text("نقل") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewer(
    repository: ArchiveRepository,
    images: List<ArchivedImage>,
    startIndex: Int,
    onClose: () -> Unit,
    onShare: (ArchivedImage) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { images.size }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val img = images[page]
            var scale by remember(page) { mutableStateOf(1f) }
            var offset by remember(page) { mutableStateOf(Offset.Zero) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(page) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale <= 1f) Offset.Zero else offset + pan
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = File(img.storedPath),
                    contentDescription = img.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offset.x, translationY = offset.y
                        )
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
        ) {
            IconButton(onClick = { onShare(images[pagerState.currentPage]) }) {
                Icon(Icons.Filled.Share, contentDescription = "مشاركة", tint = Color.White)
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(14.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = Color.White)
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val current = images.getOrNull(pagerState.currentPage)
            if (current != null) {
                Text(current.fileName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                val meta = "${sourceLabel(current)} · ${FileUtils.formatTime(current.importedAt)}" +
                    if (current.receivedCount > 1) " · استُلمت ${current.receivedCount} مرات" else ""
                Text(meta, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)

                // وسوم التصنيف التلقائي + مقتطف النص المستخرج (اختياريان حسب ما حلّله المحرّك)
                val aiLabels by repository.observeLabelsForImage(current.id)
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                val ocr by repository.observeOcrForImage(current.id)
                    .collectAsStateWithLifecycle(initialValue = null)

                if (aiLabels.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        aiLabels.take(4).forEach { label ->
                            Box(
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(label.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                val ocrText = ocr?.text?.trim().orEmpty()
                if (ocrText.isNotEmpty()) {
                    Text(
                        ocrText.replace('\n', ' ').take(160),
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp)
                    )
                }
            }
        }
    }
}
