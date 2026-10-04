package com.alarsheef.archive.ui.screens.pending

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.settings.SettingsPreferences
import com.alarsheef.archive.ui.components.AddFab
import com.alarsheef.archive.ui.components.SearchField
import com.alarsheef.archive.ui.theme.Glass
import com.alarsheef.archive.ui.theme.GlassBorder
import com.alarsheef.archive.ui.theme.GlassStrong
import com.alarsheef.archive.ui.theme.TealBright
import com.alarsheef.archive.util.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingReviewScreen(
    repository: ArchiveRepository,
    settingsPrefs: SettingsPreferences,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val images by repository.observePendingReviewImages().collectAsStateWithLifecycle(initialValue = emptyList())

    var query by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteImage by remember { mutableStateOf<ArchivedImage?>(null) }

    val filtered = images.filter { query.isBlank() || it.fileName.contains(query) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("مراجعة المعلقة (${filtered.size})") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.background(GlassStrong, RoundedCornerShape(14.dp))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, titleContentColor = MaterialTheme.colorScheme.onBackground, navigationIconContentColor = MaterialTheme.colorScheme.onBackground, actionIconContentColor = MaterialTheme.colorScheme.onBackground)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchField(query = query, onQueryChange = { query = it }, placeholder = "ابحث في المعلقة...")

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = TealBright, modifier = Modifier.size(48.dp))
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 12.dp))
                        Text("لا توجد صور معلقة للمراجعة", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { image ->
                        PendingReviewCard(
                            image = image,
                            repository = repository,
                            snackbarHostState = snackbarHostState,
                            scope = scope,
                            onDelete = { deleteImage = it; showDeleteDialog = true }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        deleteImage?.let { img ->
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false; deleteImage = null },
                title = { Text("تأكيد الحذف") },
                text = { Text("حذف \"${img.fileName}\" نهائيًا؟") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            repository.deleteImages(listOf(img))
                            snackbarHostState.showSnackbar("تم الحذف")
                            showDeleteDialog = false
                            deleteImage = null
                        }
                    }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false; deleteImage = null }) { Text("إلغاء") }
                }
            )
        }
    }
}

@Composable
private fun PendingReviewCard(
    image: ArchivedImage,
    repository: ArchiveRepository,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    onDelete: (ArchivedImage) -> Unit
) {
    val statusText = when (image.contentVerified) {
        com.alarsheef.archive.data.entities.ContentVerified.AMBIGUOUS -> "معلّق للمراجعة"
        com.alarsheef.archive.data.entities.ContentVerified.REJECTED -> "مرفوض نصياً"
        com.alarsheef.archive.data.entities.ContentVerified.ACCEPTED -> "مقبول نصياً"
        else -> "غير متحقق"
    }
    val statusColor = when (image.contentVerified) {
        com.alarsheef.archive.data.entities.ContentVerified.AMBIGUOUS -> MaterialTheme.colorScheme.primary
        com.alarsheef.archive.data.entities.ContentVerified.REJECTED -> MaterialTheme.colorScheme.error
        com.alarsheef.archive.data.entities.ContentVerified.ACCEPTED -> TealBright
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Glass, contentColor = MaterialTheme.colorScheme.onSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = java.io.File(image.storedPath),
                contentDescription = image.fileName,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp))
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(image.fileName, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    Text(FileUtils.formatTime(image.importedAt), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = {
                        scope.launch {
                            repository.updateContentVerified(image.id, com.alarsheef.archive.data.entities.ContentVerified.ACCEPTED)
                            snackbarHostState.showSnackbar("تم القبول")
                        }
                    },
                    modifier = Modifier.background(TealBright, RoundedCornerShape(10.dp)).padding(8.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "قبول", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            repository.updateContentVerified(image.id, com.alarsheef.archive.data.entities.ContentVerified.REJECTED)
                            snackbarHostState.showSnackbar("تم الرفض")
                        }
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp)).padding(8.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "رفض", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}