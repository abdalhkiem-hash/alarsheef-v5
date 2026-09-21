package com.alarsheef.archive.util

import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

object FileUtils {

    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
    val PDF_EXTENSIONS = setOf("pdf")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isImage(name: String): Boolean = extensionOf(name) in IMAGE_EXTENSIONS
    fun isPdf(name: String): Boolean = extensionOf(name) in PDF_EXTENSIONS

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun dayFolder(rootDir: File, year: Int, month: Int, day: Int): File {
        val monthStr = String.format(Locale.ROOT, "%02d", month)
        val dayStr = String.format(Locale.ROOT, "%02d", day)
        val folder = File(rootDir, "archive/$year/$monthStr/$dayStr")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun uniqueName(baseName: String, ext: String, targetDir: File): String {
        var candidate = "$baseName.$ext"
        var counter = 1
        while (File(targetDir, candidate).exists()) {
            candidate = "${baseName}_$counter.$ext"
            counter++
        }
        return candidate
    }

    fun today(): Triple<Int, Int, Int> {
        val cal = Calendar.getInstance()
        return Triple(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** بداية اليوم الحالي بالمللي ثانية — معيار الأرشفة اليومية "اليوم بيومه". */
    fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun monthArabicName(month: Int): String {
        val names = listOf(
            "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
        )
        return names.getOrElse(month - 1) { "شهر $month" }
    }

    fun twoDigits(value: Int): String = String.format(Locale.ROOT, "%02d", value)

    fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        val formatter = java.text.SimpleDateFormat("hh:mm a", Locale.forLanguageTag("ar"))
        return formatter.format(java.util.Date(millis))
    }

    fun formatDate(millis: Long): String {
        if (millis <= 0) return ""
        val formatter = java.text.SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("ar"))
        return formatter.format(java.util.Date(millis))
    }
}
