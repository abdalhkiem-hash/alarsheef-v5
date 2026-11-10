package com.alarsheef.archive.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrFallbackPolicyTest {

    private fun next(streak: Int, samsung: Int, other: Int) =
        OcrFallbackPolicy.nextStreak(streak, samsung, other, minChars = 3, strongChars = 40)

    @Test
    fun emptySamsungWhileAnotherEngineReads_countsAgainstSamsung() {
        assertEquals(1, next(0, samsung = 0, other = 300))
        assertEquals(3, next(2, samsung = 0, other = 2652))
    }

    @Test
    fun blankImage_isNotHeldAgainstSamsung() {
        assertEquals(2, next(2, samsung = 0, other = 0))
        assertEquals(2, next(2, samsung = 0, other = 10))
    }

    @Test
    fun samsungReadingText_resetsTheStreak() {
        assertEquals(0, next(2, samsung = 120, other = 300))
    }

    @Test
    fun givesUpOnlyAfterThreeMisses() {
        assertTrue(OcrFallbackPolicy.shouldTrySamsung(2))
        assertFalse(OcrFallbackPolicy.shouldTrySamsung(3))
    }
}
