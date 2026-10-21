package com.alarsheef.archive.ui.screens.day

import com.alarsheef.archive.ui.components.GlassPanel
import com.alarsheef.archive.ui.components.GlassTopBar
import com.alarsheef.archive.ui.components.GlassEmptyState
import com.alarsheef.archive.ui.components.IconTile
import com.alarsheef.archive.ui.components.CountBadge
import com.alarsheef.archive.ui.theme.Accent
import com.alarsheef.archive.ui.theme.TextMuted
import com.alarsheef.archive.util.FeatureFlags
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.DayGroupRow
import com.alarsheef.archive.data.repository.DayRow
import com.alarsheef.archive.porter.ArchivePorter
import com.alarsheef.archive.porter.PorterResult
import com.alarsheef.archive.ui.components.AddFab
import com.alarsheef.archive.ui.components.ConfirmDeleteDialog
import com.alarsheef.archive.ui.components.CustomLabelDialog
import com.alarsheef.archive.ui.components.NewFolderDialog
import com.alarsheef.archive.ui.components.SearchField
import com.alarsheef.archive.ui.components.rememberScopeSuggestions
import androidx.compose.ui.draw.clip
import com.alarsheef.archive.ui.theme.Glass
import com.alarsheef.archive.ui.theme.GlassBorder
import com.alarsheef.archive.ui.theme.GlassStrong
import com.alarsheef.archive.util.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    repository: ArchiveRepository,
    year: Int,
    month: Int,
    onOpenDay: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val porter = remember { ArchivePorter(context, repository) }
    val days by repository.observeDayRows(year, month).collectAsStateWithLifecycle(initialValue = emptyList())
    val dayGroups by repository.observeDayGroups(year, month).collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    var confirmDeleteDay by remember { mutableStateOf<Int?>(null) }
    var labelDialogScope by remember { mutableStateOf<String?>(null) }
    var pendingExportDay by remember { mutableStateOf<Int?>(null) }
    var showAddDayDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newDayNumber by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val labelSuggestions = rememberScopeSuggestions(repository, labelDialogScope)

    val allDays = (days.map { DayRow(it.day, it.fileCount, it.lastImportedAt, it.label, it.docCount) } +
        dayGroups.map { DayGroupRow(it.id, it.dayNumber) }
            .map { DayRow(it.dayNumber, 0, 0, null) })
        .sortedBy { it.day }
        .distinctBy { it.day }

    val filtered = allDays.filter {
        query.isBlank() ||
            it.day.toString().contains(query) ||
            (it.label?.contains(query) == true)
    }

    fun createDay(numberStr: String) {
        val dayNum = numberStr.trim().toIntOrNull()
        // أيام الشهر الفعلية (لا 31 فبراير)
        val daysInMonth = java.util.Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
            .getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        if (dayNum == null || dayNum < 1 || dayNum > daysInMonth) {
            scope.launch { snackbarHostState.showSnackbar("رقم اليوم يجب أن يكون بين 1 و$daysInMonth") }
            return
        }
        scope.launch {
            val exists = allDays.any { it.day == dayNum } ||
                repository.createDayGroup(year, month, dayNum) == -1L
            newDayNumber = ""
            showAddDayDialog = false
            snackbarHostState.showSnackbar(
                if (exists) "يوم $dayNum/$month/$year موجود مسبقًا" else "تم إنشاء يوم $dayNum/$month/$year"
            )
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

Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "${FileUtils.monthArabicName(month)} $year",
                subtitle = "أيام الشهر التي تحتوي على ملفات",
                onBack = onBack
            )
        },
        floatingActionButton = { AddFab(repository = repository, snackbarHostState = snackbarHostState, scope = scope, onAddFolder = { showNewFolderDialog = true }, onAddDay = { showAddDayDialog = true }, mergePagesYear = year, mergePagesMonth = month, mergePagesDay = 1) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "ابحث في أيام ${FileUtils.monthArabicName(month)}..."
            )

            if (filtered.isEmpty()) {
                EmptyState(text = if (allDays.isEmpty()) "لا توجد أيام مؤرشفة بعد" else "لا توجد نتائج")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.day }) { row ->
                        DayRowCard(
                            row = row,
                            monthName = FileUtils.monthArabicName(month),
                            onClick = { onOpenDay(row.day) },
                            onExport = {
                                pendingExportDay = row.day
                                exportLauncher.launch("alarsheef-$year-${FileUtils.twoDigits(month)}-${FileUtils.twoDigits(row.day)}.zip")
                            },
                            onShare = {
                                scope.launch {
                                    val images = repository.byDayForExport(year, month, row.day)
                                    shareUris(images.map { repository.getShareUri(it) }, "image/*")
                                }
                            },
                            onLabel = { labelDialogScope = "d-$year-$month-${row.day}" },
                            onDelete = { confirmDeleteDay = row.day }
                        )
                    }
                }
            }
        }
    }

    confirmDeleteDay?.let { day ->
        ConfirmDeleteDialog(
            message = "هل تريد حذف يوم $day/$month/$year؟ سيتم حذف كل ملفاته أو إزالة اليوم اليدوي نهائيًا.",
            onConfirm = { scope.launch { repository.deleteDayOrGroup(year, month, day) } },
            onDismiss = { confirmDeleteDay = null }
        )
    }

    labelDialogScope?.let { scopeKey ->
        val currentLabel = allDays.find { "d-$year-$month-${it.day}" == scopeKey }?.label
        CustomLabelDialog(
            currentLabel = currentLabel,
            onSave = { text -> scope.launch { repository.setLabel(scopeKey, text) } },
            onDismiss = { labelDialogScope = null },
            suggestions = labelSuggestions
        )
    }

    if (showAddDayDialog) {
        AlertDialog(
            onDismissRequest = { showAddDayDialog = false; newDayNumber = "" },
            title = { Text("يوم جديد") },
            text = {
                OutlinedTextField(
                    value = newDayNumber,
                    onValueChange = { newDayNumber = it },
                    label = { Text("رقم اليوم") },
                    placeholder = { Text("مثال: 21") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { createDay(newDayNumber) }) {
                    Text("إنشاء")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDayDialog = false; newDayNumber = "" }) {
                    Text("إلغاء")
                }
            }
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
private fun DayRowCard(
    row: DayRow,
    monthName: String,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onLabel: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    GlassPanel(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(size = 56.dp) {
                Text(
                    "${row.day}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Accent
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.label ?: "${row.day} $monthName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val photos = (row.fileCount - row.docCount).coerceAtLeast(0)
                val parts = listOfNotNull(
                    if (row.docCount > 0) FileUtils.countLabel(row.docCount, "مستند واحد", "مستندان", "مستندات", "مستندًا") else null,
                    if (photos > 0) FileUtils.countLabel(photos, "صورة واحدة", "صورتان", "صور", "صورة") else null
                )
                val subtitle = when {
                    row.fileCount == 0 -> "يوم يدوي — لا ملفات بعد"
                    else -> parts.joinToString(" • ")
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            if (row.fileCount > 0) {
                CountBadge("${row.fileCount}")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "خيارات", tint = TextMuted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("تصدير هذا اليوم (ZIP)") }, onClick = { menuOpen = false; onExport() })
                    DropdownMenuItem(text = { Text("مشاركة كل ملفات اليوم") }, onClick = { menuOpen = false; onShare() })
                    DropdownMenuItem(text = { Text("تسمية مخصصة") }, onClick = { menuOpen = false; onLabel() })
                    DropdownMenuItem(text = { Text("حذف", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    GlassEmptyState(
        icon = Icons.Filled.Image,
        title = text,
        subtitle = "اختر يومًا آخر لعرض الملفات المتاحة."
    )
}
