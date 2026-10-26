package com.alarsheef.archive.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCompressorTest {

    @Test
    fun targetSize_capsShortEdgeNotLongEdge() {
        // لقطة شاشة طويلة: الضلع الأقصر 1804 يُحدّ إلى 1600 والارتفاع يتناسب
        assertEquals(1600 to 3548, ImageCompressor.targetSize(1804, 4000))
        // صورة أفقية 3648×2052: الأقصر 2052 -> 1600
        assertEquals(2844 to 1600, ImageCompressor.targetSize(3648, 2052))
    }

    @Test
    fun targetSize_neverEnlarges() {
        assertEquals(800 to 1200, ImageCompressor.targetSize(800, 1200))
        assertEquals(1600 to 1600, ImageCompressor.targetSize(1600, 1600))
    }

    @Test
    fun sampleSize_keepsDecodedShortEdgeAtLeastTarget() {
        assertEquals(1, ImageCompressor.sampleSizeFor(1804, 4000))
        assertEquals(2, ImageCompressor.sampleSizeFor(3200, 4000))
        assertEquals(4, ImageCompressor.sampleSizeFor(6400, 8000))
        assertEquals(1, ImageCompressor.sampleSizeFor(800, 600))
    }

    @Test
    fun worthKeeping_requiresRealSaving() {
        assertTrue(ImageCompressor.worthKeeping(1000, 500))
        assertTrue(ImageCompressor.worthKeeping(1000, 900))
        assertFalse(ImageCompressor.worthKeeping(1000, 950))
        assertFalse(ImageCompressor.worthKeeping(1000, 1200))
        assertFalse(ImageCompressor.worthKeeping(1000, 0))
    }
}
