package com.macrostack.app.fusion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** A packed ARGB image (one Int per pixel, row-major). */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(pixels.size >= width * height) { "Pixel buffer too small" }
    }
}

/** A single-channel float image, used for alignment. */
class GrayImage(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    /** Bilinear sample. Requires 0 ≤ x < width − 1 and 0 ≤ y < height − 1. */
    fun sample(x: Float, y: Float): Float {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val i = y0 * width + x0
        val top = data[i] + (data[i + 1] - data[i]) * fx
        val bottom = data[i + width] + (data[i + width + 1] - data[i + width]) * fx
        return top + (bottom - top) * fy
    }

    /** Separable [1 4 6 4 1]/16 blur (σ ≈ 1 px), edges clamped. */
    fun smooth(): GrayImage {
        val tmp = FloatArray(data.size)
        val out = FloatArray(data.size)
        val k0 = 1 / 16f
        val k1 = 4 / 16f
        val k2 = 6 / 16f
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val xm2 = row + maxOf(x - 2, 0)
                val xm1 = row + maxOf(x - 1, 0)
                val xp1 = row + minOf(x + 1, width - 1)
                val xp2 = row + minOf(x + 2, width - 1)
                tmp[row + x] = k0 * (data[xm2] + data[xp2]) + k1 * (data[xm1] + data[xp1]) + k2 * data[row + x]
            }
        }
        for (y in 0 until height) {
            val ym2 = maxOf(y - 2, 0) * width
            val ym1 = maxOf(y - 1, 0) * width
            val yp1 = minOf(y + 1, height - 1) * width
            val yp2 = minOf(y + 2, height - 1) * width
            val row = y * width
            for (x in 0 until width) {
                out[row + x] = k0 * (tmp[ym2 + x] + tmp[yp2 + x]) + k1 * (tmp[ym1 + x] + tmp[yp1 + x]) + k2 * tmp[row + x]
            }
        }
        return GrayImage(width, height, out)
    }

    /** Half size, each pixel the average of a 2×2 block. */
    fun half(): GrayImage {
        val w = width / 2
        val h = height / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val r0 = 2 * y * width
            val r1 = r0 + width
            for (x in 0 until w) {
                val c = 2 * x
                out[y * w + x] = (data[r0 + c] + data[r0 + c + 1] + data[r1 + c] + data[r1 + c + 1]) * 0.25f
            }
        }
        return GrayImage(w, h, out)
    }

    companion object {
        fun luma(pixels: IntArray, width: Int, height: Int): GrayImage {
            val out = FloatArray(width * height)
            for (i in out.indices) {
                val p = pixels[i]
                out[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
            }
            return GrayImage(width, height, out)
        }

        fun luma(image: RgbImage): GrayImage = luma(image.pixels, image.width, image.height)
    }
}

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * A similarity transform (scale, rotation, shift) in coordinates centred on the image:
 * x' = a·x − b·y + tx,  y' = b·x + a·y + ty.
 * Focus breathing is a scale about the centre, so centred coordinates keep the numbers well-behaved.
 */
data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {

    val scale: Double get() = hypot(a, b)
    val rotationRadians: Double get() = atan2(b, a)

    fun mapX(x: Double, y: Double): Double = a * x - b * y + tx
    fun mapY(x: Double, y: Double): Double = b * x + a * y + ty

    /** This ∘ [other]: apply [other] first, then this. */
    fun after(other: Similarity) = Similarity(
        a = a * other.a - b * other.b,
        b = a * other.b + b * other.a,
        tx = a * other.tx - b * other.ty + tx,
        ty = b * other.tx + a * other.ty + ty,
    )

    fun inverse(): Similarity {
        val d = a * a + b * b
        val ia = a / d
        val ib = -b / d
        return Similarity(ia, ib, -(ia * tx - ib * ty), -(ib * tx + ia * ty))
    }

    /** The same transform for an image [factor] times larger. */
    fun scaledBy(factor: Double) = copy(tx = tx * factor, ty = ty * factor)

    fun toAffine() = Affine(a, -b, b, a, tx, ty)

    companion object {
        val IDENTITY = Similarity(1.0, 0.0, 0.0, 0.0)
    }
}

/**
 * An affine transform in coordinates centred on the image: x' = a·x + b·y + tx,  y' = c·x + d·y + ty.
 * Besides scale, rotation and shift it can stretch more one way than the other, and shear: a phone
 * held by hand tilts a little between shots, which over most subjects looks just like that.
 */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {

    /** Magnification (the square root of the area change). */
    val scale: Double get() = sqrt(abs(a * d - b * c))
    val rotationRadians: Double get() = atan2(c - b, a + d)

    /** Stretch and shear, relative to the scale: 0 for a pure scale and rotation. */
    val distortion: Double get() = hypot(a - d, b + c) / (2 * scale)

    fun mapX(x: Double, y: Double): Double = a * x + b * y + tx
    fun mapY(x: Double, y: Double): Double = c * x + d * y + ty

    /** This ∘ [other]: apply [other] first, then this. */
    fun after(other: Affine) = Affine(
        a = a * other.a + b * other.c,
        b = a * other.b + b * other.d,
        c = c * other.a + d * other.c,
        d = c * other.b + d * other.d,
        tx = a * other.tx + b * other.ty + tx,
        ty = c * other.tx + d * other.ty + ty,
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    /** The same transform for an image [factor] times larger. */
    fun scaledBy(factor: Double) = copy(tx = tx * factor, ty = ty * factor)

    /** The same transform with [factor] times the magnification. */
    fun magnifiedBy(factor: Double) = copy(a = a * factor, b = b * factor, c = c * factor, d = d * factor)

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
}
