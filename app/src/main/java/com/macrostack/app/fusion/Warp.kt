package com.macrostack.app.fusion

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Something that can hand out rows of packed ARGB pixels — an Android Bitmap, or an array in tests. */
interface PixelSource {
    val width: Int
    val height: Int

    /** Copies rows [y, y + count) into [dst] (count × width, row-major). Must be safe to call from several threads. */
    fun readRows(y: Int, count: Int, dst: IntArray)
}

class ArraySource(private val image: RgbImage) : PixelSource {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    override fun readRows(y: Int, count: Int, dst: IntArray) {
        System.arraycopy(image.pixels, y * width, dst, 0, count * width)
    }
}

object Warp {

    private const val BAND = 48
    private val rowBuffer = ThreadLocal<IntArray>()

    /**
     * Resamples [src] onto the reference frame: out(x, y) = src(T(x + offsetX, y + offsetY)), where
     * [transform] T maps reference coordinates to [src] coordinates (both centred on the image middle).
     * Bilinear; pixels outside [src] take the nearest edge pixel.
     */
    fun apply(
        src: PixelSource,
        transform: Affine,
        out: IntArray,
        outWidth: Int,
        outHeight: Int,
        offsetX: Int,
        offsetY: Int,
        parallel: Parallel,
    ) {
        val sw = src.width
        val sh = src.height
        val cx = (sw - 1) / 2.0
        val cy = (sh - 1) / 2.0
        val a = transform.a
        val b = transform.b
        val c = transform.c
        val d = transform.d
        // Source position of output pixel (x, y): u = a·X + b·Y + tx + cx, v = c·X + d·Y + ty + cy,
        // with X = x + offsetX − cx, Y = y + offsetY − cy.
        fun u(x: Double, y: Double) = a * (x + offsetX - cx) + b * (y + offsetY - cy) + transform.tx + cx
        fun v(x: Double, y: Double) = c * (x + offsetX - cx) + d * (y + offsetY - cy) + transform.ty + cy

        parallel.forRows(outHeight) { from, until ->
            var y0 = from
            while (y0 < until) {
                val y1 = min(until, y0 + BAND)
                // Source rows this band of output rows can touch.
                val xs = doubleArrayOf(0.0, outWidth - 1.0)
                val ys = doubleArrayOf(y0.toDouble(), y1 - 1.0)
                var vMin = Double.MAX_VALUE
                var vMax = -Double.MAX_VALUE
                for (px in xs) for (py in ys) {
                    val vv = v(px, py)
                    vMin = min(vMin, vv)
                    vMax = max(vMax, vv)
                }
                val r0 = floor(vMin).toInt().coerceIn(0, sh - 1)
                val r1 = (ceil(vMax).toInt() + 2).coerceIn(r0 + 1, sh)
                val rows = r1 - r0
                var buf = rowBuffer.get()
                if (buf == null || buf.size < rows * sw) {
                    buf = IntArray(rows * sw)
                    rowBuffer.set(buf)
                }
                src.readRows(r0, rows, buf)

                for (y in y0 until y1) {
                    var uu = u(0.0, y.toDouble())
                    var vv = v(0.0, y.toDouble())
                    val rowOut = y * outWidth
                    for (x in 0 until outWidth) {
                        out[rowOut + x] = sample(buf, sw, r0, rows, uu, vv)
                        uu += a
                        vv += c
                    }
                }
                y0 = y1
            }
        }
    }

    /** Bilinear sample from a band of rows starting at source row [r0]. */
    private fun sample(buf: IntArray, sw: Int, r0: Int, rows: Int, u: Double, v: Double): Int {
        val uc = u.coerceIn(0.0, sw - 1.0)
        val vc = (v - r0).coerceIn(0.0, rows - 1.0)
        val x0 = min(uc.toInt(), sw - 2).coerceAtLeast(0)
        val yy0 = min(vc.toInt(), rows - 2).coerceAtLeast(0)
        val fx = ((uc - x0) * 256).toInt().coerceIn(0, 256)
        val fy = ((vc - yy0) * 256).toInt().coerceIn(0, 256)
        val i = yy0 * sw + x0
        val p00 = buf[i]
        val p01 = if (sw > 1) buf[i + 1] else p00
        val p10 = if (rows > 1) buf[i + sw] else p00
        val p11 = if (sw > 1 && rows > 1) buf[i + sw + 1] else p10
        val w00 = (256 - fx) * (256 - fy)
        val w01 = fx * (256 - fy)
        val w10 = (256 - fx) * fy
        val w11 = fx * fy
        val r = (((p00 shr 16) and 0xFF) * w00 + ((p01 shr 16) and 0xFF) * w01 +
            ((p10 shr 16) and 0xFF) * w10 + ((p11 shr 16) and 0xFF) * w11 + 32768) shr 16
        val g = (((p00 shr 8) and 0xFF) * w00 + ((p01 shr 8) and 0xFF) * w01 +
            ((p10 shr 8) and 0xFF) * w10 + ((p11 shr 8) and 0xFF) * w11 + 32768) shr 16
        val bl = ((p00 and 0xFF) * w00 + (p01 and 0xFF) * w01 +
            (p10 and 0xFF) * w10 + (p11 and 0xFF) * w11 + 32768) shr 16
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
