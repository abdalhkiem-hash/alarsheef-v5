package com.alarsheef.archive.onedrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * جلسة OneDrive: تسجيل دخول مرة واحدة عبر المتصفح، ثم تجديد الرمز تلقائيًا.
 * الجلسة (رمز التجديد) مشفّرة في Keystore. الصلاحية قراءة الملفات فقط (Files.Read).
 */
object OneDriveAuth {

    private const val TAG = "OneDriveAuth"
    private const val SESSION_FILE = "onedrive_session.bin"
    private const val PENDING_FILE = "onedrive_pending.bin"

    private val refreshLock = Mutex()
    private val _account = MutableStateFlow<String?>(null)

    /** اسم الحساب المربوط (أو null). يتحدث عند الربط والفصل ليتفاعل معه الإعدادات. */
    val account: StateFlow<String?> = _account

    /** يملأ [account] من الجلسة المحفوظة. يُستدعى عند بدء التطبيق. */
    fun restore(context: Context) {
        _account.value = readSession(context)?.optString("account")?.ifBlank { "OneDrive" }
    }

    fun isSignedIn(context: Context): Boolean = readSession(context) != null

    /** يفتح صفحة تسجيل الدخول في المتصفح. يعيد false إن لم يوجد متصفح. */
    fun startSignIn(context: Context): Boolean {
        val verifier = OneDriveProtocol.randomToken(48)
        val state = OneDriveProtocol.randomToken(16)
        SecureFile(context, PENDING_FILE).write(JSONObject().put("verifier", verifier).put("state", state).toString())
        val url = OneDriveProtocol.authorizeUrl(OneDriveProtocol.challengeOf(verifier), state)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun isRedirect(uri: Uri?): Boolean =
        uri != null && uri.scheme == OneDriveProtocol.REDIRECT_SCHEME && uri.host == OneDriveProtocol.REDIRECT_HOST

    /** يكمل الدخول من عنوان العودة. يعيد null عند النجاح أو رسالة الخطأ. */
    suspend fun completeSignIn(context: Context, redirect: Uri): String? = withContext(Dispatchers.IO) {
        val pendingStore = SecureFile(context, PENDING_FILE)
        val pending = pendingStore.read()?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return@withContext "لم يبدأ تسجيل دخول، أعد المحاولة"
        pendingStore.delete()
        val query = redirect.queryParameterNames.associateWith { redirect.getQueryParameter(it) }
        when (val parsed = OneDriveProtocol.parseRedirect(query, pending.optString("state"))) {
            is OneDriveProtocol.Redirect.Failure -> "تعذّر الدخول: ${parsed.reason}"
            is OneDriveProtocol.Redirect.Code -> try {
                val tokens = postForTokens(OneDriveProtocol.codeExchangeBody(parsed.code, pending.optString("verifier")))
                    ?: return@withContext "رفض الخادم رمز الدخول"
                val refresh = tokens.refreshToken ?: return@withContext "لم يُرجع الخادم صلاحية الاستمرار"
                val label = fetchAccountLabel(tokens.accessToken)
                writeSession(context, refresh, tokens, label)
                _account.value = label
                null
            } catch (e: IOException) {
                "تعذّر الاتصال بالإنترنت"
            }
        }
    }

    /** رمز وصول صالح، أو null إن لم يكن الحساب مربوطًا أو فشل التجديد. */
    suspend fun accessToken(context: Context): String? = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val session = readSession(context) ?: return@withLock null
            val now = System.currentTimeMillis()
            val cached = session.optString("access")
            if (cached.isNotBlank() && OneDriveProtocol.isFresh(session.optLong("expiresAt"), now)) {
                return@withLock cached
            }
            val refresh = session.optString("refresh").takeIf { it.isNotBlank() } ?: return@withLock null
            val tokens = postForTokens(OneDriveProtocol.refreshBody(refresh), refresh) ?: return@withLock null
            writeSession(context, tokens.refreshToken ?: refresh, tokens, session.optString("account"))
            tokens.accessToken
        }
    }

    fun signOut(context: Context) {
        SecureFile(context, SESSION_FILE).delete()
        SecureFile(context, PENDING_FILE).delete()
        _account.value = null
    }

    private fun readSession(context: Context): JSONObject? =
        SecureFile(context, SESSION_FILE).read()?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun writeSession(context: Context, refresh: String, tokens: OneDriveProtocol.Tokens, account: String) {
        SecureFile(context, SESSION_FILE).write(
            JSONObject()
                .put("refresh", refresh)
                .put("access", tokens.accessToken)
                .put("expiresAt", tokens.expiresAtMs)
                .put("account", account)
                .toString()
        )
    }

    /** يعيد null عند رفض الخادم (400/401)، ويرمي IOException عند انقطاع الاتصال. */
    private fun postForTokens(body: String, fallbackRefresh: String? = null): OneDriveProtocol.Tokens? {
        val conn = URL(OneDriveProtocol.TOKEN_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "رفض نقطة الرموز: HTTP $code")
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return OneDriveProtocol.parseTokens(text, System.currentTimeMillis(), fallbackRefresh)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchAccountLabel(accessToken: String): String {
        val conn = URL("https://graph.microsoft.com/v1.0/me?\$select=userPrincipalName,displayName")
            .openConnection() as HttpURLConnection
        return try {
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            if (conn.responseCode !in 200..299) return "OneDrive"
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optString("userPrincipalName").ifBlank { json.optString("displayName") }.ifBlank { "OneDrive" }
        } catch (e: Exception) {
            "OneDrive"
        } finally {
            conn.disconnect()
        }
    }
}
