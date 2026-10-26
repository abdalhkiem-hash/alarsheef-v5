package com.alarsheef.archive.util

import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

object FileUtils {

    /**
     * امتدادات الصور — تشمل الصيغ الشائعة كي تمرّ كلها من مسار MediaStore
     * الموحّد (بهوية المسار النسبي) بدل أن تقع من SAF بهوية URI ثم تتكرر
     * مع نفس الملف المكتشف عبر MediaStore.
     */
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "tiff", "avif")
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

    /** ساعة بداية يوم العمل: اليوم يبدأ 6 صباحًا وينتهي 6 صباحًا من اليوم التالي. */
    const val DAY_START_HOUR = 6

    /** سنة/شهر/يوم يوم العمل الذي يقع فيه [millis]. ما قبل الساعة 6 صباحًا يُحسب من اليوم السابق. */
    fun dayParts(millis: Long): Triple<Int, Int, Int> {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        if (cal.get(Calendar.HOUR_OF_DAY) < DAY_START_HOUR) cal.add(Calendar.DAY_OF_MONTH, -1)
        return Triple(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun today(): Triple<Int, Int, Int> = dayParts(System.currentTimeMillis())

    /** بداية يوم العمل الحالي (الساعة 6 صباحًا) بالمللي ثانية — معيار الأرشفة اليومية "اليوم بيومه". */
    fun startOfToday(): Long = startOfWorkDay(System.currentTimeMillis())

    fun startOfWorkDay(millis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        if (cal.get(Calendar.HOUR_OF_DAY) < DAY_START_HOUR) cal.add(Calendar.DAY_OF_MONTH, -1)
        cal.set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
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

    /** صيغة عدد عربية صحيحة: 1 مفرد، 2 مثنّى، 3–10 جمع، 11+ مفرد منصوب. */
    fun countLabel(n: Int, one: String, two: String, few: String, many: String = one): String = when {
        n == 1 -> one
        n == 2 -> two
        n in 3..10 -> "$n $few"
        else -> "$n $many"
    }

    /** "الآن"، "منذ ساعتين"، "منذ 3 أيام"... (بالعربية مع المثنّى والجمع). */
    fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - millis).coerceAtLeast(0L)
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        fun unit(n: Long, one: String, two: String, few: String): String = when {
            n == 1L -> one
            n == 2L -> two
            n in 3L..10L -> "$n $few"
            else -> "$n $one"
        }
        return when {
            diff < minute -> "الآن"
            diff < hour -> "منذ " + unit(diff / minute, "دقيقة", "دقيقتين", "دقائق")
            diff < day -> "منذ " + unit(diff / hour, "ساعة", "ساعتين", "ساعات")
            diff < 7 * day -> "منذ " + unit(diff / day, "يوم", "يومين", "أيام")
            diff < 30 * day -> "منذ " + unit(diff / (7 * day), "أسبوع", "أسبوعين", "أسابيع")
            diff < 365 * day -> "منذ " + unit(diff / (30 * day), "شهر", "شهرين", "أشهر")
            else -> "منذ " + unit(diff / (365 * day), "سنة", "سنتين", "سنوات")
        }
    }

    /** يحوّل عدد البايتات إلى نص عربي مقروء مثل "4.2 جيجابايت" أو "830 ميجابايت". */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 بايت"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.ROOT, "%.1f جيجابايت", bytes / gb)
            bytes >= mb -> String.format(Locale.ROOT, "%.0f ميجابايت", bytes / mb)
            bytes >= kb -> String.format(Locale.ROOT, "%.0f كيلوبايت", bytes / kb)
            else -> "$bytes بايت"
        }
    }

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
