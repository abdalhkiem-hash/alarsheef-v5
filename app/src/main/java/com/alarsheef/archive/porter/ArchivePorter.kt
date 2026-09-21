package com.alarsheef.archive.porter

import android.content.Context
import android.net.Uri
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed class PorterResult {
    data class Success(val count: Int) : PorterResult()
    data object Failure : PorterResult()
}

/**
 * تصدير الأرشيف (كاملًا أو بمدى سنة/شهر/يوم) إلى ملف ZIP،
 * واستيراد أرشيف سابق من ملف ZIP مع إعادة بناء الهيكلة وقاعدة البيانات.
 *
 * كل ملف يُخزَّن بمساره الهيكلي files/{سنة}/{شهر}/{يوم}/{name} مع ملف
 * metadata.json يحمل المعلومات الزمنية والمصدر لكل إدخال، حتى تُستعاد
 * التواريخ الأصلية لا تواريخ يوم الاستيراد.
 */
class ArchivePorter(
    private val context: Context,
    private val repository: ArchiveRepository
) {

    suspend fun exportTo(destination: Uri, images: List<ArchivedImage>): PorterResult =
        withContext(Dispatchers.IO) {
            try {
                val output = context.contentResolver.openOutputStream(destination)
                    ?: return@withContext PorterResult.Failure

                output.use { stream ->
                    ZipOutputStream(stream).use { zip ->
                        val metadata = JSONArray()

                        images.forEach { image ->
                            val file = File(image.storedPath)
                            if (!file.exists()) return@forEach

                            val entryName = "files/${image.year}/${image.month}/${image.day}/${image.fileName}"
                            zip.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()

                            metadata.put(
                                JSONObject()
                                    .put("entry", entryName)
                                    .put("fileName", image.fileName)
                                    .put("contentHash", image.contentHash)
                                    .put("sourceApp", image.sourceApp.name)
                                    .put("year", image.year)
                                    .put("month", image.month)
                                    .put("day", image.day)
                                    .put("capturedAt", image.capturedAt)
                            )
                        }

                        zip.putNextEntry(ZipEntry("metadata.json"))
                        zip.write(metadata.toString(2).toByteArray())
                        zip.closeEntry()
                    }
                }
                PorterResult.Success(images.size)
            } catch (e: Exception) {
                PorterResult.Failure
            }
        }

    suspend fun importFrom(source: Uri): PorterResult = withContext(Dispatchers.IO) {
        val tempFiles = mutableListOf<File>()
        val metaByEntry = mutableMapOf<String, JSONObject>()
        var added = 0

        try {
            val input = context.contentResolver.openInputStream(source)
                ?: return@withContext PorterResult.Failure

            input.use { stream ->
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        when {
                            entry.isDirectory -> {}
                            entry.name == "metadata.json" -> {
                                val text = zip.readBytes().toString(Charsets.UTF_8)
                                runCatching {
                                    val arr = JSONArray(text)
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        metaByEntry[obj.optString("entry")] = obj
                                    }
                                }
                            }
                            entry.name.startsWith("files/") -> {
                                val temp = File(
                                    context.cacheDir,
                                    "restore_${System.nanoTime()}_${File(entry.name).name}"
                                )
                                temp.outputStream().use { zip.copyTo(it) }
                                tempFiles += temp
                                val meta = metaByEntry[entry.name]
                                val parts = entry.name.split("/")
                                val year = meta?.optInt("year") ?: parts.getOrNull(1)?.toIntOrNull() ?: 0
                                val month = meta?.optInt("month") ?: parts.getOrNull(2)?.toIntOrNull() ?: 0
                                val day = meta?.optInt("day") ?: parts.getOrNull(3)?.toIntOrNull() ?: 0
                                val capturedAt = meta?.optLong("capturedAt") ?: temp.lastModified()
                                val sourceApp = runCatching {
                                    SourceApp.valueOf(meta?.optString("sourceApp") ?: "")
                                }.getOrDefault(SourceApp.MANUAL_IMPORT)
                                val preferredName = meta?.optString("fileName") ?: parts.lastOrNull()

                                if (year > 0 && month in 1..12 && day in 1..31) {
                                    when (repository.importIntoDate(
                                        sourceFile = temp,
                                        sourceApp = sourceApp,
                                        year = year,
                                        month = month,
                                        day = day,
                                        capturedAt = capturedAt,
                                        preferredName = preferredName
                                    )) {
                                        is ImportResult.Added -> added++
                                        else -> {} // مكرر أو فاشل — يُتجاهل بصمت
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            PorterResult.Success(added)
        } catch (e: Exception) {
            PorterResult.Failure
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }
}