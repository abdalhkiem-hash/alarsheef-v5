package com.alarsheef.archive.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceGroupingEngineHashTest {

    @Test
    fun hammingDistance_identicalHashesIsZero() {
        assertEquals(0, FaceGroupingEngine.hammingDistance(0L, 0L))
        assertEquals(0, FaceGroupingEngine.hammingDistance(123456789L, 123456789L))
    }

    @Test
    fun hammingDistance_countsDifferingBits() {
        // 1010 xor 0011 = 1001 => بتان مختلفان
        assertEquals(2, FaceGroupingEngine.hammingDistance(0b1010L, 0b0011L))
    }

    @Test
    fun hammingDistance_isSymmetric() {
        val a = 0x0F0F0F0F0F0F0F0FL
        val b = 0x00FF00FF00FF00FFL
        assertEquals(FaceGroupingEngine.hammingDistance(a, b), FaceGroupingEngine.hammingDistance(b, a))
    }

    @Test
    fun hashToHex_isAlwaysSixteenChars() {
        assertEquals("0000000000000000", FaceGroupingEngine.hashToHex(0L))
        assertEquals(16, FaceGroupingEngine.hashToHex(Long.MAX_VALUE).length)
    }

    @Test
    fun hashToHex_roundTripsThroughUnsignedParse() {
        val hash = 0xFEDCBA9876543210uL.toLong()
        val hex = FaceGroupingEngine.hashToHex(hash)
        assertEquals(hash, java.lang.Long.parseUnsignedLong(hex, 16))
    }

    @Test
    fun consecutiveHashesAreClose_enoughToMatchThreshold() {
        // بصمتان تختلفان بت واحد فقط يجب أن تقعا ضمن عتبة المطابقة
        val base = 0xAAAAAAAAAAAAAAAAuL.toLong()
        val oneBitOff = base xor 0x1L
        assertTrue(FaceGroupingEngine.hammingDistance(base, oneBitOff) <= FaceGroupingEngine.MATCH_THRESHOLD)
    }
}