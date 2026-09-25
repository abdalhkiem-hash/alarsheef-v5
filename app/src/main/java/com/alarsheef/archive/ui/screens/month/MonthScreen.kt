package com.alarsheef.archive.ui.screens.month

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.alarsheef.archive.settings.SettingsPreferences
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.MonthRow
import com.alarsheef.archive.data.repository.SubFolderRow
import com.alarsheef.archive.porter.ArchivePorter
import com.alarsheef.archive.porter.PorterResult
import com.alarsheef.archive.ui.components.AddFab
import com.alarsheef.archive.ui.components.ConfirmDeleteDialog
import com.alarsheef.archive.ui.components.CustomLabelDialog
import com.alarsheef.archive.ui.components.NewFolderDialog
import com.alarsheef.archive.ui.components.SearchField
import com.alarsheef.archive.ui.components.rememberScopeSuggestions
import com.alarsheef.archive.ui.theme.Teal
import com.alarsheef.archive.util.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthScreen(
    repository: ArchiveRepository,
    year: Int,
    onOpenMonth: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val porter = remember { ArchivePorter(context, repository) }
    val months by repository.observeMonthRows(year).collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    var confirmDeleteMonth by remember { mutableStateOf<Int?>(null) }
    var labelDialogScope by remember { mutableStateOf<String?>(null) }
    var pendingExportMonth by remember { mutableStateOf<Int?>(null) }
    var pendingTreeTarget by remember { mutableStateOf<String?>(null) }
    var showSubFolderDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newSubFolderName by remember { mutableStateOf("") }
    var subFolderMonth by remember { mutableStateOf<Int?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val labelSuggestions = rememberScopeSuggestions(repository, labelDialogScope)

    val filtered = months.filter {
        query.isBlank() ||
            FileUtils.monthArabicName(it.month).contains(query) ||
            (it.label?.contains(query) == true)
    }

    fun createSubFolder(month: Int, name: String) {
        if (name.isBlank()) return
        scope.launch {
            repository.createSubFolder(year, month, name)
            newSubFolderName = ""
            showSubFolderDialog = false
            subFolderMonth = null
            snackbarHostState.showSnackbar("تم إنشاء المجلد الفرعي \"$name\"")
        }
    }

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

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val month = pendingExportMonth
        pendingExportMonth = null
        if (uri != null && month != null) {
            scope.launch {
                val images = repository.byMonthForExport(year, month)
                val result = porter.exportTo(uri, images)
                snackbarHostState.showSnackbar(
                    when (result) {
                        is PorterResult.Success -> "تم تصدير ${result.count} ملف"
                        is PorterResult.Failure -> "تعذّر تصدير الشهر"
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
                    "import_custom" -> {
                        val prefs = SettingsPreferences(context)
                        prefs.setImportFolderUri(uri.toString())
                    }
                }
                snackbarHostState.showSnackbar("تم اختيار المجلد")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("أرشيف سنة $year") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White)
            )
        },
        floatingActionButton = { AddFab(repository = repository, snackbarHostState = snackbarHostState, scope = scope, onAddFolder = { showNewFolderDialog = true }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SearchField(query = query, onQueryChange = { query = it }, placeholder = "ابحث في أشهر $year...")

            if (filtered.isEmpty()) {
                EmptyState(text = if (months.isEmpty()) "لا توجد أشهر مؤرشفة بعد" else "لا توجد نتائج")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.month }) { row ->
                        MonthRowCard(
                            row = row,
                            onClick = { onOpenMonth(row.month) },
                            onExport = {
                                pendingExportMonth = row.month
                                exportLauncher.launch("alarsheef-$year-${FileUtils.twoDigits(row.month)}.zip")
                            },
                            onShare = {
                                scope.launch {
                                    val images = repository.byMonthForExport(year, row.month)
                                    shareUris(images.map { repository.getShareUri(it) }, "image/*")
                                }
                            },
                            onLabel = { labelDialogScope = "m-$year-${row.month}" },
                            onDelete = { confirmDeleteMonth = row.month },
                            onAddSubFolder = { subFolderMonth = row.month; showSubFolderDialog = true }
                        )
                    }
                }
            }
        }
    }

    confirmDeleteMonth?.let { month ->
        ConfirmDeleteDialog(
            message = "هل تريد حذف ${FileUtils.monthArabicName(month)} $year كاملاً؟ سيتم حذف كل أيامه وملفاته نهائيًا.",
            onConfirm = { scope.launch { repository.deleteMonth(year, month) } },
            onDismiss = { confirmDeleteMonth = null }
        )
    }

    labelDialogScope?.let { scopeKey ->
        val currentLabel = months.find { "m-$year-${it.month}" == scopeKey }?.label
        CustomLabelDialog(
            currentLabel = currentLabel,
            onSave = { text -> scope.launch { repository.setLabel(scopeKey, text) } },
            onDismiss = { labelDialogScope = null },
            suggestions = labelSuggestions
        )
    }

if (showSubFolderDialog && subFolderMonth != null) {
        AlertDialog(
            onDismissRequest = { showSubFolderDialog = false; subFolderMonth = null },
            title = { Text("مجلد فرعي جديد") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newSubFolderName,
                        onValueChange = { newSubFolderName = it },
                        label = { Text("اسم المجلد الفرعي") },
                        placeholder = { Text("مثال: اجتماعات") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { createSubFolder(subFolderMonth!!, newSubFolderName) }) {
                    Text("إنشاء")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubFolderDialog = false; subFolderMonth = null; newSubFolderName = "" }) {
                    Text("إلغاء") }
            }
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onSave = { name -> if (subFolderMonth != null) scope.launch { repository.createSubFolder(year, subFolderMonth!!, name) } },
            onDismiss = { showNewFolderDialog = false }
        )
    }
}

@Composable
private fun MonthRowCard(
    row: MonthRow,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onLabel: () -> Unit,
    onDelete: () -> Unit,
    onAddSubFolder: () -> Unit
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
                    DropdownMenuItem(text = { Text("تصدير هذا الشهر (ZIP)") }, onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("مشاركة كل ملفات الشهر") }, onClick = { menuOpen = false; onShare() })
                    DropdownMenuItem(text = { Text("تسمية مخصصة") }, onClick = { menuOpen = false; onLabel() })
                    DropdownMenuItem(text = { Text("إضافة مجلد فرعي") }, onClick = { menuOpen = false; onAddSubFolder() })
                    DropdownMenuItem(text = { Text("حذف", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
                }
            }
            Column(modifier = Modifier.padding(start = 4.dp).weight(1f)) {
                Text(row.label ?: FileUtils.monthArabicName(row.month), style = MaterialTheme.typography.titleMedium)
                val subtitle = if (row.label != null) {
                    "${FileUtils.monthArabicName(row.month)} · ${row.fileCount} ملف"
                } else {
                    "${row.fileCount} ملف"
                }
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Box(
                modifier = Modifier.size(46.dp).background(Teal, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CalendarViewDay, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CalendarViewDay,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Teal.copy(alpha = 0.4f)
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
