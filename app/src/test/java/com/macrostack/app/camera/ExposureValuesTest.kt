package com.macrostack.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ExposureValuesTest {

    @Test
    fun formatsShutterSpeeds() {
        assertEquals("1/60", ExposureValues.formatShutter(16_666_667))
        assertEquals("1/100", ExposureValues.formatShutter(10_000_000))
        assertEquals("1/8000", ExposureValues.formatShutter(125_000))
        assertEquals("1/2", ExposureValues.formatShutter(500_000_000))
        assertEquals("1s", ExposureValues.formatShutter(1_000_000_000))
        assertEquals("2s", ExposureValues.formatShutter(2_000_000_000))
        assertEquals("1.5s", ExposureValues.formatShutter(1_500_000_000))
    }

    @Test
    fun nearestPicksClosestStopNotClosestNumber() {
        // 1/45 s is closer in stops to 1/50 than to 1/30.
        assertEquals(20_000_000L, ExposureValues.nearestShutter(listOf(33_333_333L, 20_000_000L), 22_222_222L))
        assertEquals(400, ExposureValues.nearestIso(listOf(100, 200, 400, 800), 450))
        assertEquals(123, ExposureValues.nearestIso(emptyList(), 123))
    }
}
