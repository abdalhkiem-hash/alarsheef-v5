package com.alarsheef.archive.onedrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** بروتوكول تسجيل الدخول: PKCE وعنوان التفويض وقراءة العودة والرموز. */
class OneDriveProtocolTest {

    @Test
    fun pkceChallenge_matchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OneDriveProtocol.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun randomTokens_areUrlSafeAndDistinct() {
        val a = OneDriveProtocol.randomToken()
        val b = OneDriveProtocol.randomToken()
        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun authorizeUrl_carriesTenantClientRedirectAndReadOnlyScope() {
        val url = OneDriveProtocol.authorizeUrl("CHAL", "ST")
        assertTrue(url.startsWith("https://login.microsoftonline.com/${OneDriveProtocol.TENANT_ID}/oauth2/v2.0/authorize?"))
        assertTrue(url.contains("client_id=${OneDriveProtocol.CLIENT_ID}"))
        assertTrue(url.contains("redirect_uri=com.alarsheef.archive%3A%2F%2Fauth"))
        assertTrue(url.contains("code_challenge=CHAL"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=ST"))
        assertTrue(url.contains("Files.Read"))
        assertFalse(url.contains("ReadWrite"))
    }

    @Test
    fun parseRedirect_acceptsMatchingStateOnly() {
        assertEquals(
            OneDriveProtocol.Redirect.Code("abc"),
            OneDriveProtocol.parseRedirect(mapOf("code" to "abc", "state" to "S"), "S"),
        )
        assertTrue(
            OneDriveProtocol.parseRedirect(mapOf("code" to "abc", "state" to "X"), "S")
                is OneDriveProtocol.Redirect.Failure
        )
        assertTrue(
            OneDriveProtocol.parseRedirect(mapOf("state" to "S"), "S") is OneDriveProtocol.Redirect.Failure
        )
    }

    @Test
    fun parseRedirect_reportsServerError() {
        val r = OneDriveProtocol.parseRedirect(
            mapOf("error" to "access_denied", "error_description" to "رفض المستخدم"), "S"
        )
        assertEquals(OneDriveProtocol.Redirect.Failure("رفض المستخدم"), r)
    }

    @Test
    fun parseTokens_readsAccessRefreshAndExpiry() {
        val t = OneDriveProtocol.parseTokens(
            """{"access_token":"AT","refresh_token":"RT","expires_in":3600}""", nowMs = 1_000L
        )!!
        assertEquals("AT", t.accessToken)
        assertEquals("RT", t.refreshToken)
        assertEquals(1_000L + 3_600_000L, t.expiresAtMs)
    }

    @Test
    fun parseTokens_keepsOldRefreshTokenWhenServerOmitsIt() {
        val t = OneDriveProtocol.parseTokens("""{"access_token":"AT","expires_in":60}""", 0L, "OLD")!!
        assertEquals("OLD", t.refreshToken)
    }

    @Test
    fun parseTokens_rejectsGarbage() {
        assertNull(OneDriveProtocol.parseTokens("not json", 0L))
        assertNull(OneDriveProtocol.parseTokens("""{"error":"invalid_grant"}""", 0L))
    }

    @Test
    fun isFresh_needsMoreThanTwoMinutesLeft() {
        assertTrue(OneDriveProtocol.isFresh(expiresAtMs = 10 * 60_000L, nowMs = 0L))
        assertFalse(OneDriveProtocol.isFresh(expiresAtMs = 60_000L, nowMs = 0L))
    }
}
