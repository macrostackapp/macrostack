package com.macrostack.app.fusion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Laplacian-pyramid focus fusion ("PMax"-style, like Zerene Stacker's PMax or Helicon's Method C),
 * fed one aligned frame at a time.
 *
 * Each frame is split into detail bands, fine to coarse. At every band and position the fused result
 * keeps the coefficient from the frame with the most local detail there — measured on luminance over
 * a small window, so it follows real structure rather than single noisy pixels — taking all three
 * colour channels from that frame. The coarsest band is averaged. Rebuilding the pyramid gives an
 * image that is sharp wherever any frame was.
 *
 * Memory stays constant however many frames are added: about 25 bytes per output pixel.
 *
 * A [Guide] (from a depth map) can weight each frame per region: a frame's detail where it's only
 * noise, or the glow of a blurred object, then can't win over the frame that is really in focus.
 */
class PyramidFuser(
    val width: Int,
    val height: Int,
    private val parallel: Parallel,
    /** Remove noise-level detail (see [shrink0]). Off only to test exact reconstruction. */
    private val denoise: Boolean = true,
) {

    val levels: Int
    private val widths: IntArray
    private val heights: IntArray

    /** Fused detail coefficients for every level below the top: [level][channel], × COEF_SCALE. */
    private val fused: Array<Array<ShortArray>>

    /** Highest selection energy seen so far at each position. */
    private val bestEnergy0: ShortArray
    private val bestEnergyUpper: Array<FloatArray>

    /** Per-frame scratch: |luma detail| summed along each row. */
    private val rowEnergy0: ShortArray
    private val rowEnergyUpper: Array<FloatArray>

    /** The coarsest level is the average of all frames. */
    private val topSum: Array<FloatArray>

    /**
     * Picking the strongest detail also picks the strongest noise wherever nothing is in focus. The
     * noise level is estimated from the least detailed frame (mostly out of focus, so mostly noise),
     * and fused detail weaker than it is removed (a garrote, which leaves strong detail almost as it
     * was) on the two finest levels, where noise lives.
     */
    private var shrink0 = 0f
    private var shrink1 = 0f
    private var noise0 = Float.MAX_VALUE
    private var noise1 = Float.MAX_VALUE
    private val noiseHistogram0 = IntArray(HISTOGRAM_BINS)
    private val noiseHistogram1 = IntArray(HISTOGRAM_BINS)

    var frames = 0
        private set

    /** Per-region weights for the frame being added: one per [cell]-pixel square, [gridWidth] wide. */
    class Guide(val cell: Int, val gridWidth: Int, val weights: FloatArray) {
        fun at(x: Int, y: Int): Float = weights[(y / cell) * gridWidth + x / cell]

        /** Region column of every full-resolution column (saves a division per pixel). */
        internal var columns = IntArray(0)
    }

    /** Σ weight of the frames in [topSum], per position. */
    private val topWeight: FloatArray

    /**
     * The darkest and brightest luma any frame had at each pixel (unsigned bytes). Near a strong edge
     * neighbouring pixels can take their fine detail from different frames, and the mismatched
     * corrections overshoot: specks or a dotted rim darker (or brighter) than any frame was there. The
     * result is kept within this range; a real edge always is, since it comes from one frame. (Leaving
     * out the frames the guide weights low — glow — made the range jump between regions: patches.)
     */
    private val lumaMin = ByteArray(width * height).also { it.fill(-1) }
    private val lumaMax = ByteArray(width * height)

    init {
        require(width >= MIN_SIZE && height >= MIN_SIZE) { "Image too small to stack: ${width}×$height" }
        val ws = mutableListOf(width)
        val hs = mutableListOf(height)
        while (ws.size < MAX_LEVELS && min(ws.last(), hs.last()) >= 2 * MIN_TOP) {
            ws += (ws.last() + 1) / 2
            hs += (hs.last() + 1) / 2
        }
        levels = ws.size
        widths = ws.toIntArray()
        heights = hs.toIntArray()
        fused = Array(levels - 1) { l -> Array(3) { ShortArray(size(l)) } }
        bestEnergy0 = ShortArray(size(0)).also { it.fill(-1) }
        rowEnergy0 = ShortArray(size(0))
        bestEnergyUpper = Array(levels - 2) { FloatArray(size(it + 1)).also { a -> a.fill(-1f) } }
        rowEnergyUpper = Array(levels - 2) { FloatArray(size(it + 1)) }
        topSum = Array(3) { FloatArray(size(levels - 1)) }
        topWeight = FloatArray(size(levels - 1))
    }

    private fun size(level: Int) = widths[level] * heights[level]

    /** Adds one aligned frame: [pixels] is packed ARGB, [width] × [height]. */
    fun add(pixels: IntArray, guide: Guide? = null) {
        require(pixels.size >= width * height)
        noiseHistogram0.fill(0)
        noiseHistogram1.fill(0)
        var g = reducePacked(pixels) // Gaussian level 1
        selectLevel0(pixels, g, guide)
        for (l in 1 until levels - 1) {
            val next = reduceFloat(g, l)
            selectUpper(l, g, next, guide)
            g = next
        }
        // The coarsest level: a (weighted) average, so it has no glow of frames that shouldn't count.
        val topLevel = levels - 1
        val tw = widths[topLevel]
        for (i in topWeight.indices) {
            val w = guide?.at(min((i % tw) shl topLevel, width - 1), min((i / tw) shl topLevel, height - 1)) ?: 1f
            topWeight[i] += w
            for (c in 0 until 3) topSum[c][i] += w * g[c][i]
        }
        if (denoise) {
            noise0 = min(noise0, noiseSigma(noiseHistogram0))
            noise1 = min(noise1, noiseSigma(noiseHistogram1))
            shrink0 = SHRINK_LEVEL0 * noise0
            shrink1 = SHRINK_LEVEL1 * noise1
        }
        frames++
    }

    /** Noise σ (in detail units) from a histogram of |luma detail| × ENERGY_SCALE. */
    private fun noiseSigma(histogram: IntArray): Float {
        val total = histogram.sum().toLong()
        if (total == 0L) return 0f
        val target = (total * NOISE_PERCENTILE).toLong()
        var seen = 0L
        for (bin in histogram.indices) {
            seen += histogram[bin]
            // For Gaussian noise, the 30th percentile of |x| is 0.385 σ.
            if (seen >= target) return (bin + 0.5f) / ENERGY_SCALE / 0.385f
        }
        return 0f
    }

    /** A fused detail coefficient, with noise-level detail removed on the finest two levels. */
    private fun detail(level: Int, raw: Short): Float {
        val v = raw * INV_SCALE
        val t = when (level) {
            0 -> shrink0
            1 -> shrink1
            else -> return v
        }
        // Garrote: detail at the noise level goes, strong detail keeps nearly all its contrast (soft
        // thresholding would take the same amount off every edge, dulling all fine detail).
        return if (abs(v) > t) v - t * t / v else 0f
    }

    /** The pyramid level whose width is at most [maxWidth] (never the full-resolution level 0). */
    fun levelForWidth(maxWidth: Int): Int {
        for (l in 1 until levels) if (widths[l] <= maxWidth) return l
        return levels - 1
    }

    /** The fused result so far, at pyramid [level] (≥ 1) — a cheap, smaller preview. */
    fun preview(level: Int): RgbImage {
        val l = level.coerceIn(1, levels - 1)
        val g = collapse(l)
        val w = widths[l]
        val out = IntArray(size(l))
        for (i in out.indices) out[i] = pack(g[0][i], g[1][i], g[2][i])
        return RgbImage(w, heights[l], out)
    }

    /** Writes the full-resolution fused image into [out] (packed ARGB, [width] × [height]). */
    fun result(out: IntArray) {
        require(out.size >= width * height)
        val g1 = collapse(1)
        val w = widths[0]
        val gw = widths[1]
        val gh = heights[1]
        parallel.forRows(heights[0]) { from, until ->
            val tmp = FloatArray(gw)
            val up = Array(3) { FloatArray(w) }
            for (y in from until until) {
                for (c in 0 until 3) expandRow(g1[c], gw, gh, y, w, tmp, up[c])
                val row = y * w
                val fr = fused[0][0]
                val fg = fused[0][1]
                val fb = fused[0][2]
                for (x in 0 until w) {
                    val i = row + x
                    val r = up[0][x] + detail(0, fr[i])
                    val g = up[1][x] + detail(0, fg[i])
                    val b = up[2][x] + detail(0, fb[i])
                    val luma = LR * r + LG * g + LB * b
                    val lo = (lumaMin[i].toInt() and 0xFF) - ENVELOPE_MARGIN
                    val hi = (lumaMax[i].toInt() and 0xFF) + ENVELOPE_MARGIN
                    val shift = if (luma < lo) lo - luma else if (luma > hi) hi - luma else 0f
                    out[i] = pack(r + shift, g + shift, b + shift)
                }
            }
        }
    }

    // ---------------------------------------------------------------- selection

    /** Level 0 straight from the packed frame: 5×5 energy window. */
    private fun selectLevel0(pixels: IntArray, g1: Array<FloatArray>, guide: Guide?) {
        val w = widths[0]
        val h = heights[0]
        val gw = widths[1]
        val gh = heights[1]

        // Pass 1: |luma detail| per pixel, summed over 5 columns; the frames' luma range.
        parallel.forRows(h) { from, until ->
            val tmp = FloatArray(gw)
            val up = Array(3) { FloatArray(w) }
            val detail = IntArray(w)
            val histogram = if (denoise) IntArray(HISTOGRAM_BINS) else null
            for (y in from until until) {
                for (c in 0 until 3) expandRow(g1[c], gw, gh, y, w, tmp, up[c])
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val p = pixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    detail[x] = (abs(LR * (r - up[0][x]) + LG * (g - up[1][x]) + LB * (b - up[2][x])) * ENERGY_SCALE).toInt()
                    val luma = (77 * r + 150 * g + 29 * b + 128) shr 8
                    if (luma < (lumaMin[i].toInt() and 0xFF)) lumaMin[i] = luma.toByte()
                    if (luma > (lumaMax[i].toInt() and 0xFF)) lumaMax[i] = luma.toByte()
                }
                if (histogram != null) for (x in 0 until w) histogram[min(detail[x], HISTOGRAM_BINS - 1)]++
                var s = detail[0] * 3 + detail[min(1, w - 1)] + detail[min(2, w - 1)]
                rowEnergy0[row] = s.toShort()
                for (x in 1 until w) {
                    s += detail[min(x + 2, w - 1)] - detail[max(x - 3, 0)]
                    rowEnergy0[row + x] = s.toShort()
                }
            }
            if (histogram != null) synchronized(noiseHistogram0) {
                for (b in histogram.indices) noiseHistogram0[b] += histogram[b]
            }
        }

        // Pass 2: sum over 5 rows; where this frame beats the best so far, take its detail.
        if (guide != null && guide.columns.size != w) guide.columns = IntArray(w) { it / guide.cell }
        parallel.forRows(h) { from, until ->
            val tmp = FloatArray(gw)
            val up = Array(3) { FloatArray(w) }
            val fr = fused[0][0]
            val fg = fused[0][1]
            val fb = fused[0][2]
            for (y in from until until) {
                val r0 = max(y - 2, 0) * w
                val r1 = max(y - 1, 0) * w
                val r2 = y * w
                val r3 = min(y + 1, h - 1) * w
                val r4 = min(y + 2, h - 1) * w
                var expanded = false
                val guideRow = if (guide != null) (y / guide.cell) * guide.gridWidth else 0
                for (x in 0 until w) {
                    var e = rowEnergy0[r0 + x] + rowEnergy0[r1 + x] + rowEnergy0[r2 + x] + rowEnergy0[r3 + x] + rowEnergy0[r4 + x]
                    if (guide != null) e = (e * guide.weights[guideRow + guide.columns[x]]).toInt()
                    val i = r2 + x
                    if (e > bestEnergy0[i]) {
                        if (!expanded) {
                            for (c in 0 until 3) expandRow(g1[c], gw, gh, y, w, tmp, up[c])
                            expanded = true
                        }
                        bestEnergy0[i] = min(e, Short.MAX_VALUE.toInt()).toShort()
                        val p = pixels[i]
                        fr[i] = coef(((p shr 16) and 0xFF) - up[0][x])
                        fg[i] = coef(((p shr 8) and 0xFF) - up[1][x])
                        fb[i] = coef((p and 0xFF) - up[2][x])
                    }
                }
            }
        }
    }

    /** Levels between the finest and the top: 3×3 energy window. */
    private fun selectUpper(l: Int, g: Array<FloatArray>, next: Array<FloatArray>, guide: Guide?) {
        val w = widths[l]
        val h = heights[l]
        val gw = widths[l + 1]
        val gh = heights[l + 1]
        val rowEnergy = rowEnergyUpper[l - 1]
        val best = bestEnergyUpper[l - 1]
        val f = fused[l]

        val collectNoise = denoise && l == 1
        parallel.forRows(h) { from, until ->
            val tmp = FloatArray(gw)
            val up = Array(3) { FloatArray(w) }
            val detail = FloatArray(w)
            val histogram = if (collectNoise) IntArray(HISTOGRAM_BINS) else null
            for (y in from until until) {
                for (c in 0 until 3) expandRow(next[c], gw, gh, y, w, tmp, up[c])
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    detail[x] = abs(LR * (g[0][i] - up[0][x]) + LG * (g[1][i] - up[1][x]) + LB * (g[2][i] - up[2][x]))
                }
                if (histogram != null) for (x in 0 until w) histogram[min((detail[x] * ENERGY_SCALE).toInt(), HISTOGRAM_BINS - 1)]++
                for (x in 0 until w) {
                    rowEnergy[row + x] = detail[max(x - 1, 0)] + detail[x] + detail[min(x + 1, w - 1)]
                }
            }
            if (histogram != null) synchronized(noiseHistogram1) {
                for (b in histogram.indices) noiseHistogram1[b] += histogram[b]
            }
        }

        parallel.forRows(h) { from, until ->
            val tmp = FloatArray(gw)
            val up = Array(3) { FloatArray(w) }
            for (y in from until until) {
                val r0 = max(y - 1, 0) * w
                val r1 = y * w
                val r2 = min(y + 1, h - 1) * w
                var expanded = false
                for (x in 0 until w) {
                    var e = rowEnergy[r0 + x] + rowEnergy[r1 + x] + rowEnergy[r2 + x]
                    if (guide != null) e *= guide.at(min(x shl l, width - 1), min(y shl l, height - 1))
                    val i = r1 + x
                    if (e > best[i]) {
                        if (!expanded) {
                            for (c in 0 until 3) expandRow(next[c], gw, gh, y, w, tmp, up[c])
                            expanded = true
                        }
                        best[i] = e
                        for (c in 0 until 3) f[c][i] = coef(g[c][i] - up[c][x])
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- pyramid operations

    /** Gaussian level 1 from the packed full-resolution frame. */
    private fun reducePacked(src: IntArray): Array<FloatArray> {
        val w0 = widths[0]
        val h0 = heights[0]
        val w1 = widths[1]
        val h1 = heights[1]
        val out = Array(3) { FloatArray(w1 * h1) }
        parallel.forRows(h1) { from, until ->
            val col = Array(3) { FloatArray(w0) }
            for (i in from until until) {
                for (c in 0 until 3) col[c].fill(0f)
                for (m in -2..2) {
                    val k = KERNEL[m + 2]
                    val row = reflect(2 * i + m, h0) * w0
                    val cr = col[0]
                    val cg = col[1]
                    val cb = col[2]
                    for (x in 0 until w0) {
                        val p = src[row + x]
                        cr[x] += k * ((p shr 16) and 0xFF)
                        cg[x] += k * ((p shr 8) and 0xFF)
                        cb[x] += k * (p and 0xFF)
                    }
                }
                for (c in 0 until 3) reduceRow(col[c], w0, out[c], i * w1, w1)
            }
        }
        return out
    }

    /** Gaussian level l + 1 from level l. */
    private fun reduceFloat(g: Array<FloatArray>, l: Int): Array<FloatArray> {
        val w = widths[l]
        val h = heights[l]
        val nw = widths[l + 1]
        val nh = heights[l + 1]
        val out = Array(3) { FloatArray(nw * nh) }
        parallel.forRows(nh) { from, until ->
            val col = FloatArray(w)
            for (i in from until until) {
                for (c in 0 until 3) {
                    col.fill(0f)
                    val src = g[c]
                    for (m in -2..2) {
                        val k = KERNEL[m + 2]
                        val row = reflect(2 * i + m, h) * w
                        for (x in 0 until w) col[x] += k * src[row + x]
                    }
                    reduceRow(col, w, out[c], i * nw, nw)
                }
            }
        }
        return out
    }

    /** Collapses the fused pyramid down to Gaussian level [downTo] (≥ 1). */
    private fun collapse(downTo: Int): Array<FloatArray> {
        val top = levels - 1
        var g = Array(3) { c -> FloatArray(size(top)) { topSum[c][it] / max(topWeight[it], 1e-6f) } }
        for (l in top - 1 downTo downTo) {
            val w = widths[l]
            val gw = widths[l + 1]
            val gh = heights[l + 1]
            val prev = g
            val out = Array(3) { FloatArray(size(l)) }
            parallel.forRows(heights[l]) { from, until ->
                val tmp = FloatArray(gw)
                val up = FloatArray(w)
                for (y in from until until) {
                    val row = y * w
                    for (c in 0 until 3) {
                        expandRow(prev[c], gw, gh, y, w, tmp, up)
                        val coefs = fused[l][c]
                        val dst = out[c]
                        for (x in 0 until w) dst[row + x] = up[x] + detail(l, coefs[row + x])
                    }
                }
            }
            g = out
        }
        return g
    }

    private companion object {
        const val MAX_LEVELS = 8
        const val MIN_TOP = 8
        const val MIN_SIZE = 2 * MIN_TOP

        /** Detail coefficients are stored as shorts with 1/16 precision; they lie within ±255. */
        const val COEF_SCALE = 16f
        const val INV_SCALE = 1f / COEF_SCALE

        /** Level-0 energies are integers: |luma detail| × 4 ≤ 1020, summed over 25 pixels ≤ 25 500. */
        const val ENERGY_SCALE = 4f

        const val HISTOGRAM_BINS = 1024
        const val NOISE_PERCENTILE = 0.3

        /** Soft-threshold strength, in noise σ, for the finest and second-finest levels. */
        const val SHRINK_LEVEL0 = 2.0f
        const val SHRINK_LEVEL1 = 1.3f

        /** How far (in luma levels) the result may go beyond the darkest / brightest frame at a pixel. */
        const val ENVELOPE_MARGIN = 2f

        const val LR = 0.299f
        const val LG = 0.587f
        const val LB = 0.114f

        /** Burt–Adelson 5-tap kernel [1 4 6 4 1] / 16. */
        val KERNEL = floatArrayOf(1 / 16f, 4 / 16f, 6 / 16f, 4 / 16f, 1 / 16f)

        fun reflect(i: Int, n: Int): Int = when {
            n == 1 -> 0
            i < 0 -> min(-i, n - 1)
            i >= n -> max(2 * n - 2 - i, 0)
            else -> i
        }

        /** Horizontal half of REDUCE: [src] (width [sw]) → [dw] samples written at [dst][offset]. */
        fun reduceRow(src: FloatArray, sw: Int, dst: FloatArray, offset: Int, dw: Int) {
            for (j in 0 until dw) {
                val c = 2 * j
                dst[offset + j] = if (c >= 2 && c + 2 < sw) {
                    KERNEL[0] * src[c - 2] + KERNEL[1] * src[c - 1] + KERNEL[2] * src[c] +
                        KERNEL[3] * src[c + 1] + KERNEL[4] * src[c + 2]
                } else {
                    KERNEL[0] * src[reflect(c - 2, sw)] + KERNEL[1] * src[reflect(c - 1, sw)] +
                        KERNEL[2] * src[reflect(c, sw)] + KERNEL[3] * src[reflect(c + 1, sw)] +
                        KERNEL[4] * src[reflect(c + 2, sw)]
                }
            }
        }

        /**
         * EXPAND for one output row: upsamples the coarser level [g] (gw × gh) to row [y] of the finer
         * level ([outW] wide), into [out]. [tmp] must hold [gw] values.
         */
        fun expandRow(g: FloatArray, gw: Int, gh: Int, y: Int, outW: Int, tmp: FloatArray, out: FloatArray) {
            val i = y / 2
            val below = min(i + 1, gh - 1) * gw
            val here = i * gw
            if (y and 1 == 0) {
                val above = max(i - 1, 0) * gw
                for (x in 0 until gw) tmp[x] = (g[above + x] + 6f * g[here + x] + g[below + x]) * 0.125f
            } else {
                for (x in 0 until gw) tmp[x] = (g[here + x] + g[below + x]) * 0.5f
            }
            val last = gw - 1
            for (x in 0 until outW) {
                val j = x shr 1
                out[x] = if (x and 1 == 0) {
                    (tmp[max(j - 1, 0)] + 6f * tmp[j] + tmp[min(j + 1, last)]) * 0.125f
                } else {
                    (tmp[j] + tmp[min(j + 1, last)]) * 0.5f
                }
            }
        }

        fun coef(v: Float): Short = (v * COEF_SCALE).roundToInt().coerceIn(-32767, 32767).toShort()

        fun pack(r: Float, g: Float, b: Float): Int {
            val ri = (r + 0.5f).toInt().coerceIn(0, 255)
            val gi = (g + 0.5f).toInt().coerceIn(0, 255)
            val bi = (b + 0.5f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }
}
