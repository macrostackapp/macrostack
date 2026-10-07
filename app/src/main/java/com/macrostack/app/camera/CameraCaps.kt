package com.macrostack.app.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Everything the app needs to know about one camera, read once from its [CameraCharacteristics]. */
class CameraCaps private constructor(val id: String, val characteristics: CameraCharacteristics) {

    /** Output sizes for one session: the photo outputs (JPEG and/or RAW — at least one is set) and the preview. */
    data class Streams(val jpeg: Size?, val raw: Size?, val preview: Size)

    val facing: Int? = characteristics[CameraCharacteristics.LENS_FACING]
    val isBack: Boolean get() = facing == CameraMetadata.LENS_FACING_BACK
    val sensorOrientation: Int = characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
    val hardwareLevel: Int = characteristics[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]
        ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

    private val capabilities: IntArray =
        characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: IntArray(0)
    val supportsManualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val supportsRaw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
    val supportsBurst = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities
    val isLogicalMultiCamera =
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities

    /** The individual lenses a logical (multi-lens) camera is made of. */
    val physicalCameraIds: Set<String> =
        if (isLogicalMultiCamera) characteristics.physicalCameraIds else emptySet()

    /** Request keys that may be set separately for each physical lens of a logical camera. */
    val physicalRequestKeys: Set<CaptureRequest.Key<*>> =
        characteristics.availablePhysicalCameraRequestKeys?.toSet() ?: emptySet()

    val zoomRatioRange: Range<Float>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) characteristics[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
        else null

    /** Closest focus in diopters (1 / metres). 0 means a fixed-focus lens. */
    val minFocusDiopters: Float =
        characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
    val focusCalibration: Int =
        characteristics[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]
            ?: CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
    private val afModes: IntArray =
        characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: IntArray(0)
    val afOffAvailable = afModes.isEmpty() || CameraMetadata.CONTROL_AF_MODE_OFF in afModes

    /** True when apps can drive the focus motor directly — the one thing focus stacking needs. */
    val supportsManualFocus = minFocusDiopters > 0f && afOffAvailable

    val isoRange: Range<Int>? = characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
    val exposureRangeNs: Range<Long>? =
        characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
    val supportsManualExposure = supportsManualSensor && isoRange != null && exposureRangeNs != null
    val evRange: Range<Int> =
        characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] ?: Range(0, 0)
    val evStep: Rational =
        characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP] ?: Rational(1, 3)
    val supportsPostRawBoost =
        characteristics[CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE] != null

    private val aeFpsRanges: List<Range<Int>> =
        characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList().orEmpty()

    /**
     * The fastest steady frame rate for auto exposure (ideally a fixed 30 fps), so AE never picks
     * shutter speeds slow enough to throttle a burst.
     */
    val fastFpsRange: Range<Int>? = aeFpsRanges
        .filter { it.upper <= 30 }
        .maxWithOrNull(compareBy<Range<Int>>({ it.lower }, { it.upper }))

    /** 0 = settings apply on the exact frame they were requested for; -1 = unknown. */
    val syncMaxLatency: Int =
        characteristics[CameraCharacteristics.SYNC_MAX_LATENCY] ?: CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN

    private val noiseReductionModes: IntArray =
        characteristics[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES] ?: IntArray(0)
    private val edgeModes: IntArray = characteristics[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES] ?: IntArray(0)
    val supportsFastNoiseReduction = CameraMetadata.NOISE_REDUCTION_MODE_FAST in noiseReductionModes
    val supportsFastEdge = CameraMetadata.EDGE_MODE_FAST in edgeModes
    val supportsEdgeOff = CameraMetadata.EDGE_MODE_OFF in edgeModes

    private val oisModes: IntArray =
        characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: IntArray(0)
    val supportsOis = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes
    val canControlOis = oisModes.isNotEmpty()

    val focalLengthMm: Float? =
        characteristics[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()
    val equivalentFocalMm: Int? = run {
        val sensor = characteristics[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        val f = focalLengthMm
        if (sensor == null || f == null || sensor.width <= 0f) null
        else (f * FULL_FRAME_DIAGONAL_MM / hypot(sensor.width, sensor.height)).roundToInt()
    }

    private val streamMap: StreamConfigurationMap =
        characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            ?: throw IllegalStateException("Camera $id has no stream configuration")

    /** JPEG sizes, largest first. */
    private val jpegSizes: List<Size> =
        streamMap.getOutputSizes(ImageFormat.JPEG).orEmpty().sortedByDescending { it.area }

    init {
        if (jpegSizes.isEmpty()) throw IllegalStateException("Camera $id cannot output JPEG")
    }

    /** Unprocessed sensor output (saved as DNG), or null when this camera doesn't offer RAW to apps. */
    val rawSize: Size? =
        if (supportsRaw) streamMap.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty().maxByOrNull { it.area }
        else null

    /** DNGs need the lens-shading map, or RAW editors can't correct the phone's strong vignetting. */
    val supportsLensShadingMap: Boolean =
        characteristics[CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES]
            ?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true

    /**
     * Picks session sizes for [format]; falls back to JPEG when this camera has no RAW. Standard JPEGs
     * are ≤ ~16 MP, the sensor's fast binned mode on high-megapixel phones; full resolution can be many
     * times slower to capture and save.
     */
    fun streams(fullResolution: Boolean, format: PhotoFormat): Streams {
        val raw = if (format.raw) rawSize else null
        val jpeg = if (format.jpeg || raw == null) pickJpegSize(fullResolution) else null
        val photo = jpeg ?: raw!!

        val previewSizes = streamMap.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val preview = previewSizes
            .filter { it.matches(photo.aspect) && it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.area }
            ?: previewSizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.area }
            ?: previewSizes.firstOrNull()
            ?: throw IllegalStateException("Camera $id has no preview sizes")

        return Streams(jpeg, raw, preview)
    }

    private fun pickJpegSize(fullResolution: Boolean): Size {
        val largest = jpegSizes.first()
        if (fullResolution) return largest
        val sameShape = jpegSizes.filter { it.matches(largest.aspect) }
        return sameShape.firstOrNull { it.area <= STANDARD_MAX_PIXELS }
            ?: jpegSizes.firstOrNull { it.area <= STANDARD_MAX_PIXELS }
            ?: jpegSizes.last()
    }

    /** Rough ceiling on burst speed (photos per second) for [format] images of [size], or null if unknown. */
    fun maxBurstFps(format: Int, size: Size): Float? {
        val ns = streamMap.getOutputMinFrameDuration(format, size) +
            streamMap.getOutputStallDuration(format, size)
        return if (ns > 0) 1e9f / ns else null
    }

    val hardwareLevelName: String
        get() = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN($hardwareLevel)"
        }

    /** One line for the camera list in "Camera info". */
    fun summary(): String = buildString {
        append(
            when (facing) {
                CameraMetadata.LENS_FACING_BACK -> "back"
                CameraMetadata.LENS_FACING_FRONT -> "front"
                CameraMetadata.LENS_FACING_EXTERNAL -> "external"
                else -> "facing ?"
            }
        )
        focalLengthMm?.let { append(" · %.2f mm".format(Locale.US, it)) }
        equivalentFocalMm?.let { append(" (≈$it mm)") }
        append(" · ").append(hardwareLevelName)
        append(" · ").append(
            if (supportsManualFocus) "MF to %.2f D".format(Locale.US, minFocusDiopters) else "no MF"
        )
        if (isLogicalMultiCamera) append(" · multi-lens")
        zoomRatioRange?.let { append(" · zoom %.1f–%.0f×".format(Locale.US, it.lower, it.upper)) }
    }

    /** Full capability report, shown in "Camera info" so problems can be diagnosed remotely. */
    fun describe(streams: Streams? = null): String = buildString {
        fun yes(b: Boolean) = if (b) "yes" else "NO"
        appendLine("── Camera $id ──")
        appendLine(summary())
        appendLine("Manual focus: ${yes(supportsManualFocus)}")
        appendLine(
            "Closest focus: %.2f D%s".format(
                Locale.US, minFocusDiopters,
                if (minFocusDiopters > 0f) " (≈ %.1f cm)".format(Locale.US, 100f / minFocusDiopters) else "",
            )
        )
        val calibration = when (focusCalibration) {
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
            else -> "uncalibrated"
        }
        appendLine("Focus calibration: $calibration")
        appendLine("Manual sensor (ISO/shutter): ${yes(supportsManualSensor)}")
        isoRange?.let { appendLine("ISO range: ${it.lower}–${it.upper}") }
        exposureRangeNs?.let {
            appendLine(
                "Shutter range: ${ExposureValues.formatShutter(it.lower)} – ${ExposureValues.formatShutter(it.upper)}"
            )
        }
        appendLine("EV compensation: ${evRange.lower}..${evRange.upper} × $evStep")
        appendLine("AE fps ranges: ${aeFpsRanges.joinToString()} → using ${fastFpsRange ?: "default"}")
        appendLine("Burst capability: ${yes(supportsBurst)} · per-frame sync latency: $syncMaxLatency")
        appendLine("OIS: ${if (supportsOis) "yes" else "no"}")
        append("RAW: ${yes(rawSize != null)}")
        rawSize?.let { size ->
            append(" (${size.width}×${size.height}")
            maxBurstFps(ImageFormat.RAW_SENSOR, size)?.let { append(", ≤ %.0f/s".format(Locale.US, it)) }
            append(", shading map ${yes(supportsLensShadingMap)})")
        }
        appendLine()
        if (physicalCameraIds.isNotEmpty()) {
            appendLine("Lenses inside: ${physicalCameraIds.joinToString()}")
            appendLine(
                "Per-lens focus control: " +
                    yes(CaptureRequest.LENS_FOCUS_DISTANCE in physicalRequestKeys)
            )
        }
        zoomRatioRange?.let { appendLine("Zoom ratio range: ${it.lower}–${it.upper}") }
        appendLine("Sensor orientation: $sensorOrientation°")
        appendLine("JPEG sizes: ${jpegSizes.take(6).joinToString { "${it.width}×${it.height}" }}")
        streams?.let { s ->
            s.jpeg?.let { jpeg ->
                append("JPEG size in use: ${jpeg.width}×${jpeg.height}")
                maxBurstFps(ImageFormat.JPEG, jpeg)?.let { append(" (≤ %.0f photos/s)".format(Locale.US, it)) }
                appendLine()
            }
            s.raw?.let { appendLine("RAW size in use: ${it.width}×${it.height}") }
            appendLine("Preview size: ${s.preview.width}×${s.preview.height}")
        }
    }

    companion object {
        private const val TAG = "CameraCaps"
        private const val FULL_FRAME_DIAGONAL_MM = 43.27f
        private const val STANDARD_MAX_PIXELS = 16_800_000L

        /** Reads a camera's characteristics, or returns null if the phone won't describe it. */
        fun load(manager: CameraManager, id: String, quiet: Boolean = false): CameraCaps? = try {
            CameraCaps(id, manager.getCameraCharacteristics(id))
        } catch (e: Exception) {
            if (!quiet) Log.w(TAG, "Skipping camera $id", e)
            null
        }
    }
}

private val Size.area: Long get() = width.toLong() * height
private val Size.aspect: Float get() = width.toFloat() / height
private fun Size.matches(aspect: Float) = abs(this.aspect - aspect) < 0.01f
