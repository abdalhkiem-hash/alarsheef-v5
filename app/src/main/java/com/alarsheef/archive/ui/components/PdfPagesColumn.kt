package com.alarsheef.archive.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.alarsheef.archive.util.PdfPage
import com.alarsheef.archive.util.PdfPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** كل صفحات ملف PDF مؤرشف بالتمرير الرأسي. الصفحات تُرسم عند ظهورها فقط. */
@Composable
fun PdfPagesColumn(file: File, modifier: Modifier = Modifier) {
    val pageCount by produceState(initialValue = -1, file) {
        value = withContext(Dispatchers.IO) { PdfPages.count(file) }
    }
    when {
        pageCount < 0 -> Box(modifier, contentAlignment = Alignment.Center) {}
        pageCount == 0 -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("تعذّر عرض الملف هنا. افتحه بتطبيق PDF من الزر أعلاه.", color = Color.White)
        }
        else -> LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items((0 until pageCount).toList(), key = { it }) { index ->
                AsyncImage(
                    model = PdfPage(file, index),
                    contentDescription = "صفحة ${index + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}
