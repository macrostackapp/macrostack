package com.macrostack.app.fusion

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * Runs the engine on real frames from a phone, for looking at the results by eye. Opt-in: set
 * MACROSTACK_REAL to a folder of binary PPMs (frame_000.ppm, … in shooting order). The results land
 * in build/fusion-test/real_*.png. Also: MACROSTACK_METHODS (e.g. DEPTH_MAP), MACROSTACK_PROBE
 * ("x,y;x,y": what the depth map decided there), MACROSTACK_ALIGN_ONLY (each frame's measured scale).
 */
class RealStackTest {

    @Test
    fun stackRealFrames() {
        val dir = System.getenv("MACROSTACK_REAL")
        assumeTrue(dir != null)
        val files = File(dir!!).listFiles { f -> f.name.endsWith(".ppm") }!!.sortedBy { it.name }
        require(files.size >= 2) { "Need at least two frames in $dir" }
        // Frames are read from disk when needed, as on the phone: 20 full-size frames won't fit in memory.
        val frames = files.map { file ->
            object : StackFusion.Frame {
                override fun loadSmall(): GrayImage {
                    // As the app decodes it: shrunk by alignSampleSize (a power of two).
                    var image = GrayImage.luma(readPpm(file))
                    var s = StackFusion.alignSampleSize(image.width, image.height)
                    while (s > 1) {
                        image = image.half()
                        s /= 2
                    }
                    return image
                }
                override fun openFull(sampleSize: Int): StackFusion.FullFrame = object : StackFusion.FullFrame {
                    private val source = ArraySource(readPpm(file))
                    override val width = source.width
                    override val height = source.height
                    override fun readRows(y: Int, count: Int, dst: IntArray) = source.readRows(y, count, dst)
                    override fun close() = Unit
                }
            }
        }
        val methods = System.getenv("MACROSTACK_METHODS")?.split(',')?.map { StackFusion.Method.valueOf(it.trim()) }
            ?: StackFusion.Method.entries
        if (System.getenv("MACROSTACK_ALIGN_ONLY") != null) {
            Parallel().use { parallel ->
                val fusion = StackFusion(parallel)
                runCatching {
                    fusion.run(frames, object : StackFusion.Listener {
                        var done = false
                        override fun onProgress(phase: StackFusion.Phase, done: Int, total: Int) {
                            if (phase != StackFusion.Phase.ALIGNING) this.done = true
                        }
                        override val isCancelled: Boolean get() = done
                    })
                }
                // Each frame's magnification relative to the middle one, chained from the measured steps.
                val chained = DoubleArray(files.size)
                chained[0] = 1.0
                for (k in 1 until files.size) chained[k] = chained[k - 1] * (fusion.lastSteps[k]?.scale ?: 1.0)
                val mid = chained[files.size / 2]
                println("  chained scale " + chained.joinToString(" ") { "%.4f".format(it / mid) })
            }
            return
        }
        Parallel().use { parallel ->
            for (method in methods) {
                val t0 = System.nanoTime()
                val fusion = StackFusion(parallel)
                val probes = System.getenv("MACROSTACK_PROBE")?.split(';')?.map { p -> p.split(',').map { it.trim().toInt() } }
                fusion.keepFuserForTests = probes != null
                var aligned = 0L
                val result = fusion.run(frames, object : StackFusion.Listener {
                    override fun onProgress(phase: StackFusion.Phase, done: Int, total: Int) {
                        if (phase != StackFusion.Phase.ALIGNING && aligned == 0L) aligned = System.nanoTime()
                    }
                }, method = method)
                println("  aligning took %.1f s".format((aligned - t0) / 1e9))
                println("$method: ${files.size} frames in %.1f s, crop ${result.crop}, unaligned ${result.unalignedPairs}"
                    .format((System.nanoTime() - t0) / 1e9))
                result.transforms.forEachIndexed { k, t -> println("  frame $k: $t") }
                fusion.lastSteps.forEachIndexed { k, s -> println("  step $k: scale %.6f".format(s?.scale ?: Double.NaN)) }
                println("  gains " + fusion.lastGains.joinToString(" ") { "%.3f".format(it) })
                val tag = method.name.lowercase()
                SyntheticStack.save(result.image, "real_$tag")
                fusion.lastDepthMap?.let { SyntheticStack.save(it, "real_${tag}_depth") }
                fusion.lastDepthFuser?.let { fuser -> probes?.forEach { (x, y) -> println(fuser.cellReport(x, y)) } }
            }
        }
    }

    private fun readPpm(file: File): RgbImage {
        DataInputStream(file.inputStream().buffered(1 shl 20)).use { input ->
            fun token(): String {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c == '#'.code) {
                        while (input.read() != '\n'.code) Unit
                        continue
                    }
                    if (c <= ' '.code) {
                        if (sb.isNotEmpty()) return sb.toString() else continue
                    }
                    sb.append(c.toChar())
                }
            }
            check(token() == "P6") { "${file.name}: not a binary PPM" }
            val w = token().toInt()
            val h = token().toInt()
            check(token() == "255")
            val bytes = ByteArray(w * h * 3)
            input.readFully(bytes)
            val px = IntArray(w * h) { i ->
                (0xFF shl 24) or ((bytes[3 * i].toInt() and 0xFF) shl 16) or
                    ((bytes[3 * i + 1].toInt() and 0xFF) shl 8) or (bytes[3 * i + 2].toInt() and 0xFF)
            }
            return RgbImage(w, h, px)
        }
    }
}
