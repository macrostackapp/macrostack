package com.macrostack.app.fusion

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Rough timing at phone resolution (4000×3000). Opt-in, since it takes a while:
 * set MACROSTACK_PERF=1 in the environment.
 */
class FusionPerfTest {

    @Test
    fun twelveMegapixelFrame() {
        assumeTrue(System.getenv("MACROSTACK_PERF") != null)
        val w = 4000
        val h = 3000
        val random = java.util.Random(1)
        val frame = IntArray(w * h) { (0xFF shl 24) or random.nextInt(0xFFFFFF) }
        val out = IntArray(w * h)
        Parallel().use { parallel ->
            println("threads: ${parallel.threads}")
            val fuser = PyramidFuser(w, h, parallel)
            val source = ArraySource(RgbImage(w, h, frame))
            repeat(4) { i ->
                val t0 = System.nanoTime()
                Warp.apply(source, Similarity(1.001, 0.0, 0.7, -0.4).toAffine(), out, w, h, 0, 0, parallel)
                val t1 = System.nanoTime()
                fuser.add(out)
                val t2 = System.nanoTime()
                println("frame $i: warp %.0f ms, fuse %.0f ms".format((t1 - t0) / 1e6, (t2 - t1) / 1e6))
            }
            val t0 = System.nanoTime()
            fuser.result(out)
            println("result %.0f ms".format((System.nanoTime() - t0) / 1e6))

            // Clean (depth map): two sweeps — measure, then compose.
            val frames = 4
            val dmap = DepthMapFuser(w, h, frames, parallel)
            for (k in 0 until frames) {
                val m0 = System.nanoTime()
                dmap.measure(k, frame)
                println("depth map measure $k: %.0f ms".format((System.nanoTime() - m0) / 1e6))
            }
            val a0 = System.nanoTime()
            dmap.analyze()
            println("depth map analyze: %.0f ms (cell ${dmap.cell}px)".format((System.nanoTime() - a0) / 1e6))
            for (k in 0 until frames) {
                val c0 = System.nanoTime()
                dmap.compose(k, frame)
                println("depth map compose $k: %.0f ms".format((System.nanoTime() - c0) / 1e6))
            }
        }
    }
}
