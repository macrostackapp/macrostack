package com.macrostack.app.stack

import org.junit.Assert.assertEquals
import org.junit.Test

class ReshootsTest {

    @Test
    fun aFrameReportedFailedTwiceIsShotAgainOnce() {
        val r = Reshoots(outputs = 2)
        r.failed(18); r.failed(18)
        r.failed(17)
        r.failed(19); r.failed(19)
        assertEquals(listOf(17, 18, 19), r.frames())
    }

    @Test
    fun aFrameIsShotAgainOnlyWhenAllItsFilesAreLost() {
        val r = Reshoots(outputs = 2) // JPEG + RAW
        r.lost(3) // only the JPEG: the RAW still arrives, so a re-shoot would make a duplicate
        r.lost(7); r.lost(7) // both
        assertEquals(listOf(7), r.frames())
        assertEquals(0, r.lostFiles(3))
        assertEquals(2, r.lostFiles(7))
    }

    @Test
    fun withOneOutputALostFileMeansAReshoot() {
        val r = Reshoots(outputs = 1)
        r.lost(5)
        assertEquals(listOf(5), r.frames())
        assertEquals(1, r.lostFiles(5))
    }

    @Test
    fun aFailedFrameThatAlsoLostAFileIsListedOnce() {
        val r = Reshoots(outputs = 2)
        r.lost(4)
        r.failed(4)
        assertEquals(listOf(4), r.frames())
        assertEquals(1, r.lostFiles(4))
    }
}
