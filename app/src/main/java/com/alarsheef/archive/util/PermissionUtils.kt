package com.alarsheef.archive.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * إدارة الصلاحيات بعد إزالة MANAGE_EXTERNAL_STORAGE.
 *
 * الصلاحيات المطلوبة:
 * - [READ_MEDIA_IMAGES] + [READ_MEDIA_VIDEO]: لاستعلام MediaStore (معرض الصور).
 * - SAF: لكل مجلد غير MediaStore (واتساب، تنزيلات، مخصص) — يختاره المستخدم من
 *   منتقي النظام ويُحفظ معرّف الشجرة تلقائيًا.
 */
object PermissionUtils {

    val mediaPermissions = arrayOf(
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
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