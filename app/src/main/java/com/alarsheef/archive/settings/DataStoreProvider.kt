package com.alarsheef.archive.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.appDataStore by preferencesDataStore(name = "arsheef_settings")
