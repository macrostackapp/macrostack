package com.macrostack.app.camera

import com.macrostack.app.fusion.GrayImage
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Focus peaking: marks edges that are actually in focus.
 *
 * Steepness alone can't tell sharp from blurred — a blurred edge between dark and light is still
 * steep. So the steepness at a pixel is compared with the brightness range around it (darkest to
 * brightest in the surrounding 7×7 pixels):
 *
 * - a sharp edge, or a sharp hair, makes its whole brightness jump within about one pixel, so its
 *   steepness is about half the range;
 * - a blurred one spreads the same jump over several pixels, so its steepness is a small fraction.
 *
 * That share doesn't depend on how contrasty the edge is, only on how sharp; and unlike comparing
 * steepness at two scales it doesn't cancel out across thin lines. A minimum range keeps noise on
 * flat surfaces from lighting up.
 *
 * Steepness is measured Sobel-style (each difference averaged 1-2-1 over three rows or columns along
 * the edge), which smooths pixel noise without blurring the edge.
 *
 * The preview shader (see [PreviewRenderer]) runs exactly this test on every preview pixel; this
 * Kotlin version is the reference it is tested against.
 */
object Peaking {

    val LEVEL_NAMES = arrayOf("Low", "Medium", "High")

    /**
     * Steepness (per pixel) ÷ local brightness range an edge needs, per sensitivity level. An ideal
     * sharp edge scores 0.5 and real in-focus edges up to about 0.4, while 99 % of blurred edge
     * pixels (σ > 1.5 px) score below 0.25. Measured in PeakingTest: at 0.30 about 6 % of the
     * highlights are blurred, against 49 % for plain steepness peaking.
     */
    val SHARPNESS = floatArrayOf(0.33f, 0.30f, 0.285f)

    /** Minimum local brightness range (0–1), per level, so flat noisy areas never light up. */
    val MIN_CONTRAST = floatArrayOf(0.09f, 0.06f, 0.05f)

    val COLOR_NAMES = arrayOf("Red", "Yellow", "Green", "Cyan", "Magenta", "White")
    val COLORS = intArrayOf(
        0xFFFF3B30.toInt(),
        0xFFFFE600.toInt(),
        0xFF3DFF5A.toInt(),
        0xFF00E5FF.toInt(),
        0xFFFF2BD6.toInt(),
        0xFFFFFFFF.toInt(),
    )

    /** The brightness range is taken over the 7×7 pixels around the edge (radius 3). */
    const val RANGE_RADIUS = 3

    /** The peaking test at ([x], [y]) of [luma] (values 0–1). Mirrors the preview shader. */
    fun isSharp(luma: GrayImage, x: Int, y: Int, sharpness: Float, minContrast: Float): Boolean {
        fun l(dx: Int, dy: Int) = luma[(x + dx).coerceIn(0, luma.width - 1), (y + dy).coerceIn(0, luma.height - 1)]
        val nw = l(-1, -1)
        val n = l(0, -1)
        val ne = l(1, -1)
        val w = l(-1, 0)
        val c = l(0, 0)
        val e = l(1, 0)
        val sw = l(-1, 1)
        val s = l(0, 1)
        val se = l(1, 1)
        val gx = (ne + 2 * e + se - nw - 2 * w - sw) / 8f
        val gy = (sw + 2 * s + se - nw - 2 * n - ne) / 8f
        val steepness = hypot(gx, gy)

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (dy in -RANGE_RADIUS..RANGE_RADIUS) for (dx in -RANGE_RADIUS..RANGE_RADIUS) {
            val v = l(dx, dy)
            lo = min(lo, v)
            hi = max(hi, v)
        }
        val range = hi - lo
        return range > minContrast && steepness > sharpness * range
    }
}
