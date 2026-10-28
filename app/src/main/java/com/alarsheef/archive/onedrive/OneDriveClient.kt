package com.alarsheef.archive.onedrive

import android.net.Uri
import com.alarsheef.archive.scanner.ImportRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** قراءة مجلدات OneDrive وتنزيل الملفات عبر Microsoft Graph. قراءة فقط. */
class OneDriveClient(private val token: suspend () -> String?) {

    data class Item(val id: String, val name: String, val isFolder: Boolean)

    /** أبناء مسار (مثل `2026/الارشيف`) أو null إن لم يوجد المسار. */
    suspend fun childrenOfPath(path: String): List<Item>? {
        val clean = path.trim('/').split('/').filter { it.isNotBlank() }
        val url = if (clean.isEmpty()) "$GRAPH/me/drive/root/children"
        else "$GRAPH/me/drive/root:/${clean.joinToString("/") { Uri.encode(it) }}:/children"
        return list(withQuery(url))
    }

    suspend fun children(itemId: String): List<Item> =
        list(withQuery("$GRAPH/me/drive/items/${Uri.encode(itemId)}/children")) ?: emptyList()

    /** ينزّل الملف إلى [target]. يعيد false إن لم يتوفر رابط التنزيل. */
    suspend fun downloadTo(itemId: String, target: File): Boolean = withContext(Dispatchers.IO) {
        val meta = getJson("$GRAPH/me/drive/items/${Uri.encode(itemId)}?\$select=id,@microsoft.graph.downloadUrl")
            ?: return@withContext false
        val url = meta.optString("@microsoft.graph.downloadUrl").takeIf { it.isNotBlank() }
            ?: return@withContext false
        // رابط التنزيل موقَّع مسبقًا: لا يُرسَل إليه رمز الدخول
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            target.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
            true
        } finally {
            conn.disconnect()
        }
    }

    /**
     * يبحث تحت [basePath] عن مجلد باسم [folderName] بعرض المستويات، متجاوزًا المجلدات
     * التي تحمل سنة غير [year]. السقف [MAX_LISTINGS] يحمي من شجرة كبيرة.
     */
    suspend fun findFolder(basePath: String, folderName: String, year: Int): Item? {
        var level = (childrenOfPath(basePath) ?: return null)
        var listings = 1
        var depth = 0
        while (level.isNotEmpty() && depth <= MAX_DEPTH) {
            val folders = level.filter { it.isFolder }
            folders.firstOrNull { it.name == folderName }?.let { return it }
            val next = ArrayList<Item>()
            for (folder in folders) {
                if (!ImportRules.shouldVisitDailySearchFolder(folder.name, year)) continue
                if (listings >= MAX_LISTINGS) return null
                listings++
                next.addAll(children(folder.id))
            }
            level = next
            depth++
        }
        return null
    }

    private fun withQuery(url: String) = "$url${if ('?' in url) "&" else "?"}\$select=id,name,folder,file&\$top=200"

    private suspend fun list(firstUrl: String): List<Item>? = withContext(Dispatchers.IO) {
        val items = ArrayList<Item>()
        var url: String? = firstUrl
        while (url != null) {
            val json = getJson(url) ?: return@withContext null
            val array = json.optJSONArray("value")
            for (i in 0 until (array?.length() ?: 0)) {
                val o = array!!.getJSONObject(i)
                items.add(Item(o.getString("id"), o.optString("name"), o.has("folder")))
            }
            url = json.optString("@odata.nextLink").takeIf { it.isNotBlank() }
        }
        items
    }

    /** JSON الرد، أو null عند 404. 401 وغيره يرمي IOException (يُعاد المحاولة لاحقًا). */
    private suspend fun getJson(url: String): JSONObject? {
        val bearer = token() ?: throw IOException("OneDrive غير مربوط")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        const val MAX_DEPTH = 4
        const val MAX_LISTINGS = 40
    }
}
