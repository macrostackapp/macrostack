package com.macrostack.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensMathTest {

    @Test
    fun labelsMatchTheStockCameraButtons() {
        // S24 Ultra-like focal lengths relative to a 23 mm main camera.
        assertEquals("0.6×", LensMath.label(13f / 23f))
        assertEquals("1×", LensMath.label(1f))
        assertEquals("3×", LensMath.label(67f / 23f))
        assertEquals("5×", LensMath.label(111f / 23f))
        assertEquals("2.5×", LensMath.label(2.5f))
    }

    @Test
    fun zoomRatioSnapsToTheMarketedRatioSoThePhoneSwitchesLens() {
        assertEquals(3f, LensMath.zoomRatioFor(67f / 23f, 0.6f, 100f))
        assertEquals(5f, LensMath.zoomRatioFor(111f / 23f, 0.6f, 100f))
        assertEquals(0.6f, LensMath.zoomRatioFor(13f / 23f, 0.6f, 100f))
    }

    @Test
    fun zoomRatioOutsideRangeIsRejected() {
        assertNull(LensMath.zoomRatioFor(5f, 1f, 4f))
        assertNull(LensMath.zoomRatioFor(0.5f, 1f, 10f))
    }

    @Test
    fun sameLensTolerance() {
        assertTrue(LensMath.sameLens(1f, 1.1f))
        assertFalse(LensMath.sameLens(3f, 5f))
    }
}
