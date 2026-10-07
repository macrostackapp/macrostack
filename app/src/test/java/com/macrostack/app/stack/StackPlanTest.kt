package com.macrostack.app.stack

import org.junit.Assert.assertEquals
import org.junit.Test

class StackPlanTest {

    @Test
    fun distancesRunFromStartToEndInEvenSteps() {
        val d = StackPlan(start = 8f, end = 4f, frames = 5).distances()
        assertEquals(listOf(8f, 7f, 6f, 5f, 4f), d)
    }

    @Test
    fun endIsHitExactly() {
        val plan = StackPlan(start = 0.123f, end = 9.877f, frames = 137)
        val d = plan.distances()
        assertEquals(137, d.size)
        assertEquals(0.123f, d.first())
        assertEquals(9.877f, d.last())
    }

    @Test
    fun stepIsAbsoluteSpacing() {
        assertEquals(0.5f, StackPlan(start = 2f, end = 7f, frames = 11).stepDiopters, 1e-6f)
        assertEquals(0.5f, StackPlan(start = 7f, end = 2f, frames = 11).stepDiopters, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSingleFrame() {
        StackPlan(start = 1f, end = 2f, frames = 1)
    }
}
