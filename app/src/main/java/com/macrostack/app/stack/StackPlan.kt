package com.macrostack.app.stack

import kotlin.math.abs

/**
 * Which focus distances to shoot, from [start] to [end] (diopters), in [frames] even steps.
 *
 * Even steps in diopters are close to even steps in depth: with a clip-on macro lens of power P the
 * in-focus plane sits at 1 / (P + d), and since P is much larger than the phone's focus range, that
 * is nearly linear in d. It is also roughly linear in focus-motor travel.
 */
data class StackPlan(val start: Float, val end: Float, val frames: Int) {

    init {
        require(frames >= 2) { "A stack needs at least 2 frames" }
    }

    fun distances(): List<Float> = List(frames) { i ->
        if (i == frames - 1) end else start + (end - start) * i / (frames - 1)
    }

    val stepDiopters: Float get() = abs(end - start) / (frames - 1)
}
