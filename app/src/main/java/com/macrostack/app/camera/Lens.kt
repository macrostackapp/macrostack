package com.macrostack.app.camera

import android.hardware.camera2.CameraManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One way of getting pictures out of a particular lens. Phones differ in which of these they allow
 * apps to use (Samsung, for example, hides its telephotos from the public camera list), so a [Lens]
 * carries several and the first one that works is used.
 */
sealed class LensAccess {
    /** Camera id passed to openCamera. */
    abstract val openId: String

    /** Characteristics that govern the session: stream sizes, exposure and control support. */
    abstract val caps: CameraCaps

    /** Characteristics of the glass actually used — its focus range. */
    open val optics: CameraCaps get() = caps

    /** The multi-lens camera that was opened, when the lens is reached through one. */
    open val logical: CameraCaps? get() = null

    /** Physical lens to route every stream to, or null. */
    open val physicalId: String? get() = null

    /** CONTROL_ZOOM_RATIO to request, or null. */
    open val zoomRatio: Float? get() = null

    /** Stable key for remembering focus points. */
    abstract val key: String

    /** How this lens is being reached, shown on screen and in Camera info. */
    abstract val description: String

    val maxFocusDiopters: Float get() = optics.minFocusDiopters
    val supportsManualFocus: Boolean get() = optics.minFocusDiopters > 0f && caps.afOffAvailable

    /** Open the camera id directly (a listed camera, or a hidden one the phone lets us open). */
    class Direct(override val caps: CameraCaps, private val hidden: Boolean) : LensAccess() {
        override val openId: String get() = caps.id
        override val key: String get() = "c${caps.id}"
        override val description: String get() = if (hidden) "hidden camera ${caps.id}" else "camera ${caps.id}"
    }

    /** Open the multi-lens camera and ask for every stream to come from one of its lenses. */
    class PhysicalStream(override val logical: CameraCaps, override val caps: CameraCaps) : LensAccess() {
        override val openId: String get() = logical.id
        override val physicalId: String get() = caps.id
        override val key: String get() = "p${logical.id}_${caps.id}"
        override val description: String get() = "lens ${caps.id} of camera ${logical.id}"
    }

    /** Open the multi-lens camera at a zoom ratio; the phone switches to the matching lens itself. */
    class Zoom(
        override val logical: CameraCaps,
        override val zoomRatio: Float,
        private val expected: CameraCaps?,
    ) : LensAccess() {
        override val caps: CameraCaps get() = logical
        override val optics: CameraCaps get() = expected ?: logical
        override val openId: String get() = logical.id
        override val key: String get() = "z${logical.id}_$zoomRatio"
        override val description: String
            get() = "camera ${logical.id} at ${LensMath.formatRatio(zoomRatio)} zoom"
    }
}

/** A lens button (0.6×, 1×, 3×, 5×…). */
class Lens(
    val key: String,
    val label: String,
    /** Focal length relative to the main camera, or null when the phone doesn't say. */
    val ratio: Float?,
    val focalEq: Int?,
    val accesses: List<LensAccess>,
)

object LensMath {
    private const val SAME_LENS_TOLERANCE = 0.15f

    fun sameLens(a: Float, b: Float): Boolean = abs(a / b - 1f) < SAME_LENS_TOLERANCE

    /** Button label: "0.6×", "1×", "3×", "5×"… */
    fun label(ratio: Float): String = when {
        ratio < 0.95f -> formatRatio(ratio)
        ratio < 1.5f -> if (abs(ratio - 1f) < 0.1f) "1×" else formatRatio(ratio)
        abs(ratio - ratio.roundToInt()) < 0.3f -> "${ratio.roundToInt()}×"
        else -> formatRatio(ratio)
    }

    fun formatRatio(ratio: Float): String =
        if (abs(ratio - ratio.roundToInt()) < 0.05f) "${ratio.roundToInt()}×"
        else String.format(Locale.US, "%.1f×", ratio)

    /**
     * The zoom ratio to request for a lens of focal-length [ratio]. Phones switch to a telephoto at its
     * marketed ratio (exactly 3.0, 5.0…), so those are rounded; returns null when out of [lower, upper].
     */
    fun zoomRatioFor(ratio: Float, lower: Float, upper: Float): Float? {
        val z = when {
            ratio >= 1.5f -> ratio.roundToInt().toFloat()
            // Ultra-wides: 0.56 → 0.6, and a range that bottoms out at 0.55 or 0.6 still reaches it.
            ratio < 1f -> (ratio * 10).roundToInt() / 10f
            else -> ratio
        }
        return if (z >= lower - 0.06f && z <= upper + 0.01f) z.coerceIn(lower, upper) else null
    }
}

/** Finds every rear lens the phone will give an app, including ones it doesn't list publicly. */
object LensCatalog {

    class Result(
        val lenses: List<Lens>,
        /** Every camera we could read, by id — public, physical and hidden. */
        val camerasById: Map<String, CameraCaps>,
        val report: String,
    )

    private const val PROBE_LIMIT = 64

    private enum class Kind { PUBLIC, PHYSICAL, PROBED }

    private class Candidate(val caps: CameraCaps, val kind: Kind, val parent: CameraCaps?)

    private class Group(val ratio: Float?, val focalEq: Int?, val firstId: String) {
        val accesses = mutableListOf<LensAccess>()
        fun addAll(more: List<LensAccess>) {
            for (a in more) if (accesses.none { it.key == a.key }) accesses += a
        }
    }

    fun discover(manager: CameraManager): Result {
        val report = StringBuilder()
        val all = LinkedHashMap<String, CameraCaps>()
        val candidates = mutableListOf<Candidate>()

        // 1. Cameras the phone lists for apps.
        val publicIds = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            emptyList()
        }
        report.appendLine("Camera IDs listed for apps: ${publicIds.joinToString().ifEmpty { "none" }}")
        for (id in publicIds) {
            val caps = CameraCaps.load(manager, id) ?: continue
            all[id] = caps
            report.appendLine("  $id: ${caps.summary()}")
            if (caps.isBack) candidates += Candidate(caps, Kind.PUBLIC, null)
        }
        val publicBack = candidates.map { it.caps }

        // 2. Individual lenses inside multi-lens cameras.
        for (logical in publicBack) {
            if (logical.physicalCameraIds.isEmpty()) continue
            report.appendLine("Camera ${logical.id} combines lenses: ${logical.physicalCameraIds.joinToString()}")
            for (pid in logical.physicalCameraIds) {
                if (pid in all) continue
                val caps = CameraCaps.load(manager, pid, quiet = true)
                if (caps == null) {
                    report.appendLine("  $pid: no details")
                    continue
                }
                all[pid] = caps
                report.appendLine("  $pid: ${caps.summary()}")
                if (caps.facing == null || caps.isBack) candidates += Candidate(caps, Kind.PHYSICAL, logical)
            }
        }

        // 3. Camera ids the phone didn't mention at all.
        val hiddenLines = mutableListOf<String>()
        for (n in 0 until PROBE_LIMIT) {
            val id = n.toString()
            if (id in all) continue
            val caps = CameraCaps.load(manager, id, quiet = true) ?: continue
            all[id] = caps
            hiddenLines += "  $id: ${caps.summary()}"
            if (caps.isBack) candidates += Candidate(caps, Kind.PROBED, null)
        }
        if (hiddenLines.isEmpty()) {
            report.appendLine("No unlisted camera IDs found (checked 0–${PROBE_LIMIT - 1})")
        } else {
            report.appendLine("Unlisted camera IDs:")
            hiddenLines.forEach { report.appendLine(it) }
        }

        // Group everything by focal length relative to the main camera.
        val main = publicBack.firstOrNull { it.id == "0" } ?: publicBack.firstOrNull()
        val mainEq = main?.equivalentFocalMm?.toFloat()
        val zoomHost = main?.takeIf { it.zoomRatioRange != null }

        fun ratioOf(c: CameraCaps): Float? {
            if (c === main) return 1f
            val eq = c.equivalentFocalMm ?: return null
            return if (mainEq != null && mainEq > 0f) eq / mainEq else null
        }

        fun zoomVia(host: CameraCaps?, ratio: Float?, expected: CameraCaps?): LensAccess? {
            val range = host?.zoomRatioRange ?: return null
            val z = LensMath.zoomRatioFor(ratio ?: return null, range.lower, range.upper) ?: return null
            return LensAccess.Zoom(host, z, expected)
        }

        val groups = mutableListOf<Group>()
        for (c in candidates) {
            val r = ratioOf(c.caps)
            val accesses = when (c.kind) {
                Kind.PUBLIC -> listOf(LensAccess.Direct(c.caps, hidden = false))
                Kind.PHYSICAL -> listOfNotNull(
                    LensAccess.Direct(c.caps, hidden = true),
                    LensAccess.PhysicalStream(c.parent!!, c.caps),
                    zoomVia(c.parent, r, c.caps),
                )
                Kind.PROBED -> listOfNotNull(
                    LensAccess.Direct(c.caps, hidden = true),
                    zoomVia(zoomHost, r, c.caps),
                )
            }
            val group = if (r == null) null else groups.firstOrNull { it.ratio != null && LensMath.sameLens(it.ratio, r) }
            (group ?: Group(r, c.caps.equivalentFocalMm, c.caps.id).also { groups += it }).addAll(accesses)
        }

        // Zoom presets for lenses that couldn't be found any other way: the phone picks the lens.
        val range = zoomHost?.zoomRatioRange
        if (zoomHost != null && range != null) {
            val presets = buildList {
                if (range.lower <= 0.7f) add(range.lower)
                if (range.upper >= 3f) add(3f)
                if (range.upper >= 5f) add(5f)
            }
            for (p in presets) {
                if (groups.none { it.ratio != null && LensMath.sameLens(it.ratio, p) }) {
                    groups += Group(p, null, zoomHost.id).apply { addAll(listOf(LensAccess.Zoom(zoomHost, p, null))) }
                }
            }
        }

        groups.sortWith(compareBy(nullsLast()) { it.ratio })
        val usedLabels = mutableSetOf<String>()
        val lenses = groups.map { g ->
            var label = g.ratio?.let { LensMath.label(it) } ?: "#${g.firstId}"
            if (!usedLabels.add(label)) label = "$label${g.firstId}"
            Lens(key = "lens:$label", label = label, ratio = g.ratio, focalEq = g.focalEq, accesses = g.accesses)
        }

        report.appendLine("Lens buttons:")
        for (l in lenses) {
            report.append("  ").append(l.label)
            l.focalEq?.let { report.append(" ($it mm)") }
            report.append(": ").appendLine(l.accesses.joinToString(" → ") { it.description })
        }
        return Result(lenses, all, report.toString())
    }
}
