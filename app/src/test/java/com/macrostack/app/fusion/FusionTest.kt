package com.macrostack.app.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class FusionTest {

    @Test
    fun similarityAlgebra() {
        val t = Similarity(1.02, 0.01, 3.5, -2.0)
        val id = t.after(t.inverse())
        assertEquals(1.0, id.a, 1e-12)
        assertEquals(0.0, id.b, 1e-12)
        assertEquals(0.0, id.tx, 1e-9)
        assertEquals(0.0, id.ty, 1e-9)
        val u = Similarity(0.99, -0.02, -1.0, 4.0)
        // (t ∘ u)(p) == t(u(p))
        val tu = t.after(u)
        val x = 12.0
        val y = -7.0
        assertEquals(t.mapX(u.mapX(x, y), u.mapY(x, y)), tu.mapX(x, y), 1e-9)
        assertEquals(t.mapY(u.mapX(x, y), u.mapY(x, y)), tu.mapY(x, y), 1e-9)
    }

    @Test
    fun affineAlgebra() {
        val t = Affine(1.02, 0.013, -0.008, 0.995, 3.5, -2.0)
        val id = t.after(t.inverse())
        assertEquals(1.0, id.a, 1e-12)
        assertEquals(0.0, id.b, 1e-12)
        assertEquals(0.0, id.c, 1e-12)
        assertEquals(1.0, id.d, 1e-12)
        assertEquals(0.0, id.tx, 1e-9)
        assertEquals(0.0, id.ty, 1e-9)
        val u = Affine(0.99, -0.02, 0.03, 1.01, -1.0, 4.0)
        val tu = t.after(u)
        val x = 12.0
        val y = -7.0
        assertEquals(t.mapX(u.mapX(x, y), u.mapY(x, y)), tu.mapX(x, y), 1e-9)
        assertEquals(t.mapY(u.mapX(x, y), u.mapY(x, y)), tu.mapY(x, y), 1e-9)
        // A similarity converts to the same mapping.
        val s = Similarity(1.02, 0.01, 3.5, -2.0)
        assertEquals(s.mapX(x, y), s.toAffine().mapX(x, y), 1e-12)
        assertEquals(s.mapY(x, y), s.toAffine().mapY(x, y), 1e-12)
        assertEquals(0.0, s.toAffine().distortion, 1e-12)
    }

    @Test
    fun alignerRecoversBreathingShiftAndRotation() {
        val stack = SyntheticStack(frames = 3, maxBlurSigma = 0f)
        Parallel(4).use { parallel ->
            val truth = Similarity(1.012, 0.004, 3.4, -2.7) // reference → moved
            val moved = IntArray(stack.width * stack.height)
            Warp.apply(ArraySource(stack.scene), truth.toAffine(), moved, stack.width, stack.height, 0, 0, parallel)
            val aligner = Aligner()
            val template = aligner.prepare(GrayImage.luma(stack.scene))
            val image = aligner.prepare(GrayImage.luma(moved, stack.width, stack.height))
            val found = aligner.estimate(template, image)
            assertNotNull(found)
            // moved(x) = scene(truth(x)), so the scene point x appears in moved at truth⁻¹(x).
            val expected = truth.inverse()
            assertEquals(expected.scale, found!!.scale, 5e-4)
            assertEquals(expected.rotationRadians, found.rotationRadians, 5e-4)
            assertTrue("no stretch or shear: ${found.distortion}", found.distortion < 5e-4)
            assertEquals(expected.tx, found.tx, 0.1)
            assertEquals(expected.ty, found.ty, 0.1)
        }
    }

    @Test
    fun diagnosePairSteps() {
        for (blur in listOf(0f, 6f)) {
            val stack = SyntheticStack(frames = 9, maxBlurSigma = blur)
            val aligner = Aligner()
            val prepared = stack.asFrames().map { aligner.prepare(it.loadSmall()) }
            for (k in 1 until stack.frames) {
                // True step k−1 → k at half scale.
                val truth = stack.frameToScene[k].inverse().after(stack.frameToScene[k - 1]).scaledBy(0.5)
                val found = aligner.estimate(prepared[k - 1], prepared[k])!!
                println(
                    "blur=$blur pair $k: scale %.5f vs %.5f · t (%.3f, %.3f) vs (%.3f, %.3f)".format(
                        found.scale, truth.scale, found.tx, found.ty, truth.tx, truth.ty,
                    )
                )
            }
        }
    }

    @Test
    fun fusingIdenticalFramesGivesTheFrameBack() {
        val stack = SyntheticStack(frames = 2, maxBlurSigma = 0f)
        Parallel(4).use { parallel ->
            val fuser = PyramidFuser(stack.width, stack.height, parallel, denoise = false)
            repeat(3) { fuser.add(stack.scene.pixels) }
            val out = IntArray(stack.width * stack.height)
            fuser.result(out)
            var maxDiff = 0
            for (i in out.indices) for (s in intArrayOf(16, 8, 0)) {
                maxDiff = maxOf(maxDiff, abs(((out[i] shr s) and 0xFF) - ((stack.scene.pixels[i] shr s) and 0xFF)))
            }
            assertTrue("max difference $maxDiff", maxDiff <= 2)
        }
    }

    @Test
    fun depthMapStackIsSharperThanAnyFrameAndCloseToTheTruth() = sharperThanAnyFrame(StackFusion.Method.DEPTH_MAP)

    @Test
    fun pyramidStackIsSharperThanAnyFrameAndCloseToTheTruth() = sharperThanAnyFrame(StackFusion.Method.PYRAMID)

    @Test
    fun noiseIsAveragedAwayNotAmplified() {
        // Real phone frames are noisy, and the noise is different in every frame.
        val stack = SyntheticStack(frames = 17, maxBlurSigma = 4f, noiseSigma = 8f, textureAmplitude = 0)
        Parallel(4).use { parallel ->
            val truth = stack.groundTruth(parallel)
            val results = StackFusion.Method.entries.associateWith { method ->
                StackFusion(parallel).run(stack.asFrames(), object : StackFusion.Listener {}, method = method)
            }
            val crop = results.getValue(StackFusion.Method.DEPTH_MAP).crop
            val single = IntArray(crop.width * crop.height)
            Warp.apply(ArraySource(stack.images[stack.frames / 2]), results.getValue(StackFusion.Method.DEPTH_MAP).transforms[stack.frames / 2],
                single, crop.width, crop.height, crop.left, crop.top, parallel)
            val singleNoise = stack.flatNoise(RgbImage(crop.width, crop.height, single), truth, crop, crop.left, crop.top)
            val noise = results.mapValues { (_, r) -> stack.flatNoise(r.image, truth, r.crop, r.crop.left, r.crop.top) }
            val psnr = results.mapValues { (_, r) ->
                SyntheticStack.psnr(r.image, truth, IntRect(r.crop.left + 8, r.crop.top + 8, r.crop.right - 8, r.crop.bottom - 8), r.crop.left, r.crop.top)
            }
            results.forEach { (m, r) -> SyntheticStack.save(r.image, "noisy_${m.name.lowercase()}") }
            SyntheticStack.save(stack.images[stack.frames / 2], "noisy_frame_middle")
            println("flat-area noise: single frame %.2f · depth map %.2f · pyramid %.2f".format(
                singleNoise, noise[StackFusion.Method.DEPTH_MAP], noise[StackFusion.Method.PYRAMID]))
            println("PSNR: depth map %.2f dB · pyramid %.2f dB".format(psnr[StackFusion.Method.DEPTH_MAP], psnr[StackFusion.Method.PYRAMID]))

            assertTrue("the depth map should average noise away", noise.getValue(StackFusion.Method.DEPTH_MAP) < singleNoise * 0.45)
            // Guided by the depth map, the pyramid takes noise-level detail from the frames in focus only.
            assertTrue("the pyramid should not keep most of the noise", noise.getValue(StackFusion.Method.PYRAMID) < singleNoise * 0.65)
        }
    }

    @Test
    fun movingSubject() {
        // A bug that moves 4 px per frame, like a hand that won't keep still: the ghosting case. Saved for visual comparison.
        val stack = SyntheticStack(frames = 17, maxBlurSigma = 4f, noiseSigma = 4f, movePerFrame = 4.0)
        Parallel(4).use { parallel ->
            val truth = stack.groundTruth(parallel)
            SyntheticStack.save(truth, "moving_truth")
            for (method in StackFusion.Method.entries) {
                val fusion = StackFusion(parallel)
                val r = fusion.run(stack.asFrames(), object : StackFusion.Listener {}, method = method)
                SyntheticStack.save(r.image, "moving_${method.name.lowercase()}")
                fusion.lastDepthMap?.let { SyntheticStack.save(it, "moving_depth") }
                val bug = IntRect(stack.width / 2 - 60, (stack.height * 0.45).toInt() - 40, stack.width / 2 + 60, (stack.height * 0.45).toInt() + 40)
                val psnr = SyntheticStack.psnr(r.image, truth, bug, r.crop.left, r.crop.top)
                println("moving bug, ${method.name}: PSNR around the bug %.2f dB".format(psnr))
                // Unguided, the pyramid ghosted to 27.2 dB.
                val expected = if (method == StackFusion.Method.DEPTH_MAP) 30.0 else 29.0
                assertTrue("$method around the bug: $psnr dB", psnr > expected)
            }
        }
    }

    @Test
    fun exposureFlickerIsEvenedOut() {
        // Lights that flicker: three frames came out 15 % darker. Their brightness is matched, so the
        // result looks as if they hadn't.
        val stack = SyntheticStack(frames = 17, maxBlurSigma = 4f)
        val dark = setOf(4, 9, 13)
        val flickering = stack.images.mapIndexed { k, image ->
            if (k !in dark) image else RgbImage(image.width, image.height, IntArray(image.pixels.size) { i ->
                val p = image.pixels[i]
                fun dim(c: Int) = (c * 0.85 + 0.5).toInt()
                (0xFF shl 24) or (dim((p shr 16) and 0xFF) shl 16) or (dim((p shr 8) and 0xFF) shl 8) or dim(p and 0xFF)
            })
        }
        Parallel(4).use { parallel ->
            val truth = stack.groundTruth(parallel)
            fun psnr(frames: List<StackFusion.Frame>): Pair<Double, List<Double>> {
                val fusion = StackFusion(parallel)
                val r = fusion.run(frames, object : StackFusion.Listener {})
                val inner = IntRect(r.crop.left + 8, r.crop.top + 8, r.crop.right - 8, r.crop.bottom - 8)
                return SyntheticStack.psnr(r.image, truth, inner, r.crop.left, r.crop.top) to fusion.lastGains
            }
            val (steady, _) = psnr(stack.asFrames())
            val (flickered, gains) = psnr(flickering.map { image ->
                object : StackFusion.Frame {
                    override fun loadSmall(): GrayImage = GrayImage.luma(image).half()
                    override fun openFull(sampleSize: Int): StackFusion.FullFrame = object : StackFusion.FullFrame {
                        private val source = ArraySource(image)
                        override val width = source.width
                        override val height = source.height
                        override fun readRows(y: Int, count: Int, dst: IntArray) = source.readRows(y, count, dst)
                        override fun close() = Unit
                    }
                }
            })
            println("flicker: %.2f dB steady, %.2f dB flickering · gains of the dark frames %s".format(
                steady, flickered, dark.joinToString { "%.3f".format(gains[it]) }))
            for (k in dark) assertEquals("gain of frame $k", 1 / 0.85, gains[k], 0.02)
            assertTrue("flickering $flickered dB vs steady $steady dB", flickered > steady - 0.5)
        }
    }

    @Test
    fun aFailedAlignmentStepTakesItsNeighboursScale() {
        val steps = listOf(1.0, 1.002, 1.0021, 1.0019, 1.03, 1.002, null, 1.0022, 1.0018).map { s ->
            s?.let { Affine.IDENTITY.magnifiedBy(it).copy(tx = 0.5) }
        }
        val cleaned = StackFusion.cleanSteps(steps)
        assertEquals(1.002, cleaned[4].scale, 3e-4) // the outlier
        assertEquals(0.5, cleaned[4].tx, 1e-9) // …keeps its shift
        assertEquals(1.002, cleaned[6].scale, 3e-4) // couldn't be aligned
        assertEquals(0.0, cleaned[6].tx, 1e-9)
        assertEquals(1.0019, cleaned[3].scale, 1e-9) // ordinary steps stay as measured
        assertEquals(1.0022, cleaned[7].scale, 1e-9)
    }

    @Test
    fun brightForegroundDoesNotSpillOntoTheBackground() {
        // A bright, near object over a far, darker background (a knee over a carpet): in the frames
        // focused near, its blur spills over the background, and bright areas are the noisiest.
        val stack = SyntheticStack(frames = 17, maxBlurSigma = 4f, noiseSigma = 2f, brightNoise = 6f, occluder = true)
        Parallel(4).use { parallel ->
            val truth = stack.groundTruth(parallel)
            SyntheticStack.save(truth, "occluder_truth")
            SyntheticStack.save(stack.images[0], "occluder_frame_first")
            SyntheticStack.save(stack.images.last(), "occluder_frame_last")
            val reference = stack.frameToScene[stack.frames / 2]
            val cx = (stack.width - 1) / 2.0
            val cy = (stack.height - 1) / 2.0
            for (method in StackFusion.Method.entries) {
                val fusion = StackFusion(parallel)
                val r = fusion.run(stack.asFrames(), object : StackFusion.Listener {}, method = method)
                SyntheticStack.save(r.image, "occluder_${method.name.lowercase()}")
                fusion.lastDepthMap?.let { SyntheticStack.save(it, "occluder_depth") }
                // The background just outside the object's edge, where the spill lands.
                var haze = 0.0
                var se = 0.0
                var n = 0
                for (y in r.crop.top + 4 until r.crop.bottom - 4) for (x in r.crop.left + 4 until r.crop.right - 4) {
                    val sx = reference.mapX(x - cx, y - cy) + cx
                    val sy = reference.mapY(x - cx, y - cy) + cy
                    val d = hypot(sx - stack.occluderCenterX, sy - stack.occluderCenterY) - stack.occluderRadius
                    if (d < 3 || d > 30) continue
                    val p = r.image.pixels[(y - r.crop.top) * r.image.width + (x - r.crop.left)]
                    val q = truth.pixels[y * truth.width + x]
                    fun luma(c: Int) = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) / 256.0
                    val e = luma(p) - luma(q)
                    haze += e
                    se += e * e
                    n++
                }
                val ringPsnr = 10 * kotlin.math.log10(255.0 * 255.0 / (se / n))
                val inner = IntRect(r.crop.left + 8, r.crop.top + 8, r.crop.right - 8, r.crop.bottom - 8)
                println("occluder, ${method.name}: spill ring %.2f dB, haze %+.2f levels · whole image %.2f dB".format(
                    ringPsnr, haze / n, SyntheticStack.psnr(r.image, truth, inner, r.crop.left, r.crop.top)))
                if (method == StackFusion.Method.PYRAMID) assertTrue("pyramid spill ring $ringPsnr dB", ringPsnr > 26.0)
                if (method == StackFusion.Method.DEPTH_MAP) {
                    // Before noise-aware sharpness and spill-free averaging: 22.8 dB, +6.7 levels of
                    // white "cotton". What's left is the spill in the frames where the background is
                    // sharp, which no depth map can avoid.
                    assertTrue("spill ring $ringPsnr dB", ringPsnr > 23.8)
                    assertTrue("haze ${haze / n}", haze / n < 6.3)
                }
            }
        }
    }

    private fun sharperThanAnyFrame(method: StackFusion.Method) {
        // 17 frames: neighbouring depths of field overlap, as in a well-planned real stack.
        val stack = SyntheticStack(frames = 17, maxBlurSigma = 4f)
        val previews = mutableListOf<RgbImage>()
        Parallel(4).use { parallel ->
            val prefetch = Executors.newSingleThreadExecutor()
            val fusion = StackFusion(parallel, prefetch)
            val result = fusion.run(stack.asFrames(), object : StackFusion.Listener {
                override fun onPreview(preview: RgbImage) {
                    previews += preview
                }
            }, method = method)
            prefetch.shutdown()
            fusion.lastDepthMap?.let { SyntheticStack.save(it, "depth_map") }

            // Alignment: every frame's transform matches what really happened.
            for (k in 0 until stack.frames) {
                val truth = stack.trueTransform(k)
                val found = result.transforms[k]
                assertEquals("scale of frame $k", truth.scale, found.scale, 1e-3)
                assertTrue(
                    "shift of frame $k: found (${found.tx}, ${found.ty}) vs (${truth.tx}, ${truth.ty})",
                    hypot(found.tx - truth.tx, found.ty - truth.ty) < 0.35,
                )
            }
            assertEquals(0, result.unalignedPairs)

            // Fusion: compare with the ideal all-sharp image, inside the cropped area.
            val truthImage = stack.groundTruth(parallel)
            val crop = result.crop
            val inner = IntRect(crop.left + 8, crop.top + 8, crop.right - 8, crop.bottom - 8)
            val fusedPsnr = SyntheticStack.psnr(result.image, truthImage, inner, crop.left, crop.top)
            val fusedSharpness = SyntheticStack.sharpness(result.image)
            val truthSharpness = SyntheticStack.sharpness(truthImage, crop)

            var bestFramePsnr = 0.0
            var bestFrameSharpness = 0.0
            for (k in 0 until stack.frames) {
                val aligned = IntArray(crop.width * crop.height)
                Warp.apply(ArraySource(stack.images[k]), result.transforms[k], aligned, crop.width, crop.height, crop.left, crop.top, parallel)
                val frame = RgbImage(crop.width, crop.height, aligned)
                bestFramePsnr = maxOf(bestFramePsnr, SyntheticStack.psnr(frame, truthImage, inner, crop.left, crop.top))
                bestFrameSharpness = maxOf(bestFrameSharpness, SyntheticStack.sharpness(frame))
            }

            SyntheticStack.save(truthImage, "truth")
            SyntheticStack.save(stack.images[0], "frame_first")
            SyntheticStack.save(stack.images[stack.frames / 2], "frame_middle")
            SyntheticStack.save(stack.images.last(), "frame_last")
            SyntheticStack.save(result.image, "fused_${method.name.lowercase()}")
            previews.lastOrNull()?.let { SyntheticStack.save(it, "preview_last") }
            println(
                "${method.name}: fused PSNR %.2f dB (best single frame %.2f) · sharpness %.2f (truth %.2f, best frame %.2f) · crop %s"
                    .format(fusedPsnr, bestFramePsnr, fusedSharpness, truthSharpness, bestFrameSharpness, crop)
            )

            assertTrue("fused PSNR $fusedPsnr should beat best frame $bestFramePsnr by 4 dB", fusedPsnr > bestFramePsnr + 4)
            assertTrue("fused should be at least twice as sharp as any frame", fusedSharpness > bestFrameSharpness * 2)
            assertTrue("previews were produced", previews.isNotEmpty())
        }
    }

    @Test
    fun cancellationStopsTheRun() {
        val stack = SyntheticStack(frames = 4)
        Parallel(2).use { parallel ->
            var calls = 0
            try {
                StackFusion(parallel).run(stack.asFrames(), object : StackFusion.Listener {
                    override fun onProgress(phase: StackFusion.Phase, done: Int, total: Int) {
                        calls++
                    }
                    override val isCancelled: Boolean get() = calls >= 2
                })
                throw AssertionError("should have been cancelled")
            } catch (e: StackFusion.CancelledException) {
                assertEquals(2, calls)
            }
        }
    }
}
