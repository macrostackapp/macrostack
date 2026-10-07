package com.macrostack.app.fusion

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a focus stack into one image that is sharp throughout, the way desktop stacking software
 * does:
 *
 * 1. **Align** — every neighbouring pair of frames is registered on small greyscale copies (scale,
 *    rotation, shift — focus breathing, OIS, vibration — and the slight stretch and shear of a
 *    hand-held tilt). The steps are chained and referred to the middle frame, and the output is
 *    cropped to the area every frame covers.
 * 2. **Fuse** — each full-resolution frame is warped onto the middle frame and fed, one at a time
 *    while the next frame is already being decoded, to [DepthMapFuser] (two sweeps: measure, then
 *    compose) or [PyramidFuser] (one sweep).
 *
 * Pure Kotlin with no Android dependencies, so it runs (and is tested) on the desktop too.
 */
class StackFusion(private val parallel: Parallel, private val prefetcher: ExecutorService? = null) {

    interface Frame {
        /**
         * A small greyscale copy for alignment — the same size for every frame: the full frame
         * divided by [alignSampleSize].
         */
        fun loadSmall(): GrayImage

        /** Decodes the frame at full resolution divided by [sampleSize]. */
        fun openFull(sampleSize: Int): FullFrame
    }

    interface FullFrame : PixelSource, AutoCloseable

    enum class Phase { ALIGNING, MEASURING, STACKING, FINISHING }

    enum class Method {
        /**
         * Depth map (like Zerene Stacker's DMap / Helicon Focus Method B): least noise, and no ghosted
         * outlines when something moved. The default.
         */
        DEPTH_MAP,

        /**
         * Laplacian pyramid (like Zerene's PMax / Helicon's Method C): the finest detail where
         * structures overlap, e.g. hairs and bristles.
         */
        PYRAMID,
    }

    interface Listener {
        fun onProgress(phase: Phase, done: Int, total: Int) {}
        fun onPreview(preview: RgbImage) {}
        val isCancelled: Boolean get() = false
    }

    class CancelledException : RuntimeException("Stacking cancelled")

    class Result(
        val image: RgbImage,
        /** Per frame: reference (middle frame) coordinates → frame coordinates, at output scale. */
        val transforms: List<Affine>,
        val crop: IntRect,
        val sampleSize: Int,
        /** Neighbour pairs that couldn't be aligned and were assumed not to move. */
        val unalignedPairs: Int,
    )

    /**
     * Runs the whole stack. [memoryBudget] is how many bytes the working buffers may use; frames are
     * decoded at a lower resolution when full size wouldn't fit.
     */
    fun run(
        frames: List<Frame>,
        listener: Listener,
        memoryBudget: Long = Long.MAX_VALUE,
        method: Method = Method.DEPTH_MAP,
    ): Result {
        require(frames.size >= 2) { "A stack needs at least two frames" }
        val n = frames.size

        // 1. Align neighbours on small copies; chain to frame 0, then refer everything to the middle.
        val aligner = Aligner(parallel = parallel)
        val steps = ArrayList<Affine?>(n) // steps[k]: frame k−1 → frame k (null = couldn't align)
        val ratios = DoubleArray(n) { 1.0 } // ratios[k]: how much brighter frame k−1 is than frame k
        var previous: Aligner.Prepared? = null
        var lastStep = Affine.IDENTITY
        var smallWidth = 0
        for ((k, frame) in frames.withIndex()) {
            checkCancelled(listener)
            val small = frame.loadSmall()
            val prepared = aligner.prepare(small)
            if (k == 0) {
                smallWidth = small.width
                steps += Affine.IDENTITY
            } else {
                val step = aligner.estimate(previous!!, prepared, lastStep)
                    ?: aligner.estimate(previous, prepared, Affine.IDENTITY)
                steps += step
                if (step != null) {
                    lastStep = step
                    ratios[k] = aligner.brightnessRatio(previous, prepared, step).coerceIn(MIN_RATIO, 1 / MIN_RATIO)
                }
            }
            previous = prepared
            listener.onProgress(Phase.ALIGNING, k + 1, n)
        }
        val unaligned = steps.count { it == null }
        lastSteps = steps
        val cleaned = cleanSteps(steps)
        val toFirst = ArrayList<Affine>(n)
        toFirst += Affine.IDENTITY
        // Each step maps frame k−1 → frame k; toFirst[k−1] maps frame 0 → frame k−1.
        for (k in 1 until n) toFirst += cleaned[k].after(toFirst[k - 1])
        val reference = n / 2
        val fromReference = toFirst[reference].inverse()
        val smallTransforms = toFirst.map { it.after(fromReference) }
        // Exposure: what makes each frame as bright as the reference, so that a frame that came out a
        // little darker (lights flicker) can't show as a patch where it's used.
        val chained = DoubleArray(n)
        chained[0] = 1.0
        for (k in 1 until n) chained[k] = chained[k - 1] * ratios[k]
        val gains = chained.map { it / chained[reference] }
        lastGains = gains

        // 2. Open the first frame to learn the working size; shrink if it wouldn't fit in memory.
        fun needed(w: Int, h: Int) = when (method) {
            Method.DEPTH_MAP -> DepthMapFuser.bytesNeeded(w, h)
            Method.PYRAMID -> bytesNeeded(w, h) + DepthMapFuser.GUIDE_BYTES
        }
        var sampleSize = 1
        var first = frames[0].openFull(sampleSize)
        while (needed(first.width, first.height) > memoryBudget && sampleSize < MAX_SAMPLE_SIZE) {
            first.close()
            sampleSize *= 2
            first = frames[0].openFull(sampleSize)
        }
        val fullWidth = first.width
        val fullHeight = first.height
        val transforms = smallTransforms.map { it.scaledBy(fullWidth.toDouble() / smallWidth) }
        val crop = commonArea(fullWidth, fullHeight, transforms)
        val aligned = IntArray(crop.width * crop.height)
        val previewEvery = max(1, n / PREVIEW_COUNT)
        val pass = AlignedPass(frames, sampleSize, transforms, gains, crop, fullWidth, fullHeight, aligned, listener)

        // 3. Fuse.
        when (method) {
            Method.PYRAMID -> {
                // A depth map first, to guide the pyramid away from noise and glow.
                val depthMap = DepthMapFuser(crop.width, crop.height, n, parallel)
                depthMap.keepRawForTests = keepFuserForTests
                if (keepFuserForTests) lastDepthFuser = depthMap
                pass.run(first, Phase.MEASURING) { k -> depthMap.measure(k, aligned) }
                checkCancelled(listener)
                depthMap.analyze()
                lastDepthMap = depthMap.depthImage()
                val weights = FloatArray(depthMap.gridWidth * depthMap.gridHeight)
                val guide = PyramidFuser.Guide(depthMap.cell, depthMap.gridWidth, weights)
                val fuser = PyramidFuser(crop.width, crop.height, parallel)
                val previewLevel = fuser.levelForWidth(PREVIEW_MAX_WIDTH)
                pass.run(frames[0].openFull(sampleSize), Phase.STACKING) { k ->
                    depthMap.guidance(k, weights)
                    fuser.add(aligned, guide)
                    if ((k + 1) % previewEvery == 0 && k + 1 < n) listener.onPreview(fuser.preview(previewLevel))
                }
                listener.onProgress(Phase.FINISHING, n, n)
                fuser.result(aligned)
            }

            Method.DEPTH_MAP -> {
                val fuser = DepthMapFuser(crop.width, crop.height, n, parallel)
                fuser.keepRawForTests = keepFuserForTests
                if (keepFuserForTests) lastDepthFuser = fuser
                pass.run(first, Phase.MEASURING) { k -> fuser.measure(k, aligned) }
                checkCancelled(listener)
                fuser.analyze()
                pass.run(frames[0].openFull(sampleSize), Phase.STACKING) { k ->
                    fuser.compose(k, aligned)
                    if ((k + 1) % previewEvery == 0 && k + 1 < n) listener.onPreview(fuser.preview(PREVIEW_MAX_WIDTH, aligned))
                }
                listener.onProgress(Phase.FINISHING, n, n)
                lastDepthMap = fuser.depthImage()
                fuser.result(aligned)
            }
        }
        return Result(RgbImage(crop.width, crop.height, aligned), transforms, crop, sampleSize, unaligned)
    }

    /** The depth map of the last DEPTH_MAP run (for tests and diagnostics). */
    var lastDepthMap: RgbImage? = null
        private set

    /** Tests: each neighbouring pair's measured alignment step in the last run (before smoothing). */
    internal var lastSteps: List<Affine?> = emptyList()
        private set

    /** Tests: the brightness each frame was scaled by in the last run. */
    internal var lastGains: List<Double> = emptyList()
        private set

    /** Tests: keep the depth-map fuser of the last run, for [DepthMapFuser.cellReport]. */
    internal var keepFuserForTests = false
    internal var lastDepthFuser: DepthMapFuser? = null
        private set

    /** One sweep through the stack: decode (next frame in the background), align, hand over. */
    private inner class AlignedPass(
        private val frames: List<Frame>,
        private val sampleSize: Int,
        private val transforms: List<Affine>,
        private val gains: List<Double>,
        private val crop: IntRect,
        private val fullWidth: Int,
        private val fullHeight: Int,
        private val aligned: IntArray,
        private val listener: Listener,
    ) {
        fun run(first: FullFrame, phase: Phase, body: (k: Int) -> Unit) {
            val n = frames.size
            var current = first
            var pending: Future<FullFrame>? = null
            try {
                for (k in 0 until n) {
                    checkCancelled(listener)
                    pending = if (k + 1 < n) prefetcher?.submit<FullFrame> { frames[k + 1].openFull(sampleSize) } else null
                    current.use { frame ->
                        require(frame.width == fullWidth && frame.height == fullHeight) {
                            "Frame ${k + 1} is ${frame.width}×${frame.height}, expected ${fullWidth}×$fullHeight"
                        }
                        Warp.apply(frame, transforms[k], aligned, crop.width, crop.height, crop.left, crop.top, parallel)
                    }
                    applyGain(gains[k])
                    body(k)
                    listener.onProgress(phase, k + 1, n)
                    if (k + 1 < n) {
                        current = pending?.get() ?: frames[k + 1].openFull(sampleSize)
                        pending = null
                    }
                }
            } catch (e: Throwable) {
                runCatching { current.close() }
                pending?.let { future -> runCatching { future.get().close() } }
                throw e
            }
        }

        /** Scales the aligned frame's brightness by [gain]. */
        private fun applyGain(gain: Double) {
            if (abs(gain - 1) < MIN_GAIN_CHANGE) return
            val f = (gain * 1024).roundToInt()
            val w = crop.width
            parallel.forRows(crop.height) { from, until ->
                for (i in from * w until until * w) {
                    val p = aligned[i]
                    val r = min(255, (((p shr 16) and 0xFF) * f + 512) shr 10)
                    val g = min(255, (((p shr 8) and 0xFF) * f + 512) shr 10)
                    val b = min(255, ((p and 0xFF) * f + 512) shr 10)
                    aligned[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    private fun checkCancelled(listener: Listener) {
        if (listener.isCancelled) throw CancelledException()
    }

    companion object {
        private const val PREVIEW_MAX_WIDTH = 1100
        private const val PREVIEW_COUNT = 12
        private const val MAX_SAMPLE_SIZE = 8

        /** Neighbouring frames differ in exposure by at most this factor (beyond it, it's a mismatch). */
        private const val MIN_RATIO = 0.7

        /** Exposure differences smaller than this aren't corrected. */
        private const val MIN_GAIN_CHANGE = 0.002

        /** Alignment copies are at least this long on their long side. */
        private const val ALIGN_SIZE = 2000

        /**
         * How much to shrink a [width] × [height] frame (a power of two) for alignment: as much as
         * keeps it [ALIGN_SIZE] px or more. Smaller copies would cost alignment precision.
         */
        fun alignSampleSize(width: Int, height: Int): Int {
            var s = 1
            while (max(width, height) / (2 * s) >= ALIGN_SIZE && s < MAX_SAMPLE_SIZE) s *= 2
            return s
        }

        /**
         * Each pair's measured step, kept as it is: on alignment copies [ALIGN_SIZE] px wide single
         * measurements are precise, and a straight-line fit of focus breathing misplaced frames in
         * real stacks (the camera moves a little, and breathing isn't quite even). Only a step whose
         * scale is far from its neighbours' — a failed measurement, say between two frames with
         * nothing in focus — takes their median scale; a pair that couldn't be aligned at all also
         * gets no shift.
         */
        fun cleanSteps(steps: List<Affine?>): List<Affine> {
            val logScale = steps.map { step -> step?.let { ln(it.scale) } }
            val local = DoubleArray(steps.size) { k ->
                val around = (max(1, k - STEP_WINDOW)..min(steps.size - 1, k + STEP_WINDOW))
                    .filter { it != k }.mapNotNull { logScale[it] }.sorted()
                if (around.isEmpty()) 0.0 else around[around.size / 2]
            }
            val deviations = (1 until steps.size).mapNotNull { k -> logScale[k]?.let { abs(it - local[k]) } }.sorted()
            val spread = if (deviations.isEmpty()) 0.0 else deviations[deviations.size / 2]
            val limit = max(OUTLIER_SPREADS * spread, MIN_OUTLIER_SCALE)
            return steps.mapIndexed { k, step ->
                when {
                    k == 0 -> Affine.IDENTITY
                    step == null -> Affine.IDENTITY.magnifiedBy(exp(local[k]))
                    abs(logScale[k]!! - local[k]) > limit -> {
                        step.magnifiedBy(exp(local[k]) / step.scale)
                    }
                    else -> step
                }
            }
        }

        /** [cleanSteps]: a step's neighbours are the steps up to this far on either side. */
        private const val STEP_WINDOW = 3

        /** [cleanSteps]: a scale this many times the typical deviation from the neighbours' is a failure… */
        private const val OUTLIER_SPREADS = 5.0

        /** …and so is one at least this far off (log scale, ≈ 0.2 %). */
        private const val MIN_OUTLIER_SCALE = 2e-3

        /** Working memory for fusing a [width] × [height] image (see [PyramidFuser]), with headroom. */
        fun bytesNeeded(width: Int, height: Int): Long = width.toLong() * height * 26

        /**
         * The part of the reference frame that every frame covers, so the result has no empty or
         * smeared edges. [transforms] map reference → frame coordinates (centred).
         */
        fun commonArea(width: Int, height: Int, transforms: List<Affine>): IntRect {
            val cx = (width - 1) / 2.0
            val cy = (height - 1) / 2.0
            var left = -cx
            var right = cx
            var top = -cy
            var bottom = cy
            for (t in transforms) {
                val inv = t.inverse()
                // Frame corners, in reference coordinates.
                fun x(px: Double, py: Double) = inv.mapX(px, py)
                fun y(px: Double, py: Double) = inv.mapY(px, py)
                left = max(left, max(x(-cx, -cy), x(-cx, cy)))
                right = min(right, min(x(cx, -cy), x(cx, cy)))
                top = max(top, max(y(-cx, -cy), y(cx, -cy)))
                bottom = min(bottom, min(y(-cx, cy), y(cx, cy)))
            }
            val l = ceil(left + cx).toInt() + 1
            val r = floor(right + cx).toInt()
            val t = ceil(top + cy).toInt() + 1
            val b = floor(bottom + cy).toInt()
            // If the frames hardly overlap something went wrong; fall back to the whole frame.
            return if (r - l < width / 2 || b - t < height / 2) IntRect(0, 0, width, height)
            else IntRect(l.coerceAtLeast(0), t.coerceAtLeast(0), r.coerceAtMost(width), b.coerceAtMost(height))
        }
    }
}
