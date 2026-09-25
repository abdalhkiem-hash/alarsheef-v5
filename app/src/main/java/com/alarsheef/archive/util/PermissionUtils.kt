package com.alarsheef.archive.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * إدارة الصلاحيات بعد إزالة MANAGE_EXTERNAL_STORAGE.
 *
 * الصلاحيات المطلوبة:
 * - أندرويد 13+ (API 33): [READ_MEDIA_IMAGES] + [READ_MEDIA_VIDEO] لاستعلام
 *   MediaStore (الصور فقط — ملفات PDF غير مغطاة هنا وتحتاج SAF).
 * - أندرويد 12L فأقدم: `READ_EXTERNAL_STORAGE` (بحد أقصى 32 في المانيفست) —
 *   تغطي الصور **وملفات PDF** في MediaStore، ويطلبها التطبيق بديلًا عن
 *   READ_MEDIA_* (طلبه على API أقدم يُرفض لأن الصلاحية مجهولة هناك).
 * - SAF: لكل مجلد غير MediaStore (وثائق واتساب، تنزيلات، مخصص) — يختاره
 *   المستخدم من منتقي النظام ويُحفظ معرّف الشجرة تلقائيًا.
 */
object PermissionUtils {

    val mediaPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        ) else arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        )

    fun hasMediaPermission(context: Context): Boolean {
        return mediaPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** تُستخدم في السجل لتحويل نتائج الطلب إلى نص واضح. */
    fun describePermissionsResults(results: Map<String, Boolean>): String =
        results.entries.joinToString(", ") { "${it.key.takeLastWhile { c -> c != '.' }}: ${it.value}" }
}