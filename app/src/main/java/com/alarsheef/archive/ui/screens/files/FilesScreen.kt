package com.alarsheef.archive.ui.screens.files

import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.roundToInt
import com.alarsheef.archive.ui.theme.GradientStart
import com.alarsheef.archive.ui.theme.GradientMid
import com.alarsheef.archive.ui.theme.GradientEnd
import com.alarsheef.archive.ui.theme.TextMuted
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.util.ArchiveNaming
import com.alarsheef.archive.ui.components.CountBadge
import com.alarsheef.archive.ui.components.IconTile
import androidx.compose.foundation.clickable
import com.alarsheef.archive.ui.components.GlassTopBar
import com.alarsheef.archive.ui.components.GlassIconButton
import com.alarsheef.archive.ui.components.GlassPanel
import com.alarsheef.archive.ui.components.GlassEmptyState
import com.alarsheef.archive.ui.components.ConfirmDeleteDialog
import com.alarsheef.archive.ui.theme.Accent
import com.alarsheef.archive.ui.theme.OnAccent
import com.alarsheef.archive.ui.theme.Radius
import com.alarsheef.archive.ui.theme.GlassSheet
import com.alarsheef.archive.ui.theme.Glass
import com.alarsheef.archive.ui.theme.Teal
import com.alarsheef.archive.ui.theme.BadgePdf
import com.alarsheef.archive.ui.theme.BadgeDoc
import com.alarsheef.archive.ui.theme.BadgeSheet
import com.alarsheef.archive.ui.theme.BadgeOther
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.ui.input.pointer.positionChanged
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
import com.alarsheef.archive.ui.components.RenameFileDialog
import com.alarsheef.archive.ui.components.SearchField
import com.alarsheef.archive.ui.theme.Amber
import com.alarsheef.archive.ui.theme.GlassBorder
import com.alarsheef.archive.ui.theme.GlassStrong
import com.alarsheef.archive.ui.theme.TealBright
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
        // (عدد ضخم من الملفات يتجاوز حد الـBinder — لا يُسقط التطبيق)
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
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
                GlassTopBar(
                    title = "${selected.size} محدد",
                    leading = {
                        GlassIconButton(Icons.Filled.Close, "إلغاء التحديد", { selected = emptySet() })
                    },
                    actions = {
                        GlassIconButton(
                            Icons.Filled.Check, "تحديد الكل",
                            { selected = if (selected.size == filtered.size) emptySet() else filtered.map { it.id }.toSet() }
                        )
                    }
                )
            } else {
                GlassTopBar(
                    title = String.format(Locale.ROOT, "%02d %s %d", day, FileUtils.monthArabicName(month), year),
                    onBack = onBack,
                    actions = {
                        GlassIconButton(
                            Icons.Filled.Check, "تحديد الكل",
                            { selected = filtered.map { it.id }.toSet() }
                        )
                        GlassIconButton(Icons.Filled.Share, "مشاركة", { shareScope() })
                    }
                )
            }
        },
        floatingActionButton = {
            // في وضع التحديد يختفي الزر حتى لا يغطي شريط الأزرار السفلي
            // (مشاركة/نقل/حذف) — زر الحذف كان مختفيًا خلفه في التخطيط RTL.
            if (!hasSelection) {
                AddFab(
                    repository = repository,
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                    onAddFolder = { showNewFolderDialog = true },
                    onExportAll = { pendingExportDay = day; exportLauncher.launch("alarsheef-$year-${FileUtils.twoDigits(month)}-${FileUtils.twoDigits(day)}.zip") },
                    onImportZip = {
                        importLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                        )
                    },
                    mergePagesYear = year,
                    mergePagesMonth = month,
                    mergePagesDay = day
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchField(query = query, onQueryChange = { query = it }, placeholder = "ابحث في هذا اليوم...")

            if (filtered.isEmpty()) {
                GlassEmptyState(
                    icon = Icons.Filled.Image,
                    title = if (images.isEmpty()) "لا توجد ملفات في هذا اليوم" else "لا توجد نتائج"
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, if (hasSelection) 90.dp else 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(filtered, key = { _, img -> img.id }) { index, img ->
                        GridTile(
                            image = img,
                            isSelected = selected.contains(img.id),
                            onClick = {
                                if (hasSelection) toggleSelect(img.id) else viewerIndex = index
                            },
                            onLongClick = { toggleSelect(img.id) }
                        )
                    }
                }
            }

            if (hasSelection) {
                GlassPanel(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    radius = Radius.Hero,
                    background = GlassSheet
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        BottomActionButton(Icons.Filled.Share, "مشاركة", Accent) { shareDialogOpen = true }
                        BottomActionButton(Icons.Filled.SwapHoriz, "نقل", Accent) { moveDialogOpen = true }
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
                if (ty == year && tm == month && td == day) {
                    moveDialogOpen = false
                    scope.launch { snackbarHostState.showSnackbar("الملفات موجودة في هذا اليوم أصلًا") }
                } else scope.launch {
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
        ConfirmDeleteDialog(
            message = "هل تريد حذف ${selected.size} ملف نهائيًا؟ لا يمكن التراجع عن هذا الإجراء.",
            onConfirm = {
                scope.launch {
                    val remaining = images.size - selectedImages.size
                    repository.deleteImages(selectedImages)
                    selected = emptySet()
                    if (remaining == 0) onBack()
                }
            },
            onDismiss = { confirmDeleteOpen = false }
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

private enum class FileKind(val label: String, val color: Color) {
    PDF("PDF", BadgePdf), IMAGE("صورة", Teal), DOC("DOC", BadgeDoc), SHEET("XLS", BadgeSheet), OTHER("ملف", BadgeOther)
}

/** نوع الملف للشارة: صفحات PDF المحوَّلة تُعرف باسمها الأصلي (.pdf)، والباقي بالامتداد. */
private fun kindOf(image: ArchivedImage): FileKind {
    val original = (image.originalName ?: "").lowercase(Locale.ROOT)
    val ext = image.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        original.contains(".pdf") || ext == "pdf" -> FileKind.PDF
        ext in setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp") -> FileKind.IMAGE
        ext in setOf("doc", "docx") -> FileKind.DOC
        ext in setOf("xls", "xlsx", "csv") -> FileKind.SHEET
        else -> FileKind.OTHER
    }
}

/**
 * بلاطة ملف (تصميم 5): بطاقة زجاجية، صورة مصغّرة بزوايا مستديرة، شارة نوع، والاسم أسفلها (لا فوق الصورة).
 * التحديد = حدّ مضيء بلون مميّز + علامة صح.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(
    image: ArchivedImage,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(Radius.Row)
    val kind = kindOf(image)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(Glass)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Accent else GlassBorder, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Radius.Control))
        ) {
            AsyncImage(
                model = File(image.storedPath),
                contentDescription = image.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(kind.color),
                contentAlignment = Alignment.Center
            ) {
                if (kind == FileKind.IMAGE) {
                    Icon(Icons.Filled.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                } else {
                    Text(kind.label, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            if (image.receivedCount > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Amber, RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text("×${image.receivedCount}", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(Accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = OnAccent, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            image.fileName.substringBeforeLast('.'),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
    }
}

private fun sourceLabel(image: ArchivedImage): String = when (image.sourceApp) {
    com.alarsheef.archive.data.entities.SourceApp.WHATSAPP -> "من واتساب"
    com.alarsheef.archive.data.entities.SourceApp.WHATSAPP_BUSINESS -> "من واتساب أعمال"
    com.alarsheef.archive.data.entities.SourceApp.GALLERY -> "من الاستديو"
    com.alarsheef.archive.data.entities.SourceApp.DOWNLOADS -> "من التنزيلات"
    com.alarsheef.archive.data.entities.SourceApp.MANUAL_CAMERA -> "التقاط صورة"
    com.alarsheef.archive.data.entities.SourceApp.MANUAL_IMPORT -> "استيراد يدوي"
    com.alarsheef.archive.data.entities.SourceApp.DOCUMENT_SCAN -> "مسح مستند"
    com.alarsheef.archive.data.entities.SourceApp.INBOX -> "مجلد الإدخال"
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
                val ty = (y.trim().toIntOrNull() ?: defaultYear).coerceIn(1970, 2100)
                val tm = (m.trim().toIntOrNull() ?: defaultMonth).coerceIn(1, 12)
                // لا يُنشأ يوم غير موجود في التقويم (31 فبراير...)
                val daysInMonth = java.util.Calendar.getInstance().apply { clear(); set(ty, tm - 1, 1) }
                    .getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                val td = (d.trim().toIntOrNull() ?: defaultDay).coerceIn(1, daysInMonth)
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
    val viewerScope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<ArchivedImage?>(null) }
    // التكبير للصفحة الحالية فقط (يُصفَّر عند تغيير الصفحة) — مرفوع هنا ليتحكم به زرا + و−
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val page = pagerState.currentPage
    val current = images.getOrNull(page)

    BackHandler(onBack = onClose)
    LaunchedEffect(page) {
        zoom = 1f
        offset = Offset.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GradientStart, GradientMid, GradientEnd)))
    ) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            // شريط علوي زجاجي (تصميم 6): رجوع يمين، اسم الملف وسط، تسمية ومشاركة يسار
            GlassTopBar(
                title = current?.fileName ?: "",
                onBack = onClose,
                actions = {
                    GlassIconButton(Icons.Filled.Edit, "إعادة تسمية", { renaming = current })
                    GlassIconButton(Icons.Filled.Share, "مشاركة", { current?.let(onShare) })
                }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // الصفحات المجاورة تظهر جزئيًا على الجانبين (contentPadding) كما في التصميم
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
                    pageSpacing = 12.dp,
                    modifier = Modifier.fillMaxSize()
                ) { index ->
                    // القائمة قد تقصر أثناء العرض (حذف/رفض بالخلفية) قبل أن يُحدَّث عدد الصفحات
                    val img = images.getOrNull(index) ?: return@HorizontalPager
                    val isCurrent = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(Radius.Hero))
                            .background(Glass)
                            .border(1.dp, GlassBorder, RoundedCornerShape(Radius.Hero))
                            .pointerInput(index, isCurrent) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (!isCurrent) return@awaitEachGesture
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (pressedCount == 0) break
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        if (pressedCount >= 2) {
                                            // إصبعان فأكثر: تقريب/تصغير، وتحريك داخل الصورة عند التكبير فقط
                                            val newScale = (zoom * zoomChange).coerceIn(1f, 5f)
                                            zoom = newScale
                                            offset = if (newScale <= 1f) Offset.Zero else offset + panChange
                                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            }
                                        } else if (zoom > 1f && panChange != Offset.Zero) {
                                            // إصبع واحد والصورة مكبّرة: تحريك داخلها دون تغيير الصفحة
                                            offset += panChange
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    }
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
                                .padding(8.dp)
                                .graphicsLayer(
                                    scaleX = if (isCurrent) zoom else 1f,
                                    scaleY = if (isCurrent) zoom else 1f,
                                    translationX = if (isCurrent) offset.x else 0f,
                                    translationY = if (isCurrent) offset.y else 0f
                                )
                        )
                    }
                }
                // سهما التنقّل: في RTL الصفحة السابقة يمينًا والتالية يسارًا
                if (page > 0) {
                    GlassIconButton(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft, "السابق",
                        { viewerScope.launch { pagerState.animateScrollToPage(page - 1) } },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)
                    )
                }
                if (page < images.size - 1) {
                    GlassIconButton(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, "التالي",
                        { viewerScope.launch { pagerState.animateScrollToPage(page + 1) } },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
                    )
                }
            }

            // مؤشر الصفحات + عدّاد + عناصر التكبير
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (images.size in 2..9) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        repeat(images.size) { i ->
                            Box(
                                modifier = Modifier
                                    .size(if (i == page) 18.dp else 6.dp, 6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (i == page) Accent else GlassBorder)
                            )
                        }
                    }
                }
                if (images.size > 1) {
                    Text(
                        "${page + 1} / ${images.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                GlassPanel(modifier = Modifier.padding(top = 8.dp), radius = Radius.Hero) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            zoom = (zoom - 0.5f).coerceIn(1f, 5f)
                            if (zoom <= 1f) offset = Offset.Zero
                        }) { Icon(Icons.Filled.Remove, contentDescription = "تصغير", tint = Color.White) }
                        Text(
                            "${(zoom * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.width(56.dp),
                            textAlign = TextAlign.Center
                        )
                        IconButton(onClick = { zoom = (zoom + 0.5f).coerceIn(1f, 5f) }) {
                            Icon(Icons.Filled.Add, contentDescription = "تكبير", tint = Color.White)
                        }
                    }
                }
            }

            if (current != null) ViewerInfoPanel(repository, current)
        }

        renaming?.let { img ->
            RenameFileDialog(
                initial = img.fileName.substringBeforeLast('.'),
                onSave = { text -> viewerScope.launch { repository.renameImage(img, text, lock = true) } },
                onDismiss = { renaming = null }
            )
        }
    }
}

/** لوحة التفاصيل السفلية (تصميم 6): النوع والوصف، شارة الفئة، ثم الجهة/المبلغ/التاريخ. */
@Composable
private fun ViewerInfoPanel(repository: ArchiveRepository, current: ArchivedImage) {
    val aiLabels by repository.observeLabelsForImage(current.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val ocr by repository.observeOcrForImage(current.id)
        .collectAsStateWithLifecycle(initialValue = null)
    val cloud by repository.observeCloudMeta(current.id)
        .collectAsStateWithLifecycle(initialValue = null)
    val cloudMeta = cloud
    val typeLabel = ArchiveNaming.labelOf(current.fileName)
    val isDocument = current.contentVerified == ContentVerified.ACCEPTED || typeLabel != null

    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        radius = Radius.Hero,
        background = GlassStrong
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(size = 48.dp, circle = true) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = Accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isDocument) "مستند" else "صورة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    val description = cloudMeta?.description?.takeIf { it.isNotBlank() }
                        ?: ocr?.text?.trim()?.replace('\n', ' ')?.take(120)?.takeIf { it.isNotEmpty() }
                    if (description != null) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (typeLabel != null) CountBadge(typeLabel.replace('_', ' '))
            }

            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(GlassBorder))

            Row(modifier = Modifier.fillMaxWidth()) {
                DetailItem(Icons.Filled.Business, "الجهة", cloudMeta?.vendor)
                DetailItem(
                    Icons.Filled.AccountBalanceWallet, "المبلغ",
                    cloudMeta?.amount?.takeIf { it.isNotBlank() }?.let { amount ->
                        cloudMeta?.currency?.takeIf { it.isNotBlank() }?.let { "$amount $it" } ?: amount
                    }
                )
                DetailItem(Icons.Filled.CalendarMonth, "التاريخ", cloudMeta?.invoiceDate)
            }

            if (aiLabels.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    aiLabels.take(4).forEach { label ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.Small))
                                .background(Glass)
                                .border(1.dp, GlassBorder, RoundedCornerShape(Radius.Small))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(label.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            val originalName = current.originalName
            val meta = buildList {
                add(sourceLabel(current))
                add(FileUtils.formatTime(current.importedAt))
                if (current.receivedCount > 1) add("استُلمت ${current.receivedCount} مرات")
                if (!originalName.isNullOrBlank() && originalName != current.fileName) add("الاسم الأصلي: $originalName")
            }.joinToString(" · ")
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun RowScope.DetailItem(icon: ImageVector, label: String, value: String?) {
    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Text(
            value?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
