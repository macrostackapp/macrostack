package com.macrostack.app.camera

import android.util.Rational
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/** The ISO and shutter steps offered in manual exposure, plus formatting helpers. */
object ExposureValues {

    private val ISO_STEPS = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
    )

    // 1/100 and 1/50 are included because they avoid flicker under 50 Hz / 60 Hz lights.
    private val SHUTTER_STEPS_NS = longArrayOf(
        125_000, 250_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 8_000_000, 10_000_000,
        16_666_667, 20_000_000, 33_333_333, 66_666_667, 125_000_000, 250_000_000, 500_000_000,
        1_000_000_000, 2_000_000_000,
    )

    fun isoOptions(caps: CameraCaps): List<Int> {
        val range = caps.isoRange ?: return emptyList()
        return ISO_STEPS.filter { it in range.lower..range.upper }
    }

    fun shutterOptions(caps: CameraCaps): List<Long> {
        val range = caps.exposureRangeNs ?: return emptyList()
        return SHUTTER_STEPS_NS.filter { it in range.lower..range.upper }
    }

    fun nearestIso(options: List<Int>, value: Int): Int =
        options.minByOrNull { abs(ln(it.toDouble() / value)) } ?: value

    fun nearestShutter(options: List<Long>, value: Long): Long =
        options.minByOrNull { abs(ln(it.toDouble() / value)) } ?: value

    fun formatShutter(ns: Long): String {
        if (ns <= 0) return "—"
        val seconds = ns / 1e9
        return if (seconds >= 0.95) {
            if (abs(seconds - seconds.roundToLong()) < 0.05) "${seconds.roundToLong()}s"
            else "%.1fs".format(Locale.US, seconds)
        } else {
            "1/${(1 / seconds).roundToLong()}"
        }
    }

    fun formatEv(index: Int, step: Rational): String =
        "%+.1f".format(Locale.US, index * step.toFloat())
}
