package com.alarsheef.archive.data.entities

import androidx.room.Entity

/**
 * تسمية مخصصة يضيفها المستخدم لنطاق سنة/شهر/يوم عبر قائمة ⋮.
 * scopeKey صيغته: "y-2026" أو "m-2026-9" أو "d-2026-9-5".
 */
@Entity(tableName = "custom_labels", primaryKeys = ["scopeKey"])
data class CustomLabel(
    val scopeKey: String,
    val label: String
)
