package com.alarsheef.archive.porter

import android.content.Context
import android.net.Uri
import com.alarsheef.archive.data.entities.ArchivedImage
import com.alarsheef.archive.data.entities.ContentVerified
import com.alarsheef.archive.data.entities.SourceApp
import com.alarsheef.archive.data.repository.ArchiveRepository
import com.alarsheef.archive.data.repository.ImportResult
import com.alarsheef.archive.scanner.ImportRules
import kotlinx.coroutines.CancellationException
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
 *
 * metadata.json يُكتب **أولًا** في الأرشيف، والاستيراد لا يعتمد على موضعه أصلًا
 * (يستخرج كل الملفات ثم يطبّق البيانات) — النسخ القديمة تكتبه آخرًا، فكان
 * الاستيراد يقرأ الملفات قبل بياناتها ويفقد المصدر وتاريخ الالتقاط لكل ملف.
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

                // الملفات الموجودة فعلًا فقط — العدّ والبيانات يطابقان محتوى الأرشيف
                val entries = images.mapNotNull { image ->
                    val file = File(image.storedPath)
                    if (file.exists()) image to file else null
                }

                output.use { stream ->
                    ZipOutputStream(stream).use { zip ->
                        val metadata = JSONArray()
                        entries.forEach { (image, _) ->
                            metadata.put(
                                JSONObject()
                                    .put("entry", entryNameOf(image))
                                    .put("fileName", image.fileName)
                                    .put("contentHash", image.contentHash)
                                    .put("sourceApp", image.sourceApp.name)
                                    .put("year", image.year)
                                    .put("month", image.month)
                                    .put("day", image.day)
                                    .put("capturedAt", image.capturedAt)
                                    .put("contentVerified", image.contentVerified.name)
                                    .put("originalName", image.originalName ?: "")
                                    .put("originalPath", image.originalPath ?: "")
                                    .put("nameLocked", image.nameLocked)
                            )
                        }
                        zip.putNextEntry(ZipEntry(METADATA_ENTRY))
                        zip.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        entries.forEach { (image, file) ->
                            zip.putNextEntry(ZipEntry(entryNameOf(image)))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                PorterResult.Success(entries.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PorterResult.Failure
            }
        }

    suspend fun importFrom(source: Uri): PorterResult = withContext(Dispatchers.IO) {
        // اسم الإدخال → الملف المستخرج مؤقتًا
        val extracted = LinkedHashMap<String, File>()
        val metaByEntry = mutableMapOf<String, JSONObject>()
        var added = 0

        try {
            val input = context.contentResolver.openInputStream(source)
                ?: return@withContext PorterResult.Failure

            // المرحلة 1: استخراج كل شيء (metadata.json قد يكون في أول الأرشيف أو آخره)
            input.use { stream ->
                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        when {
                            entry.isDirectory -> {}
                            entry.name == METADATA_ENTRY -> {
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
                                // اسم مؤقت بالامتداد فقط: لا مسار من الأرشيف يصل إلى القرص
                                val temp = File(
                                    context.cacheDir,
                                    ImportRules.tempFileName("restore", File(entry.name).name, System.nanoTime())
                                )
                                extracted[entry.name] = temp
                                temp.outputStream().use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            // المرحلة 2: الاستيراد ببيانات كل ملف
            for ((entryName, temp) in extracted) {
                val meta = metaByEntry[entryName]
                val parts = entryName.split("/")
                val year = meta?.optInt("year") ?: parts.getOrNull(1)?.toIntOrNull() ?: 0
                val month = meta?.optInt("month") ?: parts.getOrNull(2)?.toIntOrNull() ?: 0
                val day = meta?.optInt("day") ?: parts.getOrNull(3)?.toIntOrNull() ?: 0
                val capturedAt = meta?.optLong("capturedAt") ?: 0L
                val sourceApp = runCatching {
                    SourceApp.valueOf(meta?.optString("sourceApp") ?: "")
                }.getOrDefault(SourceApp.MANUAL_IMPORT)
                val verified = runCatching {
                    ContentVerified.valueOf(meta?.optString("contentVerified") ?: "")
                }.getOrDefault(ContentVerified.UNVERIFIED)
                val preferredName = meta?.optString("fileName")?.takeIf { it.isNotBlank() }
                    ?: parts.lastOrNull()

                if (year > 0 && month in 1..12 && day in 1..31) {
                    val result = repository.importIntoDate(
                        sourceFile = temp,
                        sourceApp = sourceApp,
                        year = year,
                        month = month,
                        day = day,
                        capturedAt = capturedAt,
                        preferredName = preferredName,
                        contentVerified = verified,
                        originalName = meta?.optString("originalName"),
                        originalPath = meta?.optString("originalPath"),
                        nameLocked = meta?.optBoolean("nameLocked") ?: false,
                    )
                    if (result is ImportResult.Added) added++ // مكرر أو فاشل — يُتجاهل بصمت
                }
                // يُحذف فور استيراده حتى لا يتضاعف استهلاك التخزين طوال العملية
                runCatching { temp.delete() }
            }
            PorterResult.Success(added)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PorterResult.Failure
        } finally {
            extracted.values.forEach { runCatching { it.delete() } }
        }
    }

    private fun entryNameOf(image: ArchivedImage): String =
        "files/${image.year}/${image.month}/${image.day}/${image.fileName}"

    private companion object {
        const val METADATA_ENTRY = "metadata.json"
    }
}
