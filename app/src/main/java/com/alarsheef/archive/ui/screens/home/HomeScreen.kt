package com.alarsheef.archive.ui.screens.home

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.alarsheef.archive.ui.components.GlassIconButton
import com.alarsheef.archive.ui.components.GlassPanel
import com.alarsheef.archive.ui.components.GlassTopBar
import com.alarsheef.archive.ui.components.IconTile
import com.alarsheef.archive.ai.GeminiRepository
import com.alarsheef.archive.ui.theme.Accent
import com.alarsheef.archive.ui.theme.OnAccent
import com.alarsheef.archive.ui.theme.Radius
import com.alarsheef.archive.ui.theme.TextMuted
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.YearRow
import com.alarsheef.archive.porter.ArchivePorter
import com.alarsheef.archive.porter.PorterResult
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.ui.components.AboutPanelContent
import com.alarsheef.archive.ui.components.AddFab
import com.alarsheef.archive.ui.components.AiPanelContent
import com.alarsheef.archive.ui.components.ArsheefDrawerContent
import com.alarsheef.archive.ui.components.BackupPanelContent
import com.alarsheef.archive.ui.components.ConfirmDeleteDialog
import com.alarsheef.archive.ui.components.ConfirmExitDialog
import com.alarsheef.archive.ui.components.CustomLabelDialog
import com.alarsheef.archive.ui.components.DrawerPanel
import com.alarsheef.archive.ui.components.ExportImportPanelContent
import com.alarsheef.archive.ui.components.NewFolderDialog
import com.alarsheef.archive.ui.components.SearchField
import com.alarsheef.archive.ui.components.SettingsPanelContent
import com.alarsheef.archive.ui.components.rememberScopeSuggestions
import com.alarsheef.archive.scanner.WorkScheduler
import com.alarsheef.archive.ui.theme.Glass
import com.alarsheef.archive.ui.theme.GlassBorder
import com.alarsheef.archive.ui.theme.GlassStrong
import com.alarsheef.archive.util.FileUtils
import com.alarsheef.archive.work.AiAnalysisScheduler
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

private sealed class PendingExport {
    data object All : PendingExport()
    data class Year(val year: Int) : PendingExport()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: ArchiveRepository,
    onOpenYear: (Int) -> Unit,
    onOpenImage: (ArchivedImage) -> Unit,
    onOpenFaces: () -> Unit,
    onOpenPending: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsPrefs = remember { SettingsPreferences(context) }
    val porter = remember { ArchivePorter(context, repository) }

    val cloudPauseUntil by GeminiRepository.cloudPauseUntil.collectAsStateWithLifecycle()
    val cloudPaused = cloudPauseUntil > System.currentTimeMillis()

    val years by remember { repository.observeYearRows() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val latestYear = years.firstOrNull()?.year
    val pendingImages by remember { repository.observePendingReviewImages() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingCount = pendingImages.size
    var yearsExpanded by remember { mutableStateOf(true) }

    val dashboardStats by remember(latestYear) {
        latestYear?.let { repository.observeDashboardStats(it) }
            ?: emptyFlow()
    }.collectAsStateWithLifecycle(
        initialValue = ArchiveRepository.DashboardStats(0, 0, 0, 0, 0)
    )
    val recentImages by remember { repository.observeRecentImages(8) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var storageBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        storageBytes = repository.computeStorageUsedBytes()
    }

    var query by remember { mutableStateOf("") }
    val isSearching = query.isNotBlank()
    val searchFieldFocus = remember { FocusRequester() }

    // تُبنى مرة واحدة لكل نص بحث؛ ولا استعلام أصلًا والبحث فارغ (LIKE '%%' كانت تُحمّل كل الأرشيف)
    val searchFlow = remember(query) {
        if (query.isBlank()) emptyFlow() else repository.searchAll(query)
    }
    val searchResults by searchFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var activePanel by remember { mutableStateOf<DrawerPanel?>(null) }

    var confirmDeleteYear by remember { mutableStateOf<Int?>(null) }
    var labelDialogScope by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    var pendingTreeTarget by remember { mutableStateOf<String?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    // زر الرجوع في الشاشة الرئيسية: يغلق القوائم المفتوحة أولًا، وإلا يعرض تأكيد الخروج
    BackHandler {
        when {
            activePanel != null -> activePanel = null
            drawerState.isOpen -> scope.launch { drawerState.close() }
            else -> showExitDialog = true
        }
    }

    val labelSuggestions = rememberScopeSuggestions(repository, labelDialogScope)

    val snackbarHostState = remember { SnackbarHostState() }

    fun shareUris(uris: List<android.net.Uri>, mimeType: String) {
        if (uris.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("لا توجد ملفات للمشاركة") }
            return
        }
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
        intent.clipData = android.content.ClipData.newUri(context.contentResolver, "files", uris.first()).apply {
            uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }

    fun shareScope(year: Int?, month: Int?, day: Int?) {
        scope.launch {
            val images = when {
                day != null -> repository.byDayForExport(year!!, month!!, day)
                month != null -> repository.byMonthForExport(year!!, month)
                year != null -> repository.byYearForExport(year)
                else -> repository.allForExport()
            }
            shareUris(images.map { repository.getShareUri(it) }, "image/*")
        }
    }

    // ---------- منتقيات النظام ----------

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val scopeFilter = pendingExport
        pendingExport = null
        if (uri != null && scopeFilter != null) {
            scope.launch {
                val images = when (scopeFilter) {
                    is PendingExport.All -> repository.allForExport()
                    is PendingExport.Year -> repository.byYearForExport(scopeFilter.year)
                }
                val result = porter.exportTo(uri, images)
                snackbarHostState.showSnackbar(
                    when (result) {
                        is PorterResult.Success -> "تم تصدير ${result.count} ملف"
                        is PorterResult.Failure -> "تعذّر تصدير الأرشيف"
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

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val target = pendingTreeTarget
        pendingTreeTarget = null
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            scope.launch {
when (target) {
                      "import" -> {
                          settingsPrefs.setImportFolderUri(uri.toString())
                          WorkScheduler.runOnce(context)
                      }
                      "backup" -> {
                          settingsPrefs.setBackupFolderUri(uri.toString())
                          // النسخ مفعّل وكان ينتظر مجلدًا: يُجدوَل ويُنفَّذ الآن بدل انتظار إعادة فتح التطبيق
                          if (settingsPrefs.backupEnabled.first()) {
                              com.alarsheef.archive.work.BackupScheduler.schedule(context, settingsPrefs.backupFrequency.first())
                              com.alarsheef.archive.work.BackupScheduler.runOnce(context)
                          }
                      }
                  }
                snackbarHostState.showSnackbar("تم اختيار المجلد")
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ArsheefDrawerContent(
                pendingCount = pendingCount,
                onOpenPending = {
                    scope.launch { drawerState.close() }
                    onOpenPending()
                }
            ) { panel ->
                activePanel = panel
                scope.launch { drawerState.close() }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                val panel = activePanel
                if (panel != null) {
                    GlassTopBar(
                        title = when (panel) {
                            DrawerPanel.SETTINGS -> "الإعدادات"
                            DrawerPanel.EXPORT_IMPORT -> "تصدير / استيراد"
                            DrawerPanel.BACKUP -> "النسخة الاحتياطية"
                            DrawerPanel.ABOUT -> "حول التطبيق"
                            DrawerPanel.AI -> "الذكاء الاصطناعي"
                        },
                        onBack = { activePanel = null }
                    )
                } else {
                    GlassTopBar(
                        title = "الأرشيف",
                        leading = {
                            GlassIconButton(Icons.Filled.Menu, "القائمة", { scope.launch { drawerState.open() } })
                        }
                    )
                }
            },
            floatingActionButton = { if (activePanel == null) AddFab(repository = repository, snackbarHostState = snackbarHostState, scope = scope, onAddFolder = { showNewFolderDialog = true }) }
        ) { padding ->
            if (activePanel != null) {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    when (activePanel) {
                        DrawerPanel.SETTINGS -> SettingsPanelContent(
                            prefs = settingsPrefs,
                            repository = repository,
                            scope = scope,
                            onPickImportFolder = {
                                pendingTreeTarget = "import"
                                treeLauncher.launch(null)
                            }
                        )
                        DrawerPanel.BACKUP -> BackupPanelContent(
                            prefs = settingsPrefs,
                            scope = scope,
                            onPickBackupFolder = {
                                pendingTreeTarget = "backup"
                                treeLauncher.launch(null)
                            }
                        )
                        DrawerPanel.EXPORT_IMPORT -> ExportImportPanelContent(
                            onExport = {
                                pendingExport = PendingExport.All
                                exportLauncher.launch("alarsheef-archive.zip")
                            },
                            // بعض مديري الملفات يصنّف ZIP بنوع آخر فيظهر الملف معطّلًا في المنتقي
                            onImport = {
                                importLauncher.launch(
                                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                                )
                            }
                        )
                        DrawerPanel.ABOUT -> AboutPanelContent()
                        DrawerPanel.AI -> AiPanelContent(
                            prefs = settingsPrefs,
                            repository = repository,
                            scope = scope,
                            onOpenFaces = onOpenFaces
                        )
                        null -> {}
                    }
                }
            } else {
            Column(modifier = Modifier.padding(padding)) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "ابحث في كل الأرشيف...",
                    focusRequester = searchFieldFocus
                )

                if (cloudPaused) {
                    Text(
                        "التسمية السحابية متوقفة مؤقتًا",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = Accent,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionBtn(
                        icon = Icons.Filled.Sync,
                        label = "فحص الآن يدويًا",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            WorkScheduler.runOnce(context)
                            scope.launch { snackbarHostState.showSnackbar("بدأ الفحص اليدوي — قد يستغرق دقيقة") }
                        }
                    )
                    QuickActionBtn(
                        icon = Icons.Filled.AutoAwesome,
                        label = "تشغيل التحليل الفوري",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            AiAnalysisScheduler.start(context)
                            scope.launch { snackbarHostState.showSnackbar("بدأ التحليل الفوري في الخلفية") }
                        }
                    )
                }

                if (isSearching) {
                    SearchResultsList(results = searchResults, onOpenResult = onOpenImage)
                } else if (years.isEmpty()) {
                    EmptyYearsState()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            HomeDashboard(
                                stats = dashboardStats,
                                storageText = FileUtils.formatBytes(storageBytes),
                                latestYear = latestYear,
                                recentImages = recentImages,
                                pendingCount = pendingCount,
                                onOpenYear = onOpenYear,
                                onOpenFaces = onOpenFaces,
                                onOpenImage = onOpenImage,
                                onOpenPending = onOpenPending
                            )
                        }
                        item {
                            GlassPanel(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { yearsExpanded = !yearsExpanded }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "كل السنوات",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (yearsExpanded) Icons.Filled.KeyboardArrowDown
                                        else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = TextMuted
                                    )
                                }
                            }
                        }
                        if (yearsExpanded) items(years, key = { it.year }) { row ->
                            YearRowCard(
                                row = row,
                                onClick = { onOpenYear(row.year) },
                                onExport = {
                                    pendingExport = PendingExport.Year(row.year)
                                    exportLauncher.launch("alarsheef-${row.year}.zip")
                                },
                                onShare = { shareScope(year = row.year, month = null, day = null) },
                                onLabel = { labelDialogScope = "y-${row.year}" },
                                onDelete = { confirmDeleteYear = row.year }
                            )
                        }
                    }
                }
            }
        }
    }
    }

    confirmDeleteYear?.let { year ->
        ConfirmDeleteDialog(
            message = "هل تريد حذف سنة $year كاملة؟ سيتم حذف كل الأشهر والأيام والملفات بداخلها نهائيًا.",
            onConfirm = { scope.launch { repository.deleteYear(year) } },
            onDismiss = { confirmDeleteYear = null }
        )
    }
    if (showExitDialog) {
        ConfirmExitDialog(
            onConfirm = {
                showExitDialog = false
                (context as? android.app.Activity)?.finishAffinity()
            },
            onDismiss = { showExitDialog = false }
        )
    }

    labelDialogScope?.let { scopeKey ->
        val currentLabel = years.find { "y-${it.year}" == scopeKey }?.label
        CustomLabelDialog(
            currentLabel = currentLabel,
            onSave = { text -> scope.launch { repository.setLabel(scopeKey, text) } },
            onDismiss = { labelDialogScope = null },
            suggestions = labelSuggestions
        )
    }

    if (showNewFolderDialog) {
        val latestYear = years.firstOrNull()?.year ?: 0
        NewFolderDialog(
            onSave = { name -> scope.launch { repository.createSubFolder(latestYear, 0, name) } },
            onDismiss = { showNewFolderDialog = false }
        )
    }
}

@Composable
private fun QuickActionBtn(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.Control))
            .background(Accent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = OnAccent, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OnAccent, maxLines = 1)
    }
}

@Composable
private fun YearRowCard(
    row: YearRow,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onLabel: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    GlassPanel(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(size = 48.dp) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label ?: "${row.year}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val subtitle = if (row.label != null) {
                    "${row.year} · ${row.fileCount} ملف"
                } else {
                    "${row.fileCount} ملف · آخر نشاط: ${FileUtils.monthArabicName(row.latestMonth)}"
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "خيارات", tint = TextMuted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("تصدير هذه السنة (ZIP)") }, onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("مشاركة كل ملفات السنة") }, onClick = { menuOpen = false; onShare() })
                    DropdownMenuItem(text = { Text("تسمية مخصصة") }, onClick = { menuOpen = false; onLabel() })
                    DropdownMenuItem(text = { Text("حذف", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(results: List<ArchivedImage>, onOpenResult: (ArchivedImage) -> Unit) {
    if (results.isEmpty()) {
        EmptyYearsState(text = "لا توجد نتائج")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(results, key = { it.id }) { img ->
            // نمط الصف الزجاجي الموحّد (Card الافتراضي كان سيصير لوحًا كحليًا معتمًا بالثيم الداكن)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Glass)
                    .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                    .clickable { onOpenResult(img) }
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(46.dp).background(GlassStrong, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Color.White)
                    }
                    Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(img.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            "${img.day}/${img.month}/${img.year}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDashboard(
    stats: ArchiveRepository.DashboardStats,
    storageText: String,
    latestYear: Int?,
    recentImages: List<ArchivedImage>,
    pendingCount: Int,
    onOpenYear: (Int) -> Unit,
    onOpenFaces: () -> Unit,
    onOpenImage: (ArchivedImage) -> Unit,
    onOpenPending: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // بطاقة البطل (تصميم 2): السنة + آخر تحديث للأرشيف + أيقونة، وتحتها شريط إحصاء
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            radius = Radius.Hero,
            background = GlassStrong,
            onClick = { latestYear?.let(onOpenYear) }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${latestYear ?: FileUtils.today().first}",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text("آخر تحديث للأرشيف", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    Text(
                        stats.lastImportedAt?.let { FileUtils.relativeTime(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Accent
                    )
                }
                IconTile(size = 72.dp) {
                    Icon(Icons.Filled.Archive, contentDescription = null, tint = Accent, modifier = Modifier.size(36.dp))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(GlassBorder))
            Text(
                listOf(
                    FileUtils.countLabel(stats.totalFiles, "ملف واحد", "ملفان", "ملفات", "ملفًا"),
                    FileUtils.countLabel(stats.albumCount, "شهر واحد", "شهران", "أشهر", "شهرًا"),
                    FileUtils.countLabel(stats.documentCount, "مستند واحد", "مستندان", "مستندات", "مستندًا"),
                    storageText
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        // بلاطتا "الوجوه" و"الصور اليوم"
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Person,
                title = "الوجوه",
                subtitle = "${stats.faceCount} وجهًا محفوظًا",
                onClick = onOpenFaces
            )
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.CameraAlt,
                title = "الصور اليوم",
                subtitle = "${stats.todayCount} جديدة",
                onClick = null
            )
        }

        // لافتة المراجعة: تظهر فقط حين يوجد معلّق، لتكون شاشة «قيد المراجعة» قابلة للوصول من الرئيسية
        if (pendingCount > 0) {
            GlassPanel(modifier = Modifier.fillMaxWidth(), onClick = onOpenPending) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTile(size = 40.dp, circle = true) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("بانتظار مراجعتك", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            FileUtils.countLabel(pendingCount, "ملف واحد لم يُحسم", "ملفان لم يُحسما", "ملفات لم تُحسم", "ملفًا لم يُحسم"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                }
            }
        }

        // لوحة "آخر الصور"
        if (recentImages.isNotEmpty()) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "آخر الصور",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(recentImages, key = { it.id }) { img ->
                        AsyncImage(
                            model = File(img.storedPath),
                            contentDescription = img.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(Radius.Control))
                                .border(1.dp, GlassBorder, RoundedCornerShape(Radius.Control))
                                .clickable { onOpenImage(img) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?
) {
    GlassPanel(modifier = modifier, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(size = 44.dp, circle = true) {
                Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyYearsState(text: String = "لا يوجد أرشيف بعد") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.White.copy(alpha = 0.45f)
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
