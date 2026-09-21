package com.alarsheef.archive.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.alarsheef.archive.data.repository.ArchiveRepository

/**
 * يجلب اقتراحات الأسماء الذكية لنطاق تسمية (سنة/شهر/يوم) من نص OCR ووسوم الصور
 * وأسماء مجموعات الوجوه. يُعاد التحميل كلما تغيّر النطاق.
 */
@Composable
fun rememberScopeSuggestions(repository: ArchiveRepository, scopeKey: String?): List<String> {
    var items by remember(scopeKey) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(scopeKey) {
        items = if (scopeKey.isNullOrBlank()) emptyList() else repository.getScopeAiSuggestions(scopeKey)
    }
    return items
}

@Composable
fun ConfirmDeleteDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تأكيد الحذف") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text("حذف نهائيًا", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewFolderDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مجلد جديد") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("اسم المجلد") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) { onSave(name); onDismiss() } }) {
                Text("حفظ")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun CustomLabelDialog(
    currentLabel: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    suggestions: List<String> = emptyList()
) {
    var text by remember { mutableStateOf(currentLabel ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسمية مخصصة") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("مثال: شهر السفر") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                if (suggestions.isNotEmpty()) {
                    Text(
                        "اقتراحات ذكية من محتوى هذا النطاق:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        suggestions.forEach { suggestion ->
                            AssistChip(onClick = { text = suggestion }, label = { Text(suggestion) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()); onDismiss() }) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
