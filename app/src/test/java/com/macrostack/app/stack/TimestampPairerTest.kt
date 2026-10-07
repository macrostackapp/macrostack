package com.macrostack.app.stack

import org.junit.Assert.assertEquals
import org.junit.Test

class TimestampPairerTest {

    private val pairs = mutableListOf<Pair<String, Int>>()
    private val discarded = mutableListOf<String>()
    private val pairer = TimestampPairer<String, Int>(
        onPair = { image, meta -> pairs += image to meta },
        onDiscard = { image -> discarded += image },
    )

    @Test
    fun pairsInEitherOrder() {
        pairer.addFirst(100, "raw-a")
        pairer.addSecond(100, 1)
        pairer.addSecond(200, 2)
        pairer.addFirst(200, "raw-b")
        assertEquals(listOf("raw-a" to 1, "raw-b" to 2), pairs)
        assertEquals(emptyList<String>(), discarded)
    }

    @Test
    fun droppedFrameIsReleasedWhetherItArrivedYetOrNot() {
        pairer.addFirst(100, "early")
        pairer.drop(100)
        pairer.drop(200)
        pairer.addFirst(200, "late")
        assertEquals(listOf("early", "late"), discarded)
        assertEquals(emptyList<Pair<String, Int>>(), pairs)
    }

    @Test
    fun discardAllReleasesUnpairedImages() {
        pairer.addFirst(100, "orphan")
        pairer.addSecond(300, 3)
        pairer.discardAll()
        assertEquals(listOf("orphan"), discarded)
        pairer.addFirst(300, "after-reset")
        assertEquals(emptyList<Pair<String, Int>>(), pairs) // stale metadata was cleared too
    }
}
