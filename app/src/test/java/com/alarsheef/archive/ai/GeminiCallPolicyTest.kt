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

    @Test
    fun quotaIsCommittedOnlyAfterSuccessfulText() {
        assertTrue(GeminiCallPolicy.commitQuota(httpOk = true, hasText = true))
        assertFalse(GeminiCallPolicy.commitQuota(httpOk = true, hasText = false))
        assertFalse(GeminiCallPolicy.commitQuota(httpOk = false, hasText = true))
    }
}
