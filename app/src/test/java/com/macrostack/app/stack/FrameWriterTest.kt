package com.macrostack.app.stack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class FrameWriterTest {

    @Test
    fun writesEveryFrameEvenWhenTheQueueOverflows() {
        val written = Collections.synchronizedSet(mutableSetOf<Int>())
        val ok = AtomicInteger()
        val writer = FrameWriter(queueCapacity = 2) { error -> if (error == null) ok.incrementAndGet() }

        repeat(50) { index ->
            writer.submit(index) {
                Thread.sleep(5)
                written += index
            }
        }

        assertTrue(writer.finish(10_000))
        assertEquals((0 until 50).toSet(), written.toSet())
        assertEquals(50, ok.get())
    }

    @Test
    fun reportsFailedSaves() {
        val failed = AtomicInteger()
        val writer = FrameWriter(queueCapacity = 4) { error -> if (error != null) failed.incrementAndGet() }

        repeat(10) { index ->
            writer.submit(index) { if (index % 2 == 0) throw java.io.IOException("disk full") }
        }

        assertTrue(writer.finish(5_000))
        assertEquals(5, failed.get())
    }

    @Test
    fun lateFramesStillRunAfterFinish() {
        // A RAW frame arriving after the stack ended must still be handled, or its camera buffer leaks.
        val ran = AtomicInteger()
        val writer = FrameWriter(queueCapacity = 2) { }
        writer.finish(1_000)
        writer.submit(0) { ran.incrementAndGet() }
        assertEquals(1, ran.get())
    }

    @Test
    fun capacityKeepsMemoryBounded() {
        assertEquals(16, FrameWriter.capacityFor(4000, 3000)) // 12 MP ≈ 6 MB each, 96 MB budget
        assertEquals(4, FrameWriter.capacityFor(16320, 12240)) // 200 MP → minimum
        assertEquals(48, FrameWriter.capacityFor(640, 480)) // tiny → maximum
    }
}
