package com.macrostack.app.camera

import com.macrostack.app.fusion.GrayImage
import com.macrostack.app.fusion.RgbImage
import com.macrostack.app.fusion.SyntheticStack
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Peaking must mark edges that are in focus and leave blurred ones alone — checked on a synthetic
 * frame where the exact blur of every pixel is known.
 */
class PeakingTest {

    private class Score(val inFocus: Int, val blurred: Int) {
        /** Of everything highlighted, the share that was actually blurred (lower is better). */
        val falseShare: Double get() = blurred.toDouble() / maxOf(inFocus + blurred, 1)
    }

    @Test
    fun highlightsSharpEdgesNotBlurredOnes() {
        // A noisy frame, focused on the middle of the scene: sharp band in the centre, blur either side.
        val stack = SyntheticStack(frames = 9, maxBlurSigma = 6f, noiseSigma = 3f)
        val k = stack.frames / 2
        val frame = stack.images[k]
        val luma = GrayImage.luma(frame)
        val unit = GrayImage(luma.width, luma.height, FloatArray(luma.data.size) { luma.data[it] / 255f })

        fun score(flag: (Int, Int) -> Boolean): Score {
            var inFocus = 0
            var blurred = 0
            for (y in 4 until frame.height - 4) for (x in 4 until frame.width - 4) {
                if (!flag(x, y)) continue
                val sigma = stack.blurSigmaAt(k, x)
                if (sigma < 0.6f) inFocus++ else if (sigma > 1.5f) blurred++
            }
            return Score(inFocus, blurred)
        }

        // Score distributions (steepness ÷ local range) for real edges, sharp vs blurred.
        val sharpScores = mutableListOf<Float>()
        val blurredScores = mutableListOf<Float>()
        for (y in 4 until frame.height - 4) for (x in 4 until frame.width - 4) {
            var lo = 1f
            var hi = 0f
            for (dy in -3..3) for (dx in -3..3) {
                lo = minOf(lo, unit[x + dx, y + dy])
                hi = maxOf(hi, unit[x + dx, y + dy])
            }
            if (hi - lo < 0.06f) continue
            val gx = (unit[x + 1, y - 1] + 2 * unit[x + 1, y] + unit[x + 1, y + 1] - unit[x - 1, y - 1] - 2 * unit[x - 1, y] - unit[x - 1, y + 1]) / 8f
            val gy = (unit[x - 1, y + 1] + 2 * unit[x, y + 1] + unit[x + 1, y + 1] - unit[x - 1, y - 1] - 2 * unit[x, y - 1] - unit[x + 1, y - 1]) / 8f
            val score = kotlin.math.hypot(gx, gy) / (hi - lo)
            val sigma = stack.blurSigmaAt(k, x)
            if (sigma < 0.6f) sharpScores += score else if (sigma > 1.5f) blurredScores += score
        }
        fun pct(list: List<Float>, p: Double) = list.sorted()[((list.size - 1) * p).toInt()]
        for (t in listOf(0.22f, 0.25f, 0.28f, 0.30f, 0.32f)) {
            val s = sharpScores.count { it > t }
            val b = blurredScores.count { it > t }
            println("full 7×7 range, threshold %.2f: %d sharp, %d blurred → %.0f%% wrong".format(t, s, b, 100.0 * b / maxOf(s + b, 1)))
        }
        println("score percentiles sharp  (50/75/90/95/99): " + listOf(0.5, 0.75, 0.9, 0.95, 0.99).joinToString { "%.3f".format(pct(sharpScores, it)) })
        println("score percentiles blurred (50/75/90/95/99): " + listOf(0.5, 0.75, 0.9, 0.95, 0.99).joinToString { "%.3f".format(pct(blurredScores, it)) })

        // What the app used to do: any steep brightness change.
        val old = score { x, y ->
            val gx = luma[x + 1, y] - luma[x - 1, y]
            val gy = luma[x, y + 1] - luma[x, y - 1]
            abs(gx) + abs(gy) > 40
        }
        println("old peaking: ${old.inFocus} sharp, ${old.blurred} blurred highlighted → %.0f%% wrong".format(old.falseShare * 100))

        for (level in Peaking.LEVEL_NAMES.indices) {
            val s = score { x, y -> Peaking.isSharp(unit, x, y, Peaking.SHARPNESS[level], Peaking.MIN_CONTRAST[level]) }
            println("new peaking, ${Peaking.LEVEL_NAMES[level]}: ${s.inFocus} sharp, ${s.blurred} blurred highlighted → %.0f%% wrong".format(s.falseShare * 100))
            val allowed = doubleArrayOf(0.05, 0.10, 0.20)[level]
            assertTrue("${Peaking.LEVEL_NAMES[level]}: too many blurred highlights (${s.falseShare})", s.falseShare < allowed)
            assertTrue("${Peaking.LEVEL_NAMES[level]}: should still find the sharp edges", s.inFocus > old.inFocus / 4)
        }
        assertTrue("the old method really was wrong a lot", old.falseShare > 0.3)

        // Picture for a visual check: highlighted pixels in red.
        val level = 1
        val marked = IntArray(frame.pixels.size) { i ->
            val x = i % frame.width
            val y = i / frame.width
            if (x in 4 until frame.width - 4 && y in 4 until frame.height - 4 &&
                Peaking.isSharp(unit, x, y, Peaking.SHARPNESS[level], Peaking.MIN_CONTRAST[level])
            ) 0xFFFF3030.toInt() else frame.pixels[i]
        }
        SyntheticStack.save(RgbImage(frame.width, frame.height, marked), "peaking_new")
        val oldMarked = IntArray(frame.pixels.size) { i ->
            val x = i % frame.width
            val y = i / frame.width
            if (x in 1 until frame.width - 1 && y in 1 until frame.height - 1 &&
                abs(luma[x + 1, y] - luma[x - 1, y]) + abs(luma[x, y + 1] - luma[x, y - 1]) > 40
            ) 0xFFFF3030.toInt() else frame.pixels[i]
        }
        SyntheticStack.save(RgbImage(frame.width, frame.height, oldMarked), "peaking_old")
    }
}
