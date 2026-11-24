package com.alarsheef.archive.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * جدولة النسخ الاحتياطي الدوري (يومي/أسبوعي) عبر WorkManager — بنفس أسلوب
 * جدولة الفحص التلقائي. الفترات مضمونة بالكامل من داخل العامل نفسه
 * (يقف بهدوء لو الإعداد أو المجلد غير متوفرين).
 */
object BackupScheduler {

    fun schedule(context: Context, frequency: String) {
        val hours = if (frequency == "weekly") 168L else 24L

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val request = PeriodicWorkRequestBuilder<LocalBackupWorker>(hours, TimeUnit.HOURS)
            .setInitialDelay(delayToEndOfDayMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LocalBackupWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** المدة حتى 23:30 بالتوقيت المحلي (غدًا لو فات الوقت) — نهاية اليوم. */
    internal fun delayToEndOfDayMillis(now: java.util.Calendar = java.util.Calendar.getInstance()): Long {
        val target = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 30)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            if (!after(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }

    /** نسخة احتياطية فورية لمرة واحدة — تُستخدم عند تفعيل الإعداد أول مرة */
    fun runOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocalBackupWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            LocalBackupWorker.UNIQUE_WORK_NAME + "_once",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LocalBackupWorker.UNIQUE_WORK_NAME)
    }
}