package com.alarsheef.archive.ui.screens.faces

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.alarsheef.archive.data.dao.FaceGroupRow
import com.alarsheef.archive.data.dao.FaceMemberRow
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.ui.theme.Amber
import com.alarsheef.archive.ui.theme.Teal
import kotlinx.coroutines.launch
import java.io.File

/**
 * شاشة "مجموعات الوجوه": قائمة بالأشخاص المكتشفين تلقائيًا،
 * ومن داخل كل مجموعة قائمة بصوره (صور الوجوه المقصوصة محليًا + مصدر الصورة الأصلية).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacesScreen(
    repository: ArchiveRepository,
    onOpenImage: (year: Int, month: Int, day: Int, imageId: Long) -> Unit,
    onBack: () -> Unit
) {
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    // مجموعة تلتقط حاليًا نافذة تسمية/حذف
    var renameGroup by remember { mutableStateOf<FaceGroupRow?>(null) }
    var deleteGroup by remember { mutableStateOf<FaceGroupRow?>(null) }
    val scope = rememberCoroutineScope()

    val groups by repository.observeFaceGroupRows().collectAsStateWithLifecycle(initialValue = emptyList())

    val currentGroup = selectedGroup
    if (currentGroup == null) {
        GroupsList(
            groups = groups,
            onOpenGroup = { selectedGroup = it },
            onRename = { renameGroup = it },
            onDelete = { deleteGroup = it },
            onBack = onBack
        )
    } else {
        val members by repository.observeFaceMembers(currentGroup).collectAsStateWithLifecycle(initialValue = emptyList())
        MembersList(
            members = members,
            onOpenImage = onOpenImage,
            onBack = { selectedGroup = null }
        )
    }

    renameGroup?.let { grp ->
        RenameGroupDialog(
            currentName = grp.name,
            onSave = { text ->
                renameGroup = null
                scope.launch { repository.renameFaceGroup(grp.groupKey, text.trim().ifBlank { null }) }
            },
            onDismiss = { renameGroup = null }
        )
    }

    deleteGroup?.let { grp ->
        AlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = { Text("حذف مجموعة") },
            text = { Text("هل تريد حذف مجموعة \"${grp.name ?: grp.groupKey}\" وتفكيك صورها من التجميع؟") },
            confirmButton = {
                TextButton(onClick = {
                    deleteGroup = null
                    scope.launch { repository.deleteFaceGroup(grp.groupKey) }
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text("إلغاء") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupsList(
    groups: List<FaceGroupRow>,
    onOpenGroup: (String) -> Unit,
    onRename: (FaceGroupRow) -> Unit,
    onDelete: (FaceGroupRow) -> Unit,
    onBack: () -> Unit
) {
    var totalCovered by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(groups) {
        totalCovered = groups.sumOf { it.memberCount }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مجموعات الوجوه") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                if (groups.isEmpty()) "لم تُرصد وجوه بعد — شغّل التحليل الذكي من القائمة الجانبية."
                else "$totalCovered صورة تحوي وجهًا في ${groups.size} مجموعة.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            if (groups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Face, contentDescription = null, modifier = Modifier.size(56.dp), tint = Teal.copy(alpha = 0.4f))
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(groups, key = { it.groupKey }) { grp ->
                        Card(
                            onClick = { onOpenGroup(grp.groupKey) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverThumb(path = grp.coverCropPath, size = 54)
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(
                                        grp.name ?: "مجموعة ${grp.groupKey}",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${grp.memberCount} صورة",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                IconButton(onClick = { onRename(grp) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "تسمية", tint = Teal)
                                }
                                IconButton(onClick = { onDelete(grp) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersList(
    members: List<FaceMemberRow>,
    onOpenImage: (year: Int, month: Int, day: Int, imageId: Long) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(members.size.coerceAtLeast(1).let { "المجموعة ($it صورة)" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White)
            )
        }
    ) { padding ->
        if (members.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد صور في هذه المجموعة")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(members, key = { it.memberId }) { member ->
                    Card(
                        onClick = { onOpenImage(member.year, member.month, member.day, member.imageId) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoverThumb(path = member.cropPath, size = 46)
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("${member.day}/${member.month}/${member.year}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    File(member.imagePath).name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** صورة الوجه المقصوصة محليًا (أو أيقونة عند غيابها) */
@Composable
private fun CoverThumb(path: String?, size: Int) {
    val cropFile = path?.let { File(it) }?.takeIf { it.exists() }
    if (cropFile == null) {
        Box(
            modifier = Modifier.size(size.dp).background(Amber.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Amber)
        }
    } else {
        AsyncImage(
            model = cropFile,
            contentDescription = "وجه",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp))
        )
    }
}

@Composable
private fun RenameGroupDialog(
    currentName: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسمية المجموعة") },
        text = {
            Column {
                Text("اكتب اسم الشخص ليتحول لاقتراح اسم ذكي عند تسمية صوره.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("اسم الشخص") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}