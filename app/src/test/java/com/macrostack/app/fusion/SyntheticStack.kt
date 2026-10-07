package com.macrostack.app.fusion

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Builds a fake focus stack with a known answer: a detailed scene whose depth runs left (near) to
 * right (far), photographed at evenly spaced focus distances with realistic defocus blur, focus
 * breathing and small OIS-like shifts.
 */
class SyntheticStack(
    val width: Int = 480,
    val height: Int = 360,
    val frames: Int = 9,
    private val maxBlurSigma: Float = 6f,
    /** Magnification change per frame (focus breathing). */
    private val breathingPerFrame: Double = 0.002,
    private val maxShiftPx: Double = 2.0,
    /** Per-frame sensor noise (σ, in 0–255 levels), different in every frame. */
    private val noiseSigma: Float = 0f,
    /** Fixed fine texture on everything; 0 leaves smooth, flat areas where noise is easy to measure. */
    private val textureAmplitude: Int = 10,
    /** A small "bug" that moves this many pixels per frame — a subject that won't keep still. */
    private val movePerFrame: Double = 0.0,
    /**
     * A bright object close to the camera (like a knee in front of a carpet). In the frames focused
     * nearer or farther, its defocus blur spills over the background behind its edge.
     */
    val occluder: Boolean = false,
    /** Extra noise σ in bright areas, as on a real sensor: σ grows by this much from black to white. */
    private val brightNoise: Float = 0f,
    private val seed: Long = 42,
) {
    private val random = Random(seed)

    /** The all-in-focus scene. */
    val scene: RgbImage = drawScene()

    /** Per frame: frame coordinates → scene coordinates (centred). */
    val frameToScene: List<Similarity> = List(frames) { k ->
        val s = 1.0 + breathingPerFrame * (k - frames / 2)
        Similarity(s, 0.0, (random.nextDouble() * 2 - 1) * maxShiftPx, (random.nextDouble() * 2 - 1) * maxShiftPx)
    }

    val images: List<RgbImage> by lazy { render() }

    /** The occluder: a disc this far from the left (fraction of width), at depth [OCCLUDER_DEPTH]. */
    val occluderCenterX = width * 0.66
    val occluderCenterY = height * 0.52
    val occluderRadius = height * 0.24

    /** The defocus blur (σ, pixels) at column [x] of frame [k] — depth runs left to right. */
    fun blurSigmaAt(k: Int, x: Int): Float {
        val focus = k / (frames - 1.0)
        val depth = x / (width - 1.0)
        return (abs(depth - focus) * maxBlurSigma * (frames - 1) / 2).toFloat()
    }

    /** What a perfect aligner would find: reference (middle) coordinates → frame k coordinates. */
    fun trueTransform(k: Int): Similarity = frameToScene[k].inverse().after(frameToScene[frames / 2])

    /** The sharp scene as the reference frame sees it — the ideal stacking result (bug where it is mid-stack). */
    fun groundTruth(parallel: Parallel): RgbImage {
        val sharp = scene.pixels.copyOf()
        if (occluder) occluderLayer().let { (color, alpha) -> composite(sharp, color, alpha) }
        if (movePerFrame != 0.0) bug(frames / 2).let { (color, alpha) -> composite(sharp, color, alpha) }
        val out = IntArray(width * height)
        Warp.apply(ArraySource(RgbImage(width, height, sharp)), frameToScene[frames / 2].toAffine(), out, width, height, 0, 0, parallel)
        return RgbImage(width, height, out)
    }

    /** Lays premultiplied [color] with coverage [alpha] over [base]. */
    private fun composite(base: IntArray, color: RgbImage, alpha: FloatArray) {
        for (i in base.indices) {
            val bg = base[i]
            val fg = color.pixels[i]
            val keep = 1 - alpha[i]
            base[i] = pack(
                (((bg shr 16) and 0xFF) * keep + ((fg shr 16) and 0xFF)).toInt(),
                (((bg shr 8) and 0xFF) * keep + ((fg shr 8) and 0xFF)).toInt(),
                ((bg and 0xFF) * keep + (fg and 0xFF)).toInt(),
            )
        }
    }

    /** The occluder, sharp: premultiplied colour and coverage. Light grey fabric with a fine weave. */
    private fun occluderLayer(): Pair<RgbImage, FloatArray> {
        val color = IntArray(width * height)
        val alpha = FloatArray(width * height)
        val rnd = Random(seed + 7)
        for (y in 0 until height) for (x in 0 until width) {
            val cover = (occluderRadius + 0.5 - hypot(x - occluderCenterX, y - occluderCenterY)).coerceIn(0.0, 1.0).toFloat()
            if (cover <= 0f) continue
            val weave = rnd.nextInt(17) - 8 + if ((x + y) % 4 < 2) 6 else -6
            val i = y * width + x
            alpha[i] = cover
            color[i] = pack(((200 + weave) * cover).toInt(), ((207 + weave) * cover).toInt(), ((220 + weave) * cover).toInt())
        }
        return RgbImage(width, height, color) to alpha
    }

    fun asFrames(): List<StackFusion.Frame> = images.map { image ->
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
    }

    // ---------------------------------------------------------------- scene

    private fun drawScene(): RgbImage {
        val px = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            px[y * width + x] = pack(40 + 60 * y / height, 70 + 30 * x / width, 50 + 80 * y / height)
        }
        // Discs of colour.
        repeat(45) {
            val color = pack(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            disc(px, random.nextInt(width).toDouble(), random.nextInt(height).toDouble(), 4.0 + random.nextInt(16), color)
        }
        // Checker patches: crisp, high-frequency detail.
        repeat(8) {
            val x0 = random.nextInt(width - 24)
            val y0 = random.nextInt(height - 24)
            for (y in 0 until 24) for (x in 0 until 24) {
                px[(y0 + y) * width + x0 + x] = if (((x / 3) + (y / 3)) % 2 == 0) pack(235, 235, 225) else pack(25, 25, 35)
            }
        }
        // Fine "hairs" — the detail macro stacking is all about.
        repeat(140) {
            val color = pack(200 + random.nextInt(56), 180 + random.nextInt(76), 110 + random.nextInt(110))
            val x = random.nextInt(width).toDouble()
            val y = random.nextInt(height).toDouble()
            line(px, x, y, x + random.nextInt(70) - 35, y + random.nextInt(70) - 35, 0.5 + random.nextDouble() * 0.6, color)
        }
        // Fine texture everywhere.
        if (textureAmplitude > 0) {
            for (i in px.indices) {
                val n = random.nextInt(2 * textureAmplitude + 1) - textureAmplitude
                val p = px[i]
                px[i] = pack(((p shr 16) and 0xFF) + n, ((p shr 8) and 0xFF) + n, (p and 0xFF) + n)
            }
        }
        return RgbImage(width, height, px)
    }

    /** The moving bug for frame [k]: colour (premultiplied) and coverage, before defocus. */
    private fun bug(k: Int): Pair<RgbImage, FloatArray> {
        val color = IntArray(width * height)
        val alpha = FloatArray(width * height)
        val cx = width * 0.5 + movePerFrame * (k - frames / 2)
        val cy = height * 0.45
        fun paint(x: Int, y: Int, c: Int, cover: Float) {
            if (x !in 0 until width || y !in 0 until height || cover <= 0f) return
            val i = y * width + x
            val a = alpha[i]
            val na = a + (1 - a) * cover
            color[i] = mix(color[i], c, if (na > 0f) cover / na else 0f)
            alpha[i] = na
        }
        for (y in (cy - 30).toInt()..(cy + 30).toInt()) for (x in (cx - 40).toInt()..(cx + 40).toInt()) {
            val d = hypot((x - cx) / 1.3, y - cy)
            val cover = (20.5 - d).coerceIn(0.0, 1.0).toFloat()
            // Dark body with bright spots.
            val spot = ((x - cx.toInt()) / 6 + (y - cy.toInt()) / 6) % 2 == 0
            paint(x, y, if (spot) pack(230, 200, 60) else pack(40, 25, 20), cover)
            // Legs.
            for (leg in -2..2) {
                val lx = cx + leg * 9
                val dl = abs(x - lx) - 0.6
                if (abs(y - cy) in 18.0..30.0) paint(x, y, pack(30, 20, 15), (0.5 - dl).coerceIn(0.0, 1.0).toFloat())
            }
        }
        // Store premultiplied colour.
        for (i in color.indices) {
            val p = color[i]
            val a = alpha[i]
            color[i] = pack((((p shr 16) and 0xFF) * a).toInt(), (((p shr 8) and 0xFF) * a).toInt(), ((p and 0xFF) * a).toInt())
        }
        return RgbImage(width, height, color) to alpha
    }

    private fun blurAlpha(alpha: FloatArray, sigma: Float): FloatArray {
        if (sigma < 0.3f) return alpha
        val asImage = RgbImage(width, height, IntArray(alpha.size) { i -> val v = (alpha[i] * 255).toInt(); pack(v, v, v) })
        val blurred = gaussianBlur(asImage, sigma)
        return FloatArray(alpha.size) { ((blurred.pixels[it] shr 8) and 0xFF) / 255f }
    }

    private fun addNoise(image: RgbImage, k: Int): RgbImage {
        if (noiseSigma <= 0f && brightNoise <= 0f) return image
        val rnd = Random(seed * 1000 + k)
        val out = IntArray(image.pixels.size) { i ->
            val p = image.pixels[i]
            val luma = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
            val sigma = noiseSigma + brightNoise * luma / 255f
            pack(
                ((p shr 16) and 0xFF) + (rnd.nextGaussian() * sigma).toInt(),
                ((p shr 8) and 0xFF) + (rnd.nextGaussian() * sigma).toInt(),
                (p and 0xFF) + (rnd.nextGaussian() * sigma).toInt(),
            )
        }
        return RgbImage(image.width, image.height, out)
    }

    private fun disc(px: IntArray, cx: Double, cy: Double, r: Double, color: Int) {
        for (y in max(0, (cy - r - 1).toInt())..min(height - 1, (cy + r + 1).toInt())) {
            for (x in max(0, (cx - r - 1).toInt())..min(width - 1, (cx + r + 1).toInt())) {
                val cover = (r + 0.5 - hypot(x - cx, y - cy)).coerceIn(0.0, 1.0).toFloat()
                if (cover > 0) px[y * width + x] = mix(px[y * width + x], color, cover)
            }
        }
    }

    private fun line(px: IntArray, x0: Double, y0: Double, x1: Double, y1: Double, halfWidth: Double, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        for (y in max(0, (min(y0, y1) - 2).toInt())..min(height - 1, (max(y0, y1) + 2).toInt())) {
            for (x in max(0, (min(x0, x1) - 2).toInt())..min(width - 1, (max(x0, x1) + 2).toInt())) {
                val t = if (len2 == 0.0) 0.0 else (((x - x0) * dx + (y - y0) * dy) / len2).coerceIn(0.0, 1.0)
                val d = hypot(x - (x0 + t * dx), y - (y0 + t * dy))
                val cover = (halfWidth + 0.5 - d).coerceIn(0.0, 1.0).toFloat()
                if (cover > 0) px[y * width + x] = mix(px[y * width + x], color, cover)
            }
        }
    }

    private fun render(): List<RgbImage> {
        val sigmas = floatArrayOf(0f, 0.7f, 1.4f, 2.2f, 3.2f, 4.5f, 6f, 8f)
        val blurred = sigmas.map { if (it == 0f) scene else gaussianBlur(scene, it) }
        Parallel(4).use { parallel ->
            return List(frames) { k ->
                val focus = k / (frames - 1.0)
                // Defocus at each scene pixel: depth runs 0 (left) → 1 (right).
                val defocused = IntArray(width * height)
                for (y in 0 until height) for (x in 0 until width) {
                    val depth = x / (width - 1.0)
                    val sigma = (abs(depth - focus) * maxBlurSigma * (frames - 1) / 2).toFloat().coerceAtMost(sigmas.last())
                    var j = 0
                    while (j < sigmas.size - 2 && sigmas[j + 1] < sigma) j++
                    val t = ((sigma - sigmas[j]) / (sigmas[j + 1] - sigmas[j])).coerceIn(0f, 1f)
                    defocused[y * width + x] = mix(blurred[j].pixels[y * width + x], blurred[j + 1].pixels[y * width + x], t)
                }
                if (occluder) {
                    // Near the camera, in front of the background: blurred, it spills over its edge.
                    val sigma = (abs(OCCLUDER_DEPTH - focus) * maxBlurSigma * (frames - 1) / 2).toFloat().coerceAtMost(12f)
                    val (color, alpha) = occluderLayer()
                    composite(defocused, if (sigma < 0.3f) color else gaussianBlur(color, sigma), blurAlpha(alpha, sigma))
                }
                if (movePerFrame != 0.0) {
                    // The bug sits at mid depth, in front of the background, and moves.
                    val sigma = (abs(0.5 - focus) * maxBlurSigma * (frames - 1) / 2).toFloat().coerceAtMost(8f)
                    val (color, alpha) = bug(k)
                    composite(defocused, if (sigma < 0.3f) color else gaussianBlur(color, sigma), blurAlpha(alpha, sigma))
                }
                val out = IntArray(width * height)
                Warp.apply(ArraySource(RgbImage(width, height, defocused)), frameToScene[k].toAffine(), out, width, height, 0, 0, parallel)
                addNoise(RgbImage(width, height, out), k)
            }
        }
    }

    /** Mean high-pass σ of [img] over areas where [truth] is perfectly smooth: the noise left in the result. */
    fun flatNoise(img: RgbImage, truth: RgbImage, rect: IntRect, offsetX: Int, offsetY: Int): Double {
        val block = 12
        var acc = 0.0
        var n = 0
        val t = GrayImage.luma(truth)
        val g = GrayImage.luma(img)
        var by = rect.top + 2
        while (by + block < rect.bottom - 2) {
            var bx = rect.left + 2
            while (bx + block < rect.right - 2) {
                var truthEnergy = 0.0
                var energy = 0.0
                for (y in by until by + block) for (x in bx until bx + block) {
                    fun hp(im: GrayImage, xx: Int, yy: Int) = 4 * im[xx, yy] - im[xx - 1, yy] - im[xx + 1, yy] - im[xx, yy - 1] - im[xx, yy + 1]
                    val th = hp(t, x, y)
                    truthEnergy += th * th
                    val ih = hp(g, x - offsetX, y - offsetY)
                    energy += ih * ih
                }
                if (truthEnergy / (block * block) < 0.5) {
                    acc += kotlin.math.sqrt(energy / (block * block))
                    n++
                }
                bx += block
            }
            by += block
        }
        return if (n == 0) Double.NaN else acc / n
    }

    companion object {
        const val OCCLUDER_DEPTH = 0.12

        fun pack(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t + 0.5f).toInt()
            return pack(ch(16), ch(8), ch(0))
        }

        fun gaussianBlur(src: RgbImage, sigma: Float): RgbImage {
            val radius = (sigma * 3).toInt().coerceAtLeast(1)
            val kernel = FloatArray(2 * radius + 1) { i -> exp(-((i - radius) * (i - radius)) / (2 * sigma * sigma)) }
            val sum = kernel.sum()
            for (i in kernel.indices) kernel[i] /= sum
            val w = src.width
            val h = src.height
            fun pass(input: Array<FloatArray>, horizontal: Boolean): Array<FloatArray> = Array(3) { c ->
                val inC = input[c]
                FloatArray(w * h) { i ->
                    val x = i % w
                    val y = i / w
                    var acc = 0f
                    for (k in -radius..radius) {
                        val xx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
                        val yy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
                        acc += kernel[k + radius] * inC[yy * w + xx]
                    }
                    acc
                }
            }
            val channels = Array(3) { c -> FloatArray(w * h) { i -> ((src.pixels[i] shr (16 - 8 * c)) and 0xFF).toFloat() } }
            val out = pass(pass(channels, true), false)
            return RgbImage(w, h, IntArray(w * h) { i -> pack((out[0][i] + 0.5f).toInt(), (out[1][i] + 0.5f).toInt(), (out[2][i] + 0.5f).toInt()) })
        }

        /** Peak signal-to-noise ratio of [a] (placed at [aOffsetX], [aOffsetY]) against [b] inside [rect], in dB. */
        fun psnr(a: RgbImage, b: RgbImage, rect: IntRect, aOffsetX: Int = 0, aOffsetY: Int = 0): Double {
            var se = 0.0
            var n = 0
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                val p = a.pixels[(y - aOffsetY) * a.width + (x - aOffsetX)]
                val q = b.pixels[y * b.width + x]
                for (s in intArrayOf(16, 8, 0)) {
                    val d = ((p shr s) and 0xFF) - ((q shr s) and 0xFF)
                    se += d * d
                    n++
                }
            }
            val mse = se / n
            return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
        }

        /** Mean absolute Laplacian of luma: higher = sharper. */
        fun sharpness(img: RgbImage, rect: IntRect = IntRect(0, 0, img.width, img.height)): Double {
            val l = GrayImage.luma(img)
            var acc = 0.0
            var n = 0
            for (y in max(rect.top, 1) until min(rect.bottom, img.height - 1)) {
                for (x in max(rect.left, 1) until min(rect.right, img.width - 1)) {
                    acc += abs(4 * l[x, y] - l[x - 1, y] - l[x + 1, y] - l[x, y - 1] - l[x, y + 1])
                    n++
                }
            }
            return acc / n
        }

        /** Writes a PNG into build/fusion-test (a minimal encoder — the Android test classpath has no ImageIO). */
        fun save(img: RgbImage, name: String) {
            val raw = ByteArrayOutputStream()
            DeflaterOutputStream(raw).use { z ->
                val row = ByteArray(1 + img.width * 3)
                for (y in 0 until img.height) {
                    row[0] = 0 // filter: none
                    for (x in 0 until img.width) {
                        val p = img.pixels[y * img.width + x]
                        row[1 + 3 * x] = (p shr 16).toByte()
                        row[2 + 3 * x] = (p shr 8).toByte()
                        row[3 + 3 * x] = p.toByte()
                    }
                    z.write(row)
                }
            }
            val dir = File("build/fusion-test").apply { mkdirs() }
            DataOutputStream(File(dir, "$name.png").outputStream().buffered()).use { out ->
                out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
                fun chunk(type: String, data: ByteArray) {
                    out.writeInt(data.size)
                    val typeBytes = type.toByteArray(Charsets.US_ASCII)
                    out.write(typeBytes)
                    out.write(data)
                    val crc = CRC32()
                    crc.update(typeBytes)
                    crc.update(data)
                    out.writeInt(crc.value.toInt())
                }
                val header = ByteArrayOutputStream()
                DataOutputStream(header).use { h ->
                    h.writeInt(img.width)
                    h.writeInt(img.height)
                    h.write(byteArrayOf(8, 2, 0, 0, 0)) // 8-bit RGB
                }
                chunk("IHDR", header.toByteArray())
                chunk("IDAT", raw.toByteArray())
                chunk("IEND", ByteArray(0))
            }
        }
    }
}
