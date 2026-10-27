package com.alarsheef.archive.ai

import com.alarsheef.archive.ai.GeminiHealth.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiHealthTest {

    @Test
    fun classify_successCodes() {
        assertEquals(Status.OK, GeminiHealth.classify(200, false))
        assertEquals(Status.OK, GeminiHealth.classify(204, false))
    }

    @Test
    fun classify_invalidKeyCodes() {
        assertEquals(Status.KEY_REJECTED, GeminiHealth.classify(400, false))
        assertEquals(Status.KEY_REJECTED, GeminiHealth.classify(401, false))
        assertEquals(Status.KEY_REJECTED, GeminiHealth.classify(403, false))
    }

    @Test
    fun classify_modelAndLimits() {
        assertEquals(Status.MODEL_NOT_FOUND, GeminiHealth.classify(404, false))
        assertEquals(Status.RATE_LIMITED, GeminiHealth.classify(429, false))
        assertEquals(Status.UNAVAILABLE, GeminiHealth.classify(503, false))
        assertEquals(Status.UNAVAILABLE, GeminiHealth.classify(500, false))
    }

    @Test
    fun classify_networkWinsOverCode() {
        assertEquals(Status.NETWORK, GeminiHealth.classify(null, true))
        assertEquals(Status.NETWORK, GeminiHealth.classify(200, true))
    }

    @Test
    fun classify_unknown() {
        assertEquals(Status.OTHER, GeminiHealth.classify(null, false))
        assertEquals(Status.OTHER, GeminiHealth.classify(302, false))
    }

    @Test
    fun onlyOkIsOk() {
        assertTrue(GeminiHealth.isOk(Status.OK))
        Status.values().filter { it != Status.OK }.forEach { assertFalse(it.name, GeminiHealth.isOk(it)) }
    }

    @Test
    fun everyStatusHasAMessage() {
        Status.values().forEach { assertTrue(it.name, GeminiHealth.message(it).isNotBlank()) }
    }
}
