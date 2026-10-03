package com.alarsheef.archive.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.alarsheef.archive.data.repository.ArchiveRepository

private val DialogTeal = Color(0xF20F766E)
private val DialogTealText = Color(0xFF0F766E)
private val DialogWhite = Color(0xE6FFFFFF)

private fun glassTitle(): Modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
private fun glassBody(): Modifier = Modifier.fillMaxWidth()

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
private fun DialogTitle(text: String) {
    Text(
        text,
        modifier = glassTitle(),
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun DialogText(text: String) {
    Text(
        text,
        modifier = glassBody(),
        color = Color.White.copy(alpha = 0.9f),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ConfirmButton(text: String, contentColor: Color = DialogTealText, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = DialogWhite, contentColor = contentColor),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun DismissButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun ConfirmDeleteDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogTeal,
        shape = RoundedCornerShape(24.dp),
        title = { DialogTitle("تأكيد الحذف") },
        text = { DialogText(message) },
        confirmButton = {
            ConfirmButton("حذف نهائيًا", contentColor = Color(0xFFC62828), onClick = { onConfirm(); onDismiss() })
        },
        dismissButton = {
            DismissButton("إلغاء", onClick = onDismiss)
        }
    )
}

@Composable
fun ConfirmExitDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogTeal,
        shape = RoundedCornerShape(24.dp),
        title = { DialogTitle("الخروج من التطبيق") },
        text = { DialogText("هل تريد الخروج من الأرشيف؟") },
        confirmButton = {
            ConfirmButton("خروج", onClick = onConfirm)
        },
        dismissButton = {
            DismissButton("إلغاء", onClick = onDismiss)
        }
    )
}

@Composable
private fun FieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.6f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
    focusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.5f)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewFolderDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogTeal,
        shape = RoundedCornerShape(24.dp),
        title = { DialogTitle("مجلد جديد") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("اسم المجلد") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = FieldColors()
            )
        },
        confirmButton = {
            ConfirmButton("حفظ", onClick = { if (name.isNotBlank()) { onSave(name); onDismiss() } })
        },
        dismissButton = { DismissButton("إلغاء", onClick = onDismiss) }
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
        containerColor = DialogTeal,
        shape = RoundedCornerShape(24.dp),
        title = { DialogTitle("تسمية مخصصة") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("مثال: شهر السفر") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = FieldColors()
                )
                if (suggestions.isNotEmpty()) {
                    Text(
                        "اقتراحات ذكية من محتوى هذا النطاق:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        suggestions.forEach { suggestion ->
                            AssistChip(
                                onClick = { text = suggestion },
                                label = { Text(suggestion) },
                                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                    containerColor = Color.White.copy(alpha = 0.18f),
                                    labelColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ConfirmButton("حفظ", onClick = { onSave(text.trim()); onDismiss() })
        },
        dismissButton = {
            DismissButton("إلغاء", onClick = onDismiss)
        }
    )
}

/**
 * خيارات الاستيراد من المعرض: قصّ الحواف وتحسين الصورة تلقائيين
 * (الحالتان تُحفظان في DataStore — افتراضيًا مفعّلتان).
 * يُرجَع اختيار المستخدم عبر [onConfirm].
 */
@Composable
fun ImportOptionsDialog(
    initialCrop: Boolean,
    initialEnhance: Boolean,
    onCropChange: (Boolean) -> Unit,
    onEnhanceChange: (Boolean) -> Unit,
    onConfirm: (crop: Boolean, enhance: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var crop by remember { mutableStateOf(initialCrop) }
    var enhance by remember { mutableStateOf(initialEnhance) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogTeal,
        shape = RoundedCornerShape(24.dp),
        title = { DialogTitle("خيارات الاستيراد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ImportOptionRow(
                    checked = crop,
                    title = "قصّ الحواف تلقائيًا",
                    subtitle = "كشف حدود الورقة وتصحيح المنظور",
                    onCheckedChange = { crop = it; onCropChange(it) }
                )
                ImportOptionRow(
                    checked = enhance,
                    title = "تحسين الصورة تلقائيًا",
                    subtitle = "رفع التباين وإزالة الظل — يُحفظ الأصل كما هو",
                    onCheckedChange = { enhance = it; onEnhanceChange(it) }
                )
            }
        },
        confirmButton = {
            ConfirmButton("استيراد", onClick = { onConfirm(crop, enhance); onDismiss() })
        },
        dismissButton = {
            DismissButton("إلغاء", onClick = onDismiss)
        }
    )
}

@Composable
private fun ImportOptionRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Color.White,
                checkmarkColor = DialogTealText,
                uncheckedColor = Color.White.copy(alpha = 0.7f)
            )
        )
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
