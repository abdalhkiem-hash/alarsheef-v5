package com.alarsheef.archive.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCallPolicyTest {

    @Test
    fun serviceDownAndNetworkStopTheBatch() {
        assertEquals(GeminiCallPolicy.UNAVAILABLE_COOLDOWN_MS, GeminiCallPolicy.cooldownMs(503, false))
        assertEquals(GeminiCallPolicy.UNAVAILABLE_COOLDOWN_MS, GeminiCallPolicy.cooldownMs(null, true))
        assertEquals(GeminiCallPolicy.RATE_LIMIT_COOLDOWN_MS, GeminiCallPolicy.cooldownMs(429, false))
    }

    @Test
    fun clientErrorDoesNotStopTheBatch() {
        assertEquals(0L, GeminiCallPolicy.cooldownMs(400, false))
        assertEquals(0L, GeminiCallPolicy.cooldownMs(200, false))
        assertEquals(0L, GeminiCallPolicy.cooldownMs(null, false))
    }

    // رد Google الفعلي (24/11/2026) مختصرًا
    private val dailyBody = """
        {"error":{"code":429,"message":"You exceeded your current quota ... limit: 20, model: gemini-3.6-flash,
        description: Generate content requests per day limit exceeded on Free Tier.\nPlease retry in 2h38m0.573373525s.",
        "details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaValue":"20"}]}}
    """.trimIndent()

    @Test
    fun dailyQuota429_isRecognised_minuteLimitIsNot() {
        assertTrue(GeminiCallPolicy.isDailyQuotaExhausted(429, dailyBody))
        assertFalse(GeminiCallPolicy.isDailyQuotaExhausted(429, """{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel"}"""))
        assertFalse(GeminiCallPolicy.isDailyQuotaExhausted(503, dailyBody))
    }

    @Test
    fun retryAfter_parsedFromMessage_orRetryDelay() {
        assertEquals((2 * 3600 + 38 * 60) * 1000L + 573L, GeminiCallPolicy.retryAfterMs(dailyBody))
        assertEquals(42_000L, GeminiCallPolicy.retryAfterMs("""{"retryDelay": "42s"}"""))
        assertEquals(null, GeminiCallPolicy.retryAfterMs("no hint"))
    }

    @Test
    fun quotaDay_followsPacificMidnight_notThePhone() {
        // 2026-11-24 07:00 UTC = 23:00 بتوقيت المحيط الهادئ يوم 23
        val t = java.time.Instant.parse("2026-11-24T07:00:00Z").toEpochMilli()
        assertEquals("2026-11-23", GeminiCallPolicy.quotaDay(t))
        assertEquals(3_600_000L, GeminiCallPolicy.msUntilQuotaReset(t))
    }

    @Test
    fun failover_anyModelFailure_movesToNextModel() {
        val now = java.time.Instant.parse("2026-11-24T07:00:00Z").toEpochMilli()
        fun f(code: Int?, body: String = "", timedOut: Boolean = false, offline: Boolean = false) =
            GeminiCallPolicy.failover(code, body, timedOut, offline, now)

        // الحصة اليومية: يُتخطّى النموذج حتى موعد Google، والتالي يُجرَّب
        assertEquals(GeminiCallPolicy.Failover((2 * 3600 + 38 * 60) * 1000L + 573L, true), f(429, dailyBody))
        // حد الدقيقة، الازدحام، غير موجود، المهلة: كلها تنقل للتالي
        assertEquals(GeminiCallPolicy.Failover(GeminiCallPolicy.MINUTE_LIMIT_SKIP_MS, true), f(429, "{}"))
        assertEquals(GeminiCallPolicy.Failover(GeminiCallPolicy.BUSY_SKIP_MS, true), f(503))
        assertEquals(GeminiCallPolicy.Failover(GeminiCallPolicy.MISSING_MODEL_SKIP_MS, true), f(404))
        assertEquals(GeminiCallPolicy.Failover(GeminiCallPolicy.TIMEOUT_SKIP_MS, true), f(null, timedOut = true))
        // رد 200 فارغ/غير صالح أو 400: التالي فورًا بلا تخطٍّ للنموذج
        assertEquals(GeminiCallPolicy.Failover(0L, true), f(200))
        assertEquals(GeminiCallPolicy.Failover(0L, true), f(400))
    }

    @Test
    fun failover_offlineOrRejectedKey_stopsTheChain() {
        val now = 0L
        assertFalse(GeminiCallPolicy.failover(null, "", timedOut = false, offline = true, nowMillis = now).tryNext)
        assertFalse(GeminiCallPolicy.failover(403, "", timedOut = false, offline = false, nowMillis = now).tryNext)
        assertFalse(GeminiCallPolicy.failover(401, "", timedOut = false, offline = false, nowMillis = now).tryNext)
    }

    @Test
    fun quotaIsCommittedOnlyAfterSuccessfulText() {
        assertTrue(GeminiCallPolicy.commitQuota(httpOk = true, hasText = true))
        assertFalse(GeminiCallPolicy.commitQuota(httpOk = true, hasText = false))
        assertFalse(GeminiCallPolicy.commitQuota(httpOk = false, hasText = true))
    }
}
