package com.alarsheef.archive.ui.screens.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.alarsheef.archive.ui.components.CustomLabelDialog
import com.alarsheef.archive.ui.components.DrawerPanel
import com.alarsheef.archive.ui.components.ExportImportPanelContent
import com.alarsheef.archive.ui.components.NewFolderDialog
import com.alarsheef.archive.ui.components.SearchField
import com.alarsheef.archive.ui.components.SettingsPanelContent
import com.alarsheef.archive.ui.components.rememberScopeSuggestions
import com.alarsheef.archive.ui.theme.Teal
import com.alarsheef.archive.util.FileUtils
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

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
    onOpenFaces: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsPrefs = remember { SettingsPreferences(context) }
    val porter = remember { ArchivePorter(context, repository) }

    val years by repository.observeYearRows().collectAsStateWithLifecycle(initialValue = emptyList())
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
    val sheetState = rememberModalBottomSheetState()

    var confirmDeleteYear by remember { mutableStateOf<Int?>(null) }
    var labelDialogScope by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    var pendingTreeTarget by remember { mutableStateOf<String?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

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
                      "import" -> settingsPrefs.setImportFolderUri(uri.toString())
                      "backup" -> settingsPrefs.setBackupFolderUri(uri.toString())
                      "whatsapp_images" -> settingsPrefs.setWhatsappImagesTreeUri(uri.toString())
                      "whatsapp_docs" -> settingsPrefs.setWhatsappDocumentsTreeUri(uri.toString())
                      "downloads" -> settingsPrefs.setDownloadsTreeUri(uri.toString())
                      "import_custom" -> settingsPrefs.setImportFolderUri(uri.toString())
                  }
                snackbarHostState.showSnackbar("تم اختيار المجلد")
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ArsheefDrawerContent { panel ->
                activePanel = panel
                scope.launch { drawerState.close() }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("الأرشيف") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "القائمة", tint = Color.White)
                        }
                    },
                    actions = {
                        // لا يفتح صفحة منفصلة — يركز حقل البحث الظاهر أسفل الشريط
                        IconButton(onClick = { searchFieldFocus.requestFocus() }) {
                            Icon(Icons.Filled.Search, contentDescription = "بحث", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White)
                )
            },
            floatingActionButton = { AddFab(repository = repository, snackbarHostState = snackbarHostState, scope = scope, onAddFolder = { showNewFolderDialog = true }, onAddDay = {}) }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "ابحث في كل الأرشيف...",
                    focusRequester = searchFieldFocus
                )

                if (isSearching) {
                    SearchResultsList(results = searchResults, onOpenResult = onOpenImage)
                } else if (years.isEmpty()) {
                    EmptyYearsState()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(years, key = { it.year }) { row ->
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

    if (activePanel != null) {
        ModalBottomSheet(onDismissRequest = { activePanel = null }, sheetState = sheetState) {
            when (activePanel) {
                 DrawerPanel.SETTINGS -> SettingsPanelContent(
                     prefs = settingsPrefs,
                     scope = scope,
                     onPickImportFolder = {
                         pendingTreeTarget = "import"
                         treeLauncher.launch(null)
                     },
                     onPickWhatsappImages = {
                         pendingTreeTarget = "whatsapp_images"
                         treeLauncher.launch(null)
                     },
                     onPickWhatsappDocs = {
                         pendingTreeTarget = "whatsapp_docs"
                         treeLauncher.launch(null)
                     },
                     onPickDownloads = {
                         pendingTreeTarget = "downloads"
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
                    onImport = { importLauncher.launch(arrayOf("application/zip")) }
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
    }

    confirmDeleteYear?.let { year ->
        ConfirmDeleteDialog(
            message = "هل تريد حذف سنة $year كاملة؟ سيتم حذف كل الأشهر والأيام والملفات بداخلها نهائيًا.",
            onConfirm = { scope.launch { repository.deleteYear(year) } },
            onDismiss = { confirmDeleteYear = null }
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
private fun YearRowCard(
    row: YearRow,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onLabel: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "خيارات", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("تصدير هذه السنة (ZIP)") }, onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("مشاركة كل ملفات السنة") }, onClick = { menuOpen = false; onShare() })
                    DropdownMenuItem(text = { Text("تسمية مخصصة") }, onClick = { menuOpen = false; onLabel() })
                    DropdownMenuItem(text = { Text("حذف", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
                }
            }
            Column(modifier = Modifier.padding(start = 4.dp).weight(1f)) {
                Text(row.label ?: "${row.year}", style = MaterialTheme.typography.titleMedium)
                val subtitle = if (row.label != null) {
                    "${row.year} · ${row.fileCount} ملف"
                } else {
                    "${row.fileCount} ملف · آخر نشاط: ${FileUtils.monthArabicName(row.latestMonth)}"
                }
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Box(
                modifier = Modifier.size(46.dp).background(Teal, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Color.White)
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
            Card(
                onClick = { onOpenResult(img) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(46.dp).background(Teal, RoundedCornerShape(10.dp)),
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
private fun EmptyYearsState(text: String = "لا يوجد أرشيف بعد") {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Teal.copy(alpha = 0.4f)
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        }
    }
}