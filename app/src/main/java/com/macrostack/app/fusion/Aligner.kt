package com.macrostack.app.fusion

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds how one frame is placed relative to another — focus breathing, OIS, vibration, and the small
 * tilt of a phone held by hand — using inverse-compositional Lucas–Kanade on an image pyramid, coarse
 * to fine: scale, rotation and shift on the coarser levels, and on the finest, where there is detail
 * enough to pin them down, also stretch and shear (an affine transform).
 *
 * Works best between neighbouring frames of a stack, which look almost the same.
 */
class Aligner(private val maxLevels: Int = 4, private val parallel: Parallel? = null) {

    /**
     * An image ready to be aligned: its pyramid, finest first, softened for a robust search; and the
     * full-size image softened less, for the final, precise fit.
     */
    class Prepared internal constructor(internal val pyramid: List<GrayImage>, internal val fine: GrayImage) {
        val width: Int get() = pyramid[0].width
        val height: Int get() = pyramid[0].height
    }

    /** What a refinement may change: scale, rotation and shift (4 parameters), or all six. */
    private enum class Model(val params: Int) { SIMILARITY(4), AFFINE(6) }

    fun prepare(image: GrayImage): Prepared {
        // Neighbouring frames are sharp in different places. Softening both makes alignment follow the
        // shared structure instead of fine texture that only one of them shows; the final fit softens
        // less, as the detail there pins the frames down most precisely.
        var fine = image
        repeat(FINE_BLUR_PASSES) { fine = fine.smooth() }
        var base = fine
        repeat(PRE_BLUR_PASSES - FINE_BLUR_PASSES) { base = base.smooth() }
        val levels = mutableListOf(base)
        while (levels.size < maxLevels && levels.last().width >= 2 * MIN_LEVEL_SIZE && levels.last().height >= 2 * MIN_LEVEL_SIZE) {
            levels += levels.last().half()
        }
        return Prepared(levels, fine)
    }

    /**
     * Returns W (in centred coordinates) such that image(W(x)) ≈ template(x): where each point of
     * [template] appears in [image]. Returns null if it doesn't settle on something plausible.
     */
    fun estimate(template: Prepared, image: Prepared, initial: Affine = Affine.IDENTITY): Affine? {
        val levels = min(template.pyramid.size, image.pyramid.size)
        var p = initial.scaledBy(1.0 / (1 shl (levels - 1)))
        for (level in levels - 1 downTo 1) {
            p = refine(template.pyramid[level], image.pyramid[level], p, step = 1, Model.SIMILARITY) ?: return null
            p = p.scaledBy(2.0)
        }
        val t = template.fine
        val img = image.fine
        // Stretch and shear only if they come out small (a tilt between neighbouring shots is tiny);
        // otherwise the frames didn't have the detail to pin them down.
        val affine = refine(t, img, p, step = 3, Model.AFFINE, robust = true)
        if (affine != null && plausible(affine, t.width) && affine.distortion < MAX_DISTORTION) return affine
        return refine(t, img, p, step = 3, Model.SIMILARITY, robust = true)?.takeIf { plausible(it, t.width) }
    }

    /**
     * How much brighter [template] is than [image] where they overlap ([w] maps template → image
     * coordinates, as [estimate] returns it): the ratio of their mean brightness over the middle of
     * the frame. Blur spreads light around but keeps its total, so frames focused differently still
     * compare fairly; what's left is exposure (flickering lights, say).
     */
    fun brightnessRatio(template: Prepared, image: Prepared, w: Affine): Double {
        val t = template.fine
        val img = image.fine
        val cx = (t.width - 1) / 2.0
        val cy = (t.height - 1) / 2.0
        val maxU = img.width - 1.001
        val maxV = img.height - 1.001
        var sumT = 0.0
        var sumI = 0.0
        for (y in t.height / 10 until t.height - t.height / 10 step BRIGHTNESS_STEP) {
            for (x in t.width / 10 until t.width - t.width / 10 step BRIGHTNESS_STEP) {
                val u = w.mapX(x - cx, y - cy) + cx
                val v = w.mapY(x - cx, y - cy) + cy
                if (u < 0 || v < 0 || u >= maxU || v >= maxV) continue
                sumT += t[x, y]
                sumI += img.sample(u.toFloat(), v.toFloat())
            }
        }
        return if (sumT > 0 && sumI > 0) sumT / sumI else 1.0
    }

    private fun plausible(p: Affine, width: Int): Boolean =
        abs(p.scale - 1) < 0.1 && abs(p.rotationRadians) < 0.1 && hypot(p.tx, p.ty) < 0.15 * width

    /**
     * Gauss–Newton iterations at one pyramid level. [robust]: once settled, the samples that still
     * don't match (something that moved, a highlight that changed) are weighted down — Tukey's
     * biweight — and it settles again without them.
     */
    private fun refine(t: GrayImage, img: GrayImage, start: Affine, step: Int, model: Model, robust: Boolean = false): Affine? {
        val w = t.width
        val h = t.height
        val cx = (w - 1) / 2.0
        val cy = (h - 1) / 2.0
        val margin = 2
        val np = model.params

        // Template samples and steepest-descent images: ∇T · ∂W/∂p at identity.
        val capacity = ((w - 2 * margin) / step + 1) * ((h - 2 * margin) / step + 1)
        val xs = FloatArray(capacity)
        val ys = FloatArray(capacity)
        val tv = FloatArray(capacity)
        val sd = Array(np) { FloatArray(capacity) }
        var n = 0
        var y = margin
        while (y < h - margin) {
            var x = margin
            while (x < w - margin) {
                val gx = (t[x + 1, y] - t[x - 1, y]) * 0.5f
                val gy = (t[x, y + 1] - t[x, y - 1]) * 0.5f
                if (gx * gx + gy * gy > MIN_GRADIENT_SQ) {
                    val xc = (x - cx).toFloat()
                    val yc = (y - cy).toFloat()
                    when (model) {
                        Model.SIMILARITY -> {
                            sd[0][n] = gx * xc + gy * yc // scale
                            sd[1][n] = -gx * yc + gy * xc // rotation
                            sd[2][n] = gx
                            sd[3][n] = gy
                        }
                        Model.AFFINE -> {
                            sd[0][n] = gx * xc
                            sd[1][n] = gx * yc
                            sd[2][n] = gy * xc
                            sd[3][n] = gy * yc
                            sd[4][n] = gx
                            sd[5][n] = gy
                        }
                    }
                    xs[n] = xc
                    ys[n] = yc
                    tv[n] = t[x, y]
                    n++
                }
                x += step
            }
            y += step
        }
        if (n < 4 * MIN_SAMPLES) return start // too little texture at this level; keep what we have

        val weight = FloatArray(n) { 1f }
        val iv = FloatArray(n)
        val hessian = DoubleArray(np * np)
        val rhs = DoubleArray(np)
        var gain = 1.0
        var offset = 0.0
        // Sums over the samples are taken in chunks on all cores, then added up in chunk order (so the
        // result doesn't depend on which thread finished first).
        val chunks = if (parallel == null || n < MIN_PARALLEL_SAMPLES) 1 else CHUNKS
        val partial = Array(chunks) { DoubleArray(max(np * np, 6)) }
        fun forChunks(body: (chunk: Int, from: Int, until: Int) -> Unit) {
            fun run(c: Int) = body(c, (n.toLong() * c / chunks).toInt(), (n.toLong() * (c + 1) / chunks).toInt())
            if (chunks == 1) run(0) else parallel!!.forRows(chunks) { a, b -> for (c in a until b) run(c) }
        }
        fun sumPartials(size: Int, out: DoubleArray) {
            out.fill(0.0, 0, size)
            for (c in 0 until chunks) for (j in 0 until size) out[j] += partial[c][j]
        }
        val totals = DoubleArray(6)

        /** Samples the image at W(p) into [iv] (NaN outside), then matches its brightness and contrast. */
        fun sampleAt(p: Affine): Boolean {
            val pa = p.a.toFloat()
            val pb = p.b.toFloat()
            val pc = p.c.toFloat()
            val pd = p.d.toFloat()
            val ptx = (p.tx + cx).toFloat()
            val pty = (p.ty + cy).toFloat()
            val maxU = w - 1.001f
            val maxV = h - 1.001f
            forChunks { chunk, from, until ->
                var used = 0
                var sw = 0.0
                var sumI = 0.0
                var sumT = 0.0
                var sumII = 0.0
                var sumIT = 0.0
                for (i in from until until) {
                    val u = pa * xs[i] + pb * ys[i] + ptx
                    val v = pc * xs[i] + pd * ys[i] + pty
                    if (u < 0f || v < 0f || u >= maxU || v >= maxV) {
                        iv[i] = Float.NaN
                        continue
                    }
                    val value = img.sample(u, v)
                    iv[i] = value
                    used++
                    val wi = weight[i].toDouble()
                    sw += wi
                    sumI += wi * value
                    sumT += wi * tv[i]
                    sumII += wi * value * value
                    sumIT += wi * value * tv[i]
                }
                val out = partial[chunk]
                out[0] = used.toDouble()
                out[1] = sw
                out[2] = sumI
                out[3] = sumT
                out[4] = sumII
                out[5] = sumIT
            }
            sumPartials(6, totals)
            val used = totals[0]
            val sw = totals[1]
            val sumI = totals[2]
            val sumT = totals[3]
            val sumII = totals[4]
            val sumIT = totals[5]
            if (used < MIN_SAMPLES || sw <= 0) return false
            // The two frames may differ in brightness and contrast (vignetting and exposure change with
            // focus): compare them after matching those, template ≈ gain · image + offset. Otherwise the
            // difference pulls the geometry off.
            val meanI = sumI / sw
            val meanT = sumT / sw
            val varI = sumII / sw - meanI * meanI
            gain = if (varI > 1e-6) ((sumIT / sw - meanI * meanT) / varI).coerceIn(MIN_GAIN, MAX_GAIN) else 1.0
            offset = meanT - gain * meanI
            return true
        }

        var p = start
        for (phase in 0 until if (robust) 2 else 1) {
            if (phase == 1) {
                // Tukey's biweight, scaled by the typical mismatch (the median of a subsample).
                if (!sampleAt(p)) return null
                val every = max(1, n / ROBUST_SUBSAMPLE)
                val residuals = (0 until n step every).mapNotNull { i ->
                    if (iv[i].isNaN()) null else abs(gain * iv[i] + offset - tv[i]).toFloat()
                }.sorted()
                if (residuals.isEmpty()) return p
                val limit = max(TUKEY * 1.4826f * residuals[residuals.size / 2], MIN_ROBUST_LIMIT)
                val g = gain
                val o = offset
                forChunks { _, from, until ->
                    for (i in from until until) {
                        val value = iv[i]
                        if (value.isNaN()) continue
                        val r = ((g * value + o - tv[i]) / limit).toFloat()
                        weight[i] = if (abs(r) >= 1f) 0f else (1 - r * r) * (1 - r * r)
                    }
                }
            }
            forChunks { chunk, from, until ->
                val out = partial[chunk]
                out.fill(0.0)
                for (i in from until until) {
                    val wi = weight[i]
                    if (wi == 0f) continue
                    for (r in 0 until np) {
                        val a = wi * sd[r][i]
                        for (c in r until np) out[r * np + c] += (a * sd[c][i]).toDouble()
                    }
                }
            }
            sumPartials(np * np, hessian)
            for (r in 0 until np) for (c in 0 until r) hessian[r * np + c] = hessian[c * np + r]

            for (iteration in 0 until MAX_ITERATIONS) {
                if (!sampleAt(p)) return null
                val g = gain
                val o = offset
                forChunks { chunk, from, until ->
                    val out = partial[chunk]
                    out.fill(0.0, 0, np)
                    for (i in from until until) {
                        val value = iv[i]
                        if (value.isNaN()) continue
                        val e = weight[i] * (g * value + o - tv[i])
                        for (r in 0 until np) out[r] += sd[r][i] * e
                    }
                }
                sumPartials(np, rhs)
                val dp = solve(hessian, rhs, np) ?: return null
                // Inverse-compositional update: W(p) ← W(p) ∘ W(Δp)⁻¹
                val delta = when (model) {
                    Model.SIMILARITY -> Affine(1 + dp[0], -dp[1], dp[1], 1 + dp[0], dp[2], dp[3])
                    Model.AFFINE -> Affine(1 + dp[0], dp[1], dp[2], 1 + dp[3], dp[4], dp[5])
                }
                p = p.after(delta.inverse())
                val linear = np - 2 // the last two parameters are the shift
                if ((0 until linear).all { abs(dp[it]) < 1e-5 } && abs(dp[linear]) < 1e-2 && abs(dp[linear + 1]) < 1e-2) break
            }
        }
        return p
    }

    /** Solves the [n]×[n] system [m]·x = [b] by Gaussian elimination with partial pivoting. */
    private fun solve(m: DoubleArray, b: DoubleArray, n: Int): DoubleArray? {
        val a = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) m[r * n + c] else b[r] } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-12) return null
            val tmp = a[col]
            a[col] = a[pivot]
            a[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..n) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(n) { r -> a[r][n] / a[r][r] }
    }

    private companion object {
        const val PRE_BLUR_PASSES = 2
        const val FINE_BLUR_PASSES = 1
        const val MIN_LEVEL_SIZE = 48
        const val MAX_ITERATIONS = 30
        const val MIN_SAMPLES = 50
        const val MIN_GRADIENT_SQ = 0.25f
        const val MIN_GAIN = 0.5
        const val MAX_GAIN = 2.0

        /** Robust fit: samples mismatched by more than this many typical mismatches get no weight… */
        const val TUKEY = 9f

        /** …but never by less than this (grey levels), for frames that match almost perfectly. */
        const val MIN_ROBUST_LIMIT = 2f

        /** The typical mismatch is measured on about this many samples. */
        const val ROBUST_SUBSAMPLE = 20_000

        /** [brightnessRatio] compares every this many pixels, each way. */
        const val BRIGHTNESS_STEP = 4

        /** Sums over at least this many samples are split into [CHUNKS] pieces for all cores. */
        const val MIN_PARALLEL_SAMPLES = 20_000
        const val CHUNKS = 32

        /** Most stretch or shear accepted between two neighbouring frames (relative). */
        const val MAX_DISTORTION = 0.01
    }
}
