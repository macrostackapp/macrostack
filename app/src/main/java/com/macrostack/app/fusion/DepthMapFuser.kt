package com.macrostack.app.fusion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Depth-map focus fusion ("DMap"-style, like Zerene Stacker's DMap or Helicon's Method B), in two
 * passes over the aligned frames:
 *
 * 1. **Measure** — how sharp each small cell of the image is in every frame, and how bright. For
 *    each cell that gives a sharpness curve across the stack; its peak is the depth (which frame the
 *    cell is in focus in, with sub-frame precision) and how far the peak stands out is the confidence.
 * 2. **Compose** — every pixel takes a blend of the frames around its depth. Where there is real
 *    detail that is just the nearest one or two frames, so a subject that moved shows up once, not as
 *    a ghosted stack of outlines. Where nothing is in focus (backgrounds, flat surfaces) up to 17
 *    frames are averaged, which removes most of the noise instead of adding to it.
 *
 * Sharpness that noise can't fake: sensor noise looks like fine detail, and a bright blur (an
 * out-of-focus knee glowing over a dark carpet) is noisier than the dark detail that is really in
 * focus there. But noise is different in every frame, while real detail is still there, a little
 * softer, in the neighbouring frames. So sharpness is measured as the detail a frame *shares* with
 * its neighbours (the mean product of their fine-detail signals): noise averages out of it.
 *
 * Detail is measured at two scales. Fine detail decides wherever there is any; coarse detail (of
 * 4×4-pixel blocks, which averages most noise away) decides where nothing is ever really sharp —
 * something nearer or farther than the stack reaches — so it still comes from its least blurred frame.
 *
 * Between the passes:
 * - **The depth map is smoothed with a weighted median**, trusting confident cells, so flat areas
 *   take their depth from the detail around them without blurring depth steps at object edges.
 * - **Only frames where a cell looks the same are averaged.** A blurred object spilling over a cell
 *   changes its brightness, so frames where it did are left out.
 *
 * Uses about 16 bytes per output pixel, plus the per-cell sharpness curves.
 */
class DepthMapFuser(val width: Int, val height: Int, val frames: Int, private val parallel: Parallel) {

    /** Cell size in pixels: depth is measured per cell. */
    val cell: Int
    val gridWidth: Int
    val gridHeight: Int

    /**
     * Fine detail of every cell in every frame: [cellIndex * frames + frame], RMS Laplacian ×
     * ENERGY_SCALE — noise included. [analyze] replaces it with the shared-detail sharpness.
     */
    private var profile: ShortArray

    /**
     * Detail shared by frame k and frame k − 1, laid out like [profile]: the mean product of their
     * Laplacians, as a signed RMS × ENERGY_SCALE (nothing for k = 0).
     */
    private var shared: ShortArray

    /** The previous frame's Laplacian, per pixel, while measuring. */
    private var previousDetail: ShortArray? = null

    /** Coarse detail of every cell in every frame (Laplacian of 4×4-pixel block means), like [profile]. */
    private var coarse: ShortArray

    /** Mean brightness (0–255) of every cell in every frame, laid out like [profile]. */
    private var brightness: ByteArray

    /** Per frame and brightness band: the sharpness noise alone gives (as in [profile]), NaN if unknown. */
    private val noise = Array(frames) { FloatArray(LUMA_BINS) { Float.NaN } }
    private val coarseNoise = Array(frames) { FloatArray(LUMA_BINS) { Float.NaN } }

    /** Tests: keep the raw sharpness for [cellReport]. */
    internal var keepRawForTests = false
    private var rawProfile: ShortArray? = null
    private var noiseLevels: Array<FloatArray>? = null
    private var rawCoarse: ShortArray? = null
    private var debugConfidence: FloatArray? = null
    private var debugCoarseConfidence: FloatArray? = null

    private var depth = FloatArray(0)
    private var radius = FloatArray(0)
    private var analyzed = false

    /** Σ weight × colour (× COLOR_SCALE) and Σ weight (× WEIGHT_SCALE), per pixel — only once composing. */
    private val acc by lazy { Array(3) { ShortArray(width * height) } }
    private val accWeight by lazy { ShortArray(width * height) }

    /** Each cell's sharpness curve, scaled so its peak is 255 (0 where it's no clearer than noise). */
    private var shape = ByteArray(0)

    init {
        require(frames >= 2)
        require(width >= 16 && height >= 16) { "Image too small to stack: ${width}×$height" }
        val allowedCells = PROFILE_BUDGET_BYTES / (7L * frames)
        cell = ceil(sqrt(width.toDouble() * height / allowedCells)).toInt().coerceIn(MIN_CELL, MAX_CELL)
        gridWidth = (width + cell - 1) / cell
        gridHeight = (height + cell - 1) / cell
        profile = ShortArray(gridWidth * gridHeight * frames)
        shared = ShortArray(gridWidth * gridHeight * frames)
        coarse = ShortArray(gridWidth * gridHeight * frames)
        brightness = ByteArray(gridWidth * gridHeight * frames)
    }

    // ---------------------------------------------------------------- pass 1: measure

    /** Records how sharp and how bright each cell is in aligned frame [k]. Frames come in order. */
    fun measure(k: Int, pixels: IntArray) {
        require(!analyzed && k in 0 until frames)
        val w = width
        val h = height
        val previous = previousDetail ?: ShortArray(w * h).also { previousDetail = it }
        val hasPrevious = k > 0
        parallel.forRows(gridHeight) { from, until ->
            val sums = LongArray(gridWidth)
            val sharedSums = LongArray(gridWidth)
            val lumaSums = LongArray(gridWidth)
            val counts = IntArray(gridWidth)
            // Luma of one band of cells plus SPREAD rows above and below, computed once per pixel.
            val band = IntArray((cell + 2 * SPREAD) * w)
            for (gy in from until until) {
                sums.fill(0L)
                sharedSums.fill(0L)
                lumaSums.fill(0L)
                counts.fill(0)
                val y0 = gy * cell
                val y1 = min(y0 + cell, h)
                val top = max(y0 - SPREAD, 0)
                val bottom = min(y1 + SPREAD, h) // exclusive
                for (y in top until bottom) {
                    val src = y * w
                    val dst = (y - top) * w
                    for (x in 0 until w) band[dst + x] = luma(pixels[src + x])
                }
                for (y in y0 until y1) {
                    val image = y * w
                    val row = (y - top) * w
                    val up = (max(y - SPREAD, 0) - top) * w
                    val down = (min(y + SPREAD, h - 1) - top) * w
                    for (gx in 0 until gridWidth) {
                        val x0 = gx * cell
                        val x1 = min(x0 + cell, w)
                        var sum = 0L
                        var sharedSum = 0L
                        var lumaSum = 0L
                        for (x in x0 until x1) {
                            // Laplacian with a 2-pixel spread: still focus-sensitive, less swayed by pixel noise.
                            val c = band[row + x]
                            val lap = 4 * c - band[row + max(x - SPREAD, 0)] - band[row + min(x + SPREAD, w - 1)] -
                                band[up + x] - band[down + x]
                            sum += lap * lap
                            if (hasPrevious) sharedSum += lap * previous[image + x]
                            previous[image + x] = lap.toShort()
                            lumaSum += c
                        }
                        sums[gx] += sum
                        sharedSums[gx] += sharedSum
                        lumaSums[gx] += lumaSum
                        counts[gx] += x1 - x0
                    }
                }
                for (gx in 0 until gridWidth) {
                    val n = max(counts[gx], 1)
                    val i = (gy * gridWidth + gx) * frames + k
                    profile[i] = (sqrt(sums[gx].toDouble() / n) * ENERGY_SCALE).roundToInt().coerceAtMost(Short.MAX_VALUE.toInt()).toShort()
                    val product = sharedSums[gx].toDouble() / n
                    shared[i] = (Math.copySign(sqrt(abs(product)), product) * ENERGY_SCALE).roundToInt()
                        .coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    brightness[i] = (lumaSums[gx] / n).toInt().toByte()
                }
            }
        }
        measureNoise(k, profile, noise)
        measureCoarse(k, pixels)
        measureNoise(k, coarse, coarseNoise)
        if (k == frames - 1) previousDetail = null
    }

    /** Coarse detail of frame [k]: the Laplacian of 4×4-pixel block means, per cell. */
    private fun measureCoarse(k: Int, pixels: IntArray) {
        val w = width
        val bw = width / BLOCK
        val bh = height / BLOCK
        val blocks = FloatArray(bw * bh)
        parallel.forRows(bh) { from, until ->
            for (by in from until until) for (bx in 0 until bw) {
                var sum = 0
                for (y in by * BLOCK until (by + 1) * BLOCK) {
                    val row = y * w
                    for (x in bx * BLOCK until (bx + 1) * BLOCK) sum += luma(pixels[row + x])
                }
                blocks[by * bw + bx] = sum / (BLOCK * BLOCK).toFloat()
            }
        }
        val cells = gridWidth * gridHeight
        val sums = DoubleArray(cells)
        val counts = IntArray(cells)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val i = by * bw + bx
            val lap = 4 * blocks[i] - blocks[by * bw + max(bx - 1, 0)] - blocks[by * bw + min(bx + 1, bw - 1)] -
                blocks[max(by - 1, 0) * bw + bx] - blocks[min(by + 1, bh - 1) * bw + bx]
            val gx = min((bx * BLOCK + BLOCK / 2) / cell, gridWidth - 1)
            val gy = min((by * BLOCK + BLOCK / 2) / cell, gridHeight - 1)
            val c = gy * gridWidth + gx
            sums[c] += (lap * lap).toDouble()
            counts[c]++
        }
        for (c in 0 until cells) {
            val rms = if (counts[c] > 0) sqrt(sums[c] / counts[c]) else 0.0
            coarse[c * frames + k] = (rms * ENERGY_SCALE).roundToInt().coerceAtMost(Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Most of a frame is out of focus, so within each brightness band its least detailed cells show
     * what noise alone scores. Records that level of [values] for frame [k] into [into].
     */
    private fun measureNoise(k: Int, values: ShortArray, into: Array<FloatArray>) {
        val histogram = Array(LUMA_BINS) { IntArray(NOISE_HISTOGRAM_BINS) }
        for (c in 0 until gridWidth * gridHeight) {
            val i = c * frames + k
            val band = (brightness[i].toInt() and 0xFF) * LUMA_BINS / 256
            histogram[band][min(values[i] / NOISE_HISTOGRAM_STEP, NOISE_HISTOGRAM_BINS - 1)]++
        }
        for (band in 0 until LUMA_BINS) {
            val counts = histogram[band]
            val total = counts.sum()
            if (total < MIN_NOISE_CELLS) continue
            val target = total * NOISE_PERCENTILE
            var seen = 0
            for (bin in counts.indices) {
                seen += counts[bin]
                if (seen >= target) {
                    into[k][band] = (bin + 0.5f) * NOISE_HISTOGRAM_STEP
                    break
                }
            }
        }
    }

    // ---------------------------------------------------------------- depth map

    /** Turns the sharpness curves into a smooth depth map and a per-cell blending radius. */
    fun analyze() {
        val cells = gridWidth * gridHeight

        // 1. Sharpness noise and glow can't fake. Where a cell's detail is well above the noise level
        // it is real: the detail, minus the noise. Where it is only a little above, only the detail it
        // shares with the neighbouring frames counts (noise differs from frame to frame; a subject
        // that moved shares nothing, but its detail is far above the noise) — and only while the
        // cell's brightness holds steady: a blurred object glowing over it brightens or darkens it
        // frame after frame, and the blurred pattern in that glow is shared, but isn't this cell's.
        val noiseAt = noiseModel(noise)
        val coarseNoiseAt = noiseModel(coarseNoise)
        noiseLevels = noiseAt
        if (keepRawForTests) {
            rawProfile = profile.copyOf()
            rawCoarse = coarse.copyOf()
            debugConfidence = FloatArray(cells)
            debugCoarseConfidence = FloatArray(cells)
        }
        previousDetail = null
        parallel.forRows(gridHeight) { from, until ->
            val product = FloatArray(frames) // product[k]: frames k − 1 and k
            for (c in from * gridWidth until until * gridWidth) {
                val base = c * frames
                fun bright(k: Int) = (brightness[base + k.coerceIn(0, frames - 1)].toInt() and 0xFF).toFloat()
                var usual = 0f
                for (k in 0 until frames) usual += bright(k)
                usual /= frames
                val settledTolerance = SETTLED_TOLERANCE + SETTLED_TOLERANCE_RELATIVE * usual
                for (k in 1 until frames) {
                    val v = shared[base + k].toFloat()
                    product[k] = if (v >= 0f) v * v else -v * v
                }
                for (k in 0 until frames) {
                    val i = base + k
                    val m = when (k) {
                        0 -> product[1]
                        frames - 1 -> product[k]
                        else -> (product[k] + product[k + 1]) / 2
                    }
                    val sharedDetail = sqrt(max(0f, m))
                    val e = profile[i].toFloat()
                    val n = max(noiseAt[k][brightness[i].toInt() and 0xFF], 1f)
                    val ownDetail = sqrt(max(0f, e * e - NOISE_MARGIN * NOISE_MARGIN * n * n))
                    val t = ((e / n - TRUST_OWN_FROM) / (TRUST_OWN_AT - TRUST_OWN_FROM)).coerceIn(0f, 1f)
                    // Brightness change per frame here; a steady ramp is glow coming or going.
                    val slope = abs(bright(k + 1) - bright(k - 1)) / (if (k == 0 || k == frames - 1) 1f else 2f)
                    val tolerance = RAMP_TOLERANCE + RAMP_TOLERANCE_RELATIVE * bright(k)
                    val steady = (1f - (slope - tolerance) / tolerance).coerceIn(0f, 1f)
                    // Coarse detail is held to a stricter standard (a glow is coarse detail too), on the
                    // trend over five frames, so small flicker doesn't count but a long ramp does.
                    val lo = max(k - 2, 0)
                    val hi = min(k + 2, frames - 1)
                    val trend = abs(bright(hi) - bright(lo)) / max(hi - lo, 1)
                    val coarseTolerance = COARSE_RAMP_TOLERANCE + COARSE_RAMP_TOLERANCE_RELATIVE * bright(k)
                    val coarseSteady = (1f - (trend - coarseTolerance) / coarseTolerance).coerceIn(0f, 1f)
                    // …and not where the cell is much brighter than it usually is over the stack: that's a
                    // blurred bright neighbour's glow over it (a dark gap under a white edge, say), even
                    // at the end of the stack, where that glow has stopped changing. (Darker than usual is
                    // fine: a dark subject is darkest in focus, with its neighbours' glow everywhere else.)
                    val settled = (1f - (bright(k) - usual - settledTolerance) / settledTolerance).coerceIn(0f, 1f)
                    val detail = sharedDetail + t * (ownDetail - sharedDetail)
                    profile[i] = (detail * (t + (1 - t) * steady)).roundToInt()
                        .coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
                    val ce = coarse[i].toFloat()
                    val cn = coarseNoiseAt[k][brightness[i].toInt() and 0xFF] * NOISE_MARGIN
                    coarse[i] = (sqrt(max(0f, ce * ce - cn * cn)) * coarseSteady * settled).roundToInt()
                        .coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }

        // 2. Each cell's depth (the peak of its curve) and how sure that is.
        shape = ByteArray(cells * frames)
        val rawDepth = FloatArray(cells)
        val confidence = FloatArray(cells)
        parallel.forRows(gridHeight) { from, until ->
            val curve = FloatArray(frames)
            val coarseCurve = FloatArray(frames)
            val sorted = FloatArray(frames)
            val spatial = FloatArray(frames)
            var depthFound = 0f
            var confidenceFound = 0f

            // The peak of [c] (sub-frame) and how far it stands out against [noiseAt] there.
            fun peak(i: Int, c: FloatArray, noiseAt: Array<FloatArray>, noiseFloor: Float) {
                var best = 0
                for (k in 1 until frames) if (c[k] > c[best]) best = k
                // Neighbouring frames almost as sharp as the best one: a wide, flat top's middle is
                // steadier than a noisy maximum.
                val plateauLevel = c[best] * PLATEAU
                var a = best
                while (a > 0 && c[a - 1] >= plateauLevel) a--
                var b = best
                while (b < frames - 1 && c[b + 1] >= plateauLevel) b++
                var d = best.toFloat()
                if (b - a >= 2) {
                    d = (a + b) / 2f
                } else if (best in 1 until frames - 1) {
                    // Sub-frame peak from a parabola through the top three points.
                    val l = c[best - 1]
                    val m = c[best]
                    val r = c[best + 1]
                    val denom = l - 2 * m + r
                    if (denom < 0f) d += (0.5f * (l - r) / denom).coerceIn(-0.5f, 0.5f)
                }
                c.copyInto(sorted)
                sorted.sort()
                val floor = sorted[(frames * BASELINE_PERCENTILE).toInt().coerceIn(0, frames - 1)]
                val noiseHere = noiseAt[best][brightness[i * frames + best].toInt() and 0xFF]
                depthFound = d
                confidenceFound = (c[best] - floor) / (floor + max(noiseHere, noiseFloor))
            }

            for (gy in from until until) for (gx in 0 until gridWidth) {
                val i = gy * gridWidth + gx
                curveOf(gx, gy, spatial, curve, profile, 1)
                peak(i, curve, noiseAt, NOISE_FLOOR)
                rawDepth[i] = depthFound
                confidence[i] = confidenceFound
                // Nothing clearly sharp at the fine scale: let coarse detail decide, if it can.
                curveOf(gx, gy, spatial, coarseCurve, coarse, COARSE_REACH)
                peak(i, coarseCurve, coarseNoiseAt, COARSE_NOISE_FLOOR)
                // For guiding a pyramid: which frames show real detail here, at either scale (possibly
                // at two depths) — relative to the strongest, and only if that stands out from noise.
                val middle = brightness[i * frames + frames / 2].toInt() and 0xFF
                var top = 0f
                var coarseTop = 0f
                for (k in 0 until frames) {
                    top = max(top, curve[k])
                    coarseTop = max(coarseTop, coarseCurve[k])
                }
                val scale = 1f / max(top, SHAPE_NOISE * noiseAt[frames / 2][middle])
                val coarseScale = 1f / max(coarseTop, SHAPE_NOISE * coarseNoiseAt[frames / 2][middle])
                for (k in 0 until frames) {
                    val v = max(curve[k] * scale, coarseCurve[k] * coarseScale)
                    shape[i * frames + k] = (v * 255).roundToInt().coerceIn(0, 255).toByte()
                }
                debugConfidence?.set(i, confidence[i])
                debugCoarseConfidence?.set(i, confidenceFound)
                if (COARSE_WEIGHT * confidenceFound > confidence[i]) {
                    rawDepth[i] = depthFound
                    confidence[i] = COARSE_WEIGHT * confidenceFound
                }
            }
        }

        // 3. Smooth the depth, trusting confident cells. Nearby: a weighted median, which keeps the
        // step at an object's edge sharp. Where nothing nearby is confident: a wide weighted mean.
        val weight = FloatArray(cells) { max(0f, confidence[it] - MIN_CONFIDENCE) }
        val (nearDepth, nearWeight) = weightedMedian(rawDepth, weight)
        val weighted = FloatArray(cells) { rawDepth[it] * weight[it] }
        val farDepth = blur(weighted, FAR_SIGMA)
        val farWeight = blur(weight, FAR_SIGMA)
        val totalWeight = weight.sum().toDouble()
        val globalDepth = if (totalWeight > 0) (weighted.sum() / totalWeight).toFloat() else (frames - 1) / 2f
        depth = FloatArray(cells) { i ->
            val fallback = if (farWeight[i] > 1e-4f) farDepth[i] / farWeight[i] else globalDepth
            val near = if (nearDepth[i].isNaN()) fallback else nearDepth[i]
            val trust = (nearWeight[i] / NEAR_WEIGHT_FULL).coerceIn(0f, 1f)
            (trust * near + (1 - trust) * fallback).coerceIn(0f, frames - 1f)
        }

        // 4. How many frames around its depth each cell may average: those about as sharp as the one
        // at its depth (on flat areas, most of the stack) — but only while the cell looks the same.
        // A blurred object spilling over it changes its brightness, and those frames are left out.
        val plateau = FloatArray(cells)
        parallel.forRows(gridHeight) { from, until ->
            val curve = FloatArray(frames)
            val coarseCurve = FloatArray(frames)
            val spatial = FloatArray(frames)
            for (gy in from until until) for (gx in 0 until gridWidth) {
                val i = gy * gridWidth + gx
                curveOf(gx, gy, spatial, curve, profile, 1)
                curveOf(gx, gy, spatial, coarseCurve, coarse, COARSE_REACH)
                val d = depth[i]
                val dk = d.roundToInt()
                val base = i * frames
                val ref = brightness[base + dk].toInt() and 0xFF
                // Less sharp by less than the noise is no visible loss — at either scale.
                val level = curve[dk] * PLATEAU - PLATEAU_SLACK * noiseAt[dk][ref]
                val coarseLevel = coarseCurve[dk] * PLATEAU - PLATEAU_SLACK * coarseNoiseAt[dk][ref]
                val tolerance = LUMA_TOLERANCE + LUMA_TOLERANCE_RELATIVE * ref
                fun same(k: Int) = curve[k] >= level && coarseCurve[k] >= coarseLevel &&
                    abs((brightness[base + k].toInt() and 0xFF) - ref) <= tolerance
                var a = dk
                while (a > 0 && same(a - 1)) a--
                var b = dk
                while (b < frames - 1 && same(b + 1)) b++
                plateau[i] = 1f + min(d - a, b - d).coerceAtLeast(0f)
            }
        }
        // Detail → the 1–2 nearest frames; flat → the whole plateau (noise reduction). The plateau is
        // eroded (minimum over neighbours) so averaging never spills onto detail next to a flat area.
        val maxRadius = min(MAX_RADIUS, max(1f, frames / 2f))
        val smoothPlateau = blur(erode(plateau), NEAR_SIGMA)
        radius = FloatArray(cells) { smoothPlateau[it].coerceIn(1f, maxRadius) }
        analyzed = true
        // The measurements have served their purpose: free them before composing needs the memory.
        if (!keepRawForTests) {
            profile = ShortArray(0)
            shared = ShortArray(0)
            coarse = ShortArray(0)
            brightness = ByteArray(0)
        }
    }

    /**
     * How much frame [k] should count in each cell when another method (a pyramid) merges the stack,
     * into [out] (one value per cell, 0–1). Full weight near the cell's depth, and wherever the frame
     * shows real detail of its own there (two hairs crossing at different depths both keep theirs);
     * little where its "detail" is noise, or the glow of something blurred.
     */
    fun guidance(k: Int, out: FloatArray) {
        check(analyzed) { "analyze() first" }
        for (i in out.indices) {
            val near = 1f - (abs(k - depth[i]) - radius[i] - GUIDE_MARGIN) / GUIDE_FALLOFF
            val own = (shape[i * frames + k].toInt() and 0xFF) / 255f
            out[i] = max(GUIDE_FLOOR, max(near.coerceIn(0f, 1f), own))
        }
    }

    /** Tests: what was measured and decided for the cell holding pixel ([x], [y]). */
    internal fun cellReport(x: Int, y: Int): String = buildString {
        val gx = (x / cell).coerceIn(0, gridWidth - 1)
        val gy = (y / cell).coerceIn(0, gridHeight - 1)
        val i = gy * gridWidth + gx
        val curve = FloatArray(frames)
        val coarseCurve = FloatArray(frames)
        curveOf(gx, gy, FloatArray(frames), curve, profile, 1)
        curveOf(gx, gy, FloatArray(frames), coarseCurve, coarse, COARSE_REACH)
        appendLine("cell ($gx, $gy) at ($x, $y): depth %.2f radius %.2f · confidence fine %.2f coarse %.2f".format(
            depth[i], radius[i], debugConfidence?.get(i) ?: Float.NaN, debugCoarseConfidence?.get(i) ?: Float.NaN))
        appendLine("  k   raw noise  fine curve | coarse raw  used curve | bright")
        for (k in 0 until frames) {
            val j = i * frames + k
            val b = brightness[j].toInt() and 0xFF
            appendLine("%3d %5.1f %5.1f %5.1f %5.1f |  %5.2f %5.2f %5.2f | %4d".format(
                k, (rawProfile?.get(j) ?: 0) / ENERGY_SCALE, (noiseLevels?.get(k)?.get(b) ?: 0f) / ENERGY_SCALE,
                profile[j] / ENERGY_SCALE, curve[k] / ENERGY_SCALE,
                (rawCoarse?.get(j) ?: 0) / ENERGY_SCALE, coarse[j] / ENERGY_SCALE, coarseCurve[k] / ENERGY_SCALE, b))
        }
    }

    /**
     * A cell's sharpness curve across the stack, into [curve]: averaged with the neighbouring cells
     * up to [reach] away (on flat areas each reading jitters, and this steadies the peak and the
     * plateau) and lightly along the stack.
     */
    private fun curveOf(gx: Int, gy: Int, spatial: FloatArray, curve: FloatArray, values: ShortArray, reach: Int) {
        spatial.fill(0f)
        var count = 0
        for (ny in max(gy - reach, 0)..min(gy + reach, gridHeight - 1)) for (nx in max(gx - reach, 0)..min(gx + reach, gridWidth - 1)) {
            val nb = (ny * gridWidth + nx) * frames
            val wgt = if (ny == gy && nx == gx) 2 else 1
            for (k in 0 until frames) spatial[k] += wgt * values[nb + k]
            count += wgt
        }
        for (k in 0 until frames) {
            val a = spatial[max(k - 1, 0)]
            val b = spatial[k]
            val c = spatial[min(k + 1, frames - 1)]
            curve[k] = (a + 2f * b + c) * 0.25f / count
        }
    }

    /**
     * Per frame and brightness level (0–255): the sharpness noise alone gives. A frame's own
     * measurement is kept near the stack's typical one — a frame whose cells at some brightness are
     * mostly in focus would otherwise look noisy.
     */
    private fun noiseModel(noise: Array<FloatArray>): Array<FloatArray> {
        val typical = FloatArray(LUMA_BINS) { band ->
            val values = (0 until frames).map { noise[it][band] }.filter { !it.isNaN() }.sorted()
            if (values.isEmpty()) Float.NaN else values[values.size / 2]
        }
        // Bands too rare to measure borrow from the nearest one that wasn't.
        val known = typical.indices.filter { !typical[it].isNaN() }
        for (band in typical.indices) {
            if (typical[band].isNaN()) typical[band] = known.minByOrNull { abs(it - band) }?.let { typical[it] } ?: 0f
        }
        return Array(frames) { k ->
            val perBand = FloatArray(LUMA_BINS) { band ->
                val own = noise[k][band]
                if (own.isNaN()) typical[band] else own.coerceIn(typical[band] * 0.8f, typical[band] * 1.25f)
            }
            FloatArray(256) { v ->
                val pos = ((v + 0.5f) * LUMA_BINS / 256f - 0.5f).coerceIn(0f, LUMA_BINS - 1f)
                val b0 = floor(pos).toInt()
                lerp(perBand[b0], perBand[min(b0 + 1, LUMA_BINS - 1)], pos - b0)
            }
        }
    }

    /**
     * Weighted median of [values] over the 5×5 cells around each cell (weights × a Gaussian falloff),
     * NaN where no cell has weight; and the mean weight there.
     */
    private fun weightedMedian(values: FloatArray, weights: FloatArray): Pair<FloatArray, FloatArray> {
        val gw = gridWidth
        val gh = gridHeight
        val r = MEDIAN_RADIUS
        val side = 2 * r + 1
        val kernel = FloatArray(side * side) {
            val dx = it % side - r
            val dy = it / side - r
            exp(-(dx * dx + dy * dy) / (2 * NEAR_SIGMA * NEAR_SIGMA))
        }
        val median = FloatArray(values.size)
        val meanWeight = FloatArray(values.size)
        parallel.forRows(gh) { from, until ->
            val v = FloatArray(kernel.size)
            val wv = FloatArray(kernel.size)
            for (y in from until until) for (x in 0 until gw) {
                var n = 0
                var total = 0f
                var kernelTotal = 0f
                for (dy in -r..r) {
                    val yy = y + dy
                    if (yy !in 0 until gh) continue
                    for (dx in -r..r) {
                        val xx = x + dx
                        if (xx !in 0 until gw) continue
                        val kw = kernel[(dy + r) * side + dx + r]
                        kernelTotal += kw
                        val j = yy * gw + xx
                        val wj = weights[j] * kw
                        if (wj <= 0f) continue
                        val vj = values[j]
                        var p = n
                        while (p > 0 && v[p - 1] > vj) {
                            v[p] = v[p - 1]
                            wv[p] = wv[p - 1]
                            p--
                        }
                        v[p] = vj
                        wv[p] = wj
                        n++
                        total += wj
                    }
                }
                val i = y * gw + x
                meanWeight[i] = total / kernelTotal
                if (n == 0) {
                    median[i] = Float.NaN
                    continue
                }
                var below = 0f
                var p = 0
                while (p < n - 1 && below + wv[p] < total / 2) {
                    below += wv[p]
                    p++
                }
                median[i] = v[p]
            }
        }
        return median to meanWeight
    }

    // ---------------------------------------------------------------- pass 2: compose

    /** Adds aligned frame [k] to every pixel whose depth is near k. */
    fun compose(k: Int, pixels: IntArray) {
        check(analyzed) { "analyze() first" }
        val w = width
        val kf = k.toFloat()
        val ar = acc[0]
        val ag = acc[1]
        val ab = acc[2]
        parallel.forRows(height) { from, until ->
            val rowDepth = FloatArray(w)
            val rowRadius = FloatArray(w)
            for (y in from until until) {
                sampleRow(y, rowDepth, rowRadius)
                val row = y * w
                for (x in 0 until w) {
                    // A tent of half-width radius around the depth. On detail (radius ≈ 1) its sides are
                    // steeper: mostly the nearest frame, two frames blending only around the midpoint
                    // between them — two frames each a little out of focus average to something softer
                    // than either.
                    val r = rowRadius[x]
                    val ramp = 0.5f * r * (DETAIL_BLEND + (1f - DETAIL_BLEND) * (r - 1f).coerceIn(0f, 1f))
                    val wgt = min(1f, 0.5f + (0.5f * r - abs(rowDepth[x] - kf)) / (2f * ramp))
                    if (wgt <= 0f) continue
                    val i = row + x
                    val p = pixels[i]
                    val cw = wgt * COLOR_SCALE
                    ar[i] = (ar[i] + (((p shr 16) and 0xFF) * cw + 0.5f).toInt()).toShort()
                    ag[i] = (ag[i] + (((p shr 8) and 0xFF) * cw + 0.5f).toInt()).toShort()
                    ab[i] = (ab[i] + ((p and 0xFF) * cw + 0.5f).toInt()).toShort()
                    accWeight[i] = (accWeight[i] + (wgt * WEIGHT_SCALE + 0.5f).toInt()).toShort()
                }
            }
        }
    }

    /** Depth and radius for every pixel of row [y], interpolated between cell centres. */
    private fun sampleRow(y: Int, depthOut: FloatArray, radiusOut: FloatArray) {
        val gyf = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, gridHeight - 1f)
        val gy0 = floor(gyf).toInt()
        val gy1 = min(gy0 + 1, gridHeight - 1)
        val fy = gyf - gy0
        for (x in 0 until width) {
            val gxf = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, gridWidth - 1f)
            val gx0 = floor(gxf).toInt()
            val gx1 = min(gx0 + 1, gridWidth - 1)
            val fx = gxf - gx0
            val i00 = gy0 * gridWidth + gx0
            val i01 = gy0 * gridWidth + gx1
            val i10 = gy1 * gridWidth + gx0
            val i11 = gy1 * gridWidth + gx1
            depthOut[x] = lerp(lerp(depth[i00], depth[i01], fx), lerp(depth[i10], depth[i11], fx), fy)
            radiusOut[x] = lerp(lerp(radius[i00], radius[i01], fx), lerp(radius[i10], radius[i11], fx), fy)
        }
    }

    /** The result so far, at most [maxWidth] wide. Pixels no frame has reached yet show [current]. */
    fun preview(maxWidth: Int, current: IntArray): RgbImage {
        val step = max(1, ceil(width.toDouble() / maxWidth).toInt())
        val pw = width / step
        val ph = height / step
        val out = IntArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val i = (y * step) * width + x * step
            out[y * pw + x] = if (accWeight[i] > 0) pixel(i) else current[i]
        }
        return RgbImage(pw, ph, out)
    }

    /** Writes the fused image into [out] (packed ARGB, [width] × [height]). */
    fun result(out: IntArray) {
        parallel.forRows(height) { from, until ->
            for (i in from * width until until * width) out[i] = pixel(i)
        }
    }

    /** The depth map as a grey image (near = dark, far = light), for checking. */
    fun depthImage(): RgbImage {
        val out = IntArray(gridWidth * gridHeight) { i ->
            val v = (depth[i] / max(frames - 1, 1) * 255).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return RgbImage(gridWidth, gridHeight, out)
    }

    private fun pixel(i: Int): Int {
        val wsum = accWeight[i].toInt()
        if (wsum <= 0) return 0xFF000000.toInt()
        val f = WEIGHT_SCALE / COLOR_SCALE / wsum
        val r = (acc[0][i] * f + 0.5f).toInt().coerceIn(0, 255)
        val g = (acc[1][i] * f + 0.5f).toInt().coerceIn(0, 255)
        val b = (acc[2][i] * f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 3×3 minimum filter on a cell-grid map. */
    private fun erode(src: FloatArray): FloatArray {
        val gw = gridWidth
        val gh = gridHeight
        val out = FloatArray(src.size)
        parallel.forRows(gh) { from, until ->
            for (y in from until until) for (x in 0 until gw) {
                var m = Float.MAX_VALUE
                for (yy in max(y - 1, 0)..min(y + 1, gh - 1)) for (xx in max(x - 1, 0)..min(x + 1, gw - 1)) m = min(m, src[yy * gw + xx])
                out[y * gw + x] = m
            }
        }
        return out
    }

    /** Separable Gaussian blur of a cell-grid map, edges clamped. */
    private fun blur(src: FloatArray, sigma: Float): FloatArray {
        val radius = ceil(sigma * 3).toInt()
        val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
        val norm = kernel.sum()
        for (i in kernel.indices) kernel[i] /= norm
        val gw = gridWidth
        val gh = gridHeight
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        parallel.forRows(gh) { from, until ->
            for (y in from until until) for (x in 0 until gw) {
                var s = 0f
                for (t in -radius..radius) s += kernel[t + radius] * src[y * gw + (x + t).coerceIn(0, gw - 1)]
                tmp[y * gw + x] = s
            }
        }
        parallel.forRows(gh) { from, until ->
            for (y in from until until) for (x in 0 until gw) {
                var s = 0f
                for (t in -radius..radius) s += kernel[t + radius] * tmp[(y + t).coerceIn(0, gh - 1) * gw + x]
                out[y * gw + x] = s
            }
        }
        return out
    }

    companion object {
        private const val MIN_CELL = 6

        /** Coarse detail is measured on block means of this many pixels square. */
        private const val BLOCK = 4

        /** Guidance: full weight within the blending radius + this, falling to the floor over the falloff. */
        private const val GUIDE_MARGIN = 1f
        private const val GUIDE_FALLOFF = 2f
        private const val GUIDE_FLOOR = 0.1f

        /** A curve whose peak is under this many times the noise has no shape worth guiding by. */
        private const val SHAPE_NOISE = 2f

        /** Coarse detail's confidence counts this much against fine detail's. */
        private const val COARSE_WEIGHT = 0.5f
        private const val COARSE_NOISE_FLOOR = 8f

        /** Coarse curves average this many cells around (3×3), like fine ones. */
        private const val COARSE_REACH = 1

        /** Brightness changing faster than this per frame (+ relative) rules out coarse detail. */
        private const val COARSE_RAMP_TOLERANCE = 1f

        /** Coarse detail counts only up to this (+ relative) brighter than the cell's usual brightness. */
        private const val SETTLED_TOLERANCE = 8f
        private const val SETTLED_TOLERANCE_RELATIVE = 0.15f
        private const val COARSE_RAMP_TOLERANCE_RELATIVE = 0.01f
        private const val MAX_CELL = 64
        private const val PROFILE_BUDGET_BYTES = 48L * 1024 * 1024
        private const val SPREAD = 2

        /** Cell sharpness is stored as RMS Laplacian × 32 in a short (Laplacian ≤ 1020). */
        private const val ENERGY_SCALE = 32.0

        /** The "out of focus" level of a curve: this low percentile of its values. */
        private const val BASELINE_PERCENTILE = 0.3

        /** Keeps confidence sane where everything is dark and flat (energy units). */
        private const val NOISE_FLOOR = 32f

        private const val MIN_CONFIDENCE = 0.25f
        private const val NEAR_SIGMA = 1.5f
        private const val MEDIAN_RADIUS = 2

        private const val FAR_SIGMA = 6f
        private const val NEAR_WEIGHT_FULL = 0.5f

        /** Noise is measured in this many brightness bands. */
        private const val LUMA_BINS = 16
        private const val NOISE_HISTOGRAM_STEP = 8
        private const val NOISE_HISTOGRAM_BINS = 4096
        private const val MIN_NOISE_CELLS = 200

        /** A frame's noise level: this low percentile of its cells' sharpness, per brightness band. */
        private const val NOISE_PERCENTILE = 0.3f

        /** Noise is subtracted from a cell's own detail with this margin. */
        private const val NOISE_MARGIN = 1.3f

        /** A cell's own detail is trusted from this many times the noise level, fully at the second. */
        private const val TRUST_OWN_FROM = 3f
        private const val TRUST_OWN_AT = 6f

        /** Brightness changing faster than this per frame (+ relative) is glow from a blurred object. */
        private const val RAMP_TOLERANCE = 2f
        private const val RAMP_TOLERANCE_RELATIVE = 0.03f

        /** A cell "looks the same" in two frames when their brightness differs by at most this (+ relative). */
        private const val LUMA_TOLERANCE = 4f
        private const val LUMA_TOLERANCE_RELATIVE = 0.04f

        /** …or at most this fraction of the noise level less sharp. */
        private const val PLATEAU_SLACK = 0.5f

        /** Frames at least this fraction of a cell's best sharpness count as "just as sharp". */
        private const val PLATEAU = 0.85f

        /** Flat areas blend up to 2 × this + 1 frames. */
        private const val MAX_RADIUS = 8f

        /** On detail, how much of the way between two frames they blend (1 = all of it, linearly). */
        private const val DETAIL_BLEND = 0.4f

        /** Accumulators: colour × weight × 16 and weight × 2048 both stay below 32 767 for radius ≤ 8. */
        private const val COLOR_SCALE = 16f
        private const val WEIGHT_SCALE = 2048f

        /** Working memory for a [width] × [height] image, with headroom. */
        fun bytesNeeded(width: Int, height: Int): Long = width.toLong() * height * 16 + PROFILE_BUDGET_BYTES

        /**
         * Memory a depth map keeps while guiding another method (its per-cell curves). Measuring needs
         * more, but less than a pyramid, and it's freed before the pyramid is built.
         */
        val GUIDE_BYTES: Long = PROFILE_BUDGET_BYTES / 4

        private fun luma(p: Int): Int = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
