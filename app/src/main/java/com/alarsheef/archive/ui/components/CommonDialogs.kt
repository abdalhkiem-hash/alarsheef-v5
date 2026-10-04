package com.alarsheef.archive.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alarsheef.archive.data.repository.ArchiveRepository
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentScannerSaveDialog(
    pageBitmaps: List<android.graphics.Bitmap>,
    onSave: (name: String, category: String, ocrText: String, secure: Boolean) -> Unit,
    onExportPdf: (pageBitmaps: List<android.graphics.Bitmap>, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var docName by remember { mutableStateOf("مستند جديد") }
    var category by remember { mutableStateOf("مستند عام") }
    var ocrText by remember { mutableStateOf("") }
    var secureFolder by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val categories = listOf("مستند عام", "فاتورة", "إيصال أو حوالة", "عقد", "هوية", "ملاحظات")

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        containerColor = DialogTeal,
        shape = RoundedCornerShape(24.dp),
        title = { DialogTitle("حفظ المستند الممسوح") },
        text = {
            Column(Modifier.padding(horizontal = 4.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = docName,
                    onValueChange = { docName = it },
                    label = { Text("اسم الملف", color = Color.White.copy(alpha = 0.7f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = FieldColors()
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("التصنيف", color = Color.White.copy(alpha = 0.7f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = FieldColors(),
                    trailingIcon = { Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Color.White.copy(alpha = 0.7f)) }
                )

                OutlinedTextField(
                    value = ocrText,
                    onValueChange = { ocrText = it },
                    label = { Text("النص المستخرج (OCR)", color = Color.White.copy(alpha = 0.7f)) },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    colors = FieldColors(),
                    maxLines = 6,
                    singleLine = false
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = secureFolder,
                        onCheckedChange = { secureFolder = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color.White,
                            checkmarkColor = DialogTealText,
                            uncheckedColor = Color.White.copy(alpha = 0.7f)
                        )
                    )
                    Text("حفظ في المجلد الآمن 🔒", color = Color.White, fontSize = 14.sp)
                }

                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 12.dp))
                        Text("جاري الحفظ...", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (!loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    androidx.compose.material3.Button(
                        onClick = {
                            loading = true
                            scope.launch {
                                onExportPdf(pageBitmaps, docName.trim().ifBlank { "مستند جديد" })
                                onDismiss()
                            }
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("تصدير PDF", fontWeight = FontWeight.SemiBold)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        ConfirmButton("حفظ المستند ✓", onClick = {
                            loading = true
                            scope.launch {
                                onSave(docName.trim().ifBlank { "مستند جديد" }, category, ocrText.trim(), secureFolder)
                                onDismiss()
                            }
                        })
                    }
                }
            }
        },
        dismissButton = {
            DismissButton("إلغاء", onClick = { if (!loading) onDismiss() })
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MergePagesDialog(
    repository: ArchiveRepository,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    initialYear: Int? = null,
    initialMonth: Int? = null,
    initialDay: Int? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedYear by remember { mutableStateOf(initialYear ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) }
    var selectedMonth by remember { mutableStateOf(initialMonth ?: java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1) }
    var selectedDay by remember { mutableStateOf(initialDay ?: java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)) }
    var loading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var showResult by remember { mutableStateOf(false) }
    val dialogScope = rememberCoroutineScope()

    val years = repository.observeYearRows().collectAsStateWithLifecycle(initialValue = emptyList())
    val months = repository.observeMonthRows(selectedYear).collectAsStateWithLifecycle(initialValue = emptyList())
    val days = repository.observeDayRows(selectedYear, selectedMonth).collectAsStateWithLifecycle(initialValue = emptyList())

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        containerColor = DialogTeal,
        shape = RoundedCornerShape(24.dp),
        title = { DialogTitle("دمج عدة صفحات في PDF") },
        text = {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = selectedYear.toString(),
                        onValueChange = { it.toIntOrNull()?.let { selectedYear = it; selectedMonth = 1; selectedDay = 1 } },
                        label = { Text("السنة", color = Color.White.copy(alpha = 0.7f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors = FieldColors()
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = selectedMonth.toString(),
                        onValueChange = { it.toIntOrNull()?.let { selectedMonth = it; selectedDay = 1 } },
                        label = { Text("الشهر", color = Color.White.copy(alpha = 0.7f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors = FieldColors()
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = selectedDay.toString(),
                        onValueChange = { it.toIntOrNull()?.let { selectedDay = it } },
                        label = { Text("اليوم", color = Color.White.copy(alpha = 0.7f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        colors = FieldColors()
                    )
                }
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 12.dp))
                        Text("جاري إنشاء PDF...", color = Color.White, fontSize = 14.sp)
                    }
                }
                resultMessage?.let { msg ->
                    Text(msg, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (!loading && !showResult) {
                ConfirmButton("إنشاء PDF ومشاركة", onClick = {
                    loading = true
                    dialogScope.launch {
                        val images = repository.byDayForExport(selectedYear, selectedMonth, selectedDay)
                        if (images.isEmpty()) {
                            loading = false
                            resultMessage = "لا توجد صور في هذا اليوم"
                            showResult = true
                        } else {
                            val pdfFile = repository.mergeImagesToPdf(images)
                            loading = false
                            if (pdfFile != null) {
                                val uri = repository.getShareUriForFile(pdfFile)
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "مشاركة PDF"))
                                resultMessage = "تم إنشاء PDF من ${images.size} صورة"
                            } else {
                                resultMessage = "تعذّر إنشاء PDF"
                            }
                            showResult = true
                        }
                    }
                })
            } else if (showResult) {
                ConfirmButton("تم", onClick = { onDismiss() })
            }
        },
        dismissButton = {
            DismissButton("إلغاء", onClick = { if (!loading) onDismiss() })
        }
    )
}