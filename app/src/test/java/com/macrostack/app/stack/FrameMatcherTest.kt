package com.macrostack.app.stack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMatcherTest {

    @Test
    fun matchesByTimestamp() {
        val m = FrameMatcher()
        m.submitted(0); m.started(0, 1000)
        m.submitted(1); m.started(1, 2000)
        assertEquals(1, m.onJpeg(2000))
        assertEquals(0, m.onJpeg(1000))
        assertEquals(0, m.outstanding())
    }

    @Test
    fun fallsBackToSubmissionOrderWhenTimestampsDiffer() {
        val m = FrameMatcher()
        m.submitted(0); m.started(0, 1000)
        m.submitted(1); m.started(1, 2000)
        assertEquals(0, m.onJpeg(999_999))
        assertEquals(1, m.onJpeg(888_888))
        assertNull(m.onJpeg(777_777))
    }

    @Test
    fun jpegBeforeCaptureStartedStillMatchesInOrder() {
        val m = FrameMatcher()
        m.submitted(4)
        assertEquals(4, m.onJpeg(5000))
    }

    @Test
    fun failedAttemptIsRetriedUnderSameIndex() {
        val m = FrameMatcher()
        m.submitted(0); m.started(0, 1000)
        m.submitted(1); m.started(1, 2000)
        m.failed(1)
        m.submitted(1); m.started(1, 3000)
        assertEquals(0, m.onJpeg(1000))
        assertEquals(1, m.onJpeg(3000))
        assertEquals(0, m.outstanding())
    }

    @Test
    fun lateJpegFromFailedCaptureDoesNotShiftLaterFrames() {
        val m = FrameMatcher()
        for (i in 0..2) {
            m.submitted(i)
            m.started(i, 1000L * (i + 1))
        }
        assertEquals(0, m.onJpeg(1000)) // proves timestamps work on this phone
        m.failed(1) // camera reported frame 1 failed…
        assertNull(m.onJpeg(2000)) // …but its JPEG turns up anyway: drop it
        assertEquals(2, m.onJpeg(3000)) // frame 2 keeps its own number
    }

    @Test
    fun lostBufferIsDroppedOnce() {
        val m = FrameMatcher()
        m.submitted(0); m.started(0, 1000)
        m.submitted(1); m.started(1, 2000)
        assertTrue(m.lost(0))
        assertFalse(m.lost(0))
        assertEquals(1, m.onJpeg(2000))
    }
}
