package com.alarsheef.archive.onedrive

import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * منطق تسجيل الدخول إلى Microsoft (OAuth 2.0 مع PKCE) بدوال نقية تُختبر على JVM.
 * لا أسرار هنا: التطبيق «عميل عام» والمعرّفان أدناه علنيان بطبيعتهما.
 */
object OneDriveProtocol {

    const val CLIENT_ID = "f789a648-b9a3-4c84-9fc2-76d72f530607"
    const val TENANT_ID = "07efb44a-c532-429d-859a-4562a05c1944"
    const val REDIRECT_SCHEME = "com.alarsheef.archive"
    const val REDIRECT_HOST = "auth"
    const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"
    const val SCOPE = "offline_access Files.Read User.Read"

    /** مسار الأرشيف الافتراضي؛ {year} تُستبدل بسنة المجلد المطلوب. */
    const val DEFAULT_PATH = "{year}/الارشيف"

    private const val BASE = "https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0"
    const val AUTHORIZE_URL = "$BASE/authorize"
    const val TOKEN_URL = "$BASE/token"

    private val random = SecureRandom()

    /** نص عشوائي آمن للروابط (verifier أو state). */
    fun randomToken(bytes: Int = 32): String {
        val raw = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizeUrl(challenge: String, state: String): String = AUTHORIZE_URL + "?" + form(
        "client_id" to CLIENT_ID,
        "response_type" to "code",
        "redirect_uri" to REDIRECT_URI,
        "response_mode" to "query",
        "scope" to SCOPE,
        "state" to state,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "prompt" to "select_account",
    )

    fun codeExchangeBody(code: String, verifier: String): String = form(
        "client_id" to CLIENT_ID,
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to REDIRECT_URI,
        "code_verifier" to verifier,
        "scope" to SCOPE,
    )

    fun refreshBody(refreshToken: String): String = form(
        "client_id" to CLIENT_ID,
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "scope" to SCOPE,
    )

    /** نتيجة العودة من المتصفح: رمز التفويض أو سبب الفشل. */
    sealed interface Redirect {
        data class Code(val code: String) : Redirect
        data class Failure(val reason: String) : Redirect
    }

    /** يتحقق من state ويقرأ رمز التفويض من عنوان العودة. */
    fun parseRedirect(query: Map<String, String?>, expectedState: String): Redirect {
        val error = query["error"]
        if (!error.isNullOrBlank()) {
            return Redirect.Failure(query["error_description"]?.takeIf { it.isNotBlank() } ?: error)
        }
        if (query["state"] != expectedState) return Redirect.Failure("state_mismatch")
        val code = query["code"]
        return if (code.isNullOrBlank()) Redirect.Failure("no_code") else Redirect.Code(code)
    }

    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long)

    /** يقرأ رد نقطة الرموز. [fallbackRefresh] يُحفظ إن لم يُرجع الرد رمز تجديد جديدًا. */
    fun parseTokens(json: String, nowMs: Long, fallbackRefresh: String? = null): Tokens? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val access = obj.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val refresh = obj.optString("refresh_token").takeIf { it.isNotBlank() } ?: fallbackRefresh
        val lifetimeSec = obj.optLong("expires_in", 3600L)
        return Tokens(access, refresh, nowMs + lifetimeSec * 1000L)
    }

    /** الرمز صالح ما لم يبقَ أقل من دقيقتين على انتهائه. */
    fun isFresh(expiresAtMs: Long, nowMs: Long): Boolean = expiresAtMs - nowMs > 2 * 60_000L

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
