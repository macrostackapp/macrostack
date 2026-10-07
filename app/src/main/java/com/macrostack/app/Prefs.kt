package com.macrostack.app

import android.content.Context
import androidx.core.content.edit
import com.macrostack.app.camera.PhotoFormat
import com.macrostack.app.fusion.StackFusion

/** Remembers settings and, per lens, the focus position and stack start/end points. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("macrostack", Context.MODE_PRIVATE)

    /** Merge every finished stack on the phone. */
    var stackOnPhone: Boolean
        get() = sp.getBoolean("stack_on_phone", true)
        set(v) = sp.edit { putBoolean("stack_on_phone", v) }

    /** How the phone merges a stack: depth map (default) or pyramid. */
    var mergeMethod: StackFusion.Method
        get() = when (val saved = sp.getString("merge_method", null)) {
            "DETAIL" -> StackFusion.Method.PYRAMID // name used before 1.7
            else -> StackFusion.Method.entries.firstOrNull { it.name == saved } ?: StackFusion.Method.DEPTH_MAP
        }
        set(v) = sp.edit { putString("merge_method", v.name) }

    /** The last shot stack, so it can be stacked again. */
    var lastStackFolder: String?
        get() = sp.getString("last_stack_folder", null)
        set(v) = sp.edit { putString("last_stack_folder", v) }

    var lastStackName: String?
        get() = sp.getString("last_stack_name", null)
        set(v) = sp.edit { putString("last_stack_name", v) }

    /** Notification permission (for stacking progress) has been asked for once. */
    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(v) = sp.edit { putBoolean("asked_notifications", v) }

    /** The how-to has been shown once. */
    var seenHelp: Boolean
        get() = sp.getBoolean("seen_help", false)
        set(v) = sp.edit { putBoolean("seen_help", v) }

    /** A frame from the last stack, for the thumbnail next to the shutter. */
    var lastStackUri: String?
        get() = sp.getString("last_stack_uri", null)
        set(v) = sp.edit { putString("last_stack_uri", v) }

    var lastStackMime: String?
        get() = sp.getString("last_stack_mime", null)
        set(v) = sp.edit { putString("last_stack_mime", v) }

    /** The lens button last chosen (see Lens.key). */
    var lensKey: String?
        get() = sp.getString("lens_key", null)
        set(v) = sp.edit { putString("lens_key", v) }

    /** Which way of reaching [lensKey] worked last time, so it is tried first. */
    fun workingAccess(lensKey: String): String? = sp.getString("access_$lensKey", null)
    fun setWorkingAccess(lensKey: String, accessKey: String) = sp.edit { putString("access_$lensKey", accessKey) }

    var frames: Int
        get() = sp.getInt("frames", 30)
        set(v) = sp.edit { putInt("frames", v) }

    /** Fast = one continuous burst; otherwise step-by-step (precise). */
    var fastMode: Boolean
        get() = sp.getBoolean("fast_mode", true)
        set(v) = sp.edit { putBoolean("fast_mode", v) }

    /** Fast mode: sensor frames given to the focus motor before each photo. */
    var settleFrames: Int
        get() = sp.getInt("settle_frames", 1)
        set(v) = sp.edit { putInt("settle_frames", v) }

    /** Full sensor resolution instead of the standard (≤ 16 MP, much faster) size. */
    var fullResolution: Boolean
        get() = sp.getBoolean("full_resolution", false)
        set(v) = sp.edit { putBoolean("full_resolution", v) }

    var photoFormat: PhotoFormat
        get() = PhotoFormat.fromName(sp.getString("photo_format", null))
        set(v) = sp.edit { putString("photo_format", v.name) }

    /** The camera's high-quality noise reduction and sharpening, rather than its fast variant. */
    var highQualityProcessing: Boolean
        get() = sp.getBoolean("hq_processing", true)
        set(v) = sp.edit { putBoolean("hq_processing", v) }

    var startDelaySec: Int
        get() = sp.getInt("start_delay", 2)
        set(v) = sp.edit { putInt("start_delay", v) }

    var settleMs: Int
        get() = sp.getInt("settle_ms", 0)
        set(v) = sp.edit { putInt("settle_ms", v) }

    var peakingEnabled: Boolean
        get() = sp.getBoolean("peaking", true)
        set(v) = sp.edit { putBoolean("peaking", v) }

    var peakingLevel: Int
        get() = sp.getInt("peaking_level", 1)
        set(v) = sp.edit { putInt("peaking_level", v) }

    var peakingColor: Int
        get() = sp.getInt("peaking_color", 0)
        set(v) = sp.edit { putInt("peaking_color", v) }

    var jpegQuality: Int
        get() = sp.getInt("jpeg_quality", 95)
        set(v) = sp.edit { putInt("jpeg_quality", v) }

    var stabilization: Boolean
        get() = sp.getBoolean("ois", true)
        set(v) = sp.edit { putBoolean("ois", v) }

    var volumeKeys: Boolean
        get() = sp.getBoolean("volume_keys", true)
        set(v) = sp.edit { putBoolean("volume_keys", v) }

    var beep: Boolean
        get() = sp.getBoolean("beep", true)
        set(v) = sp.edit { putBoolean("beep", v) }

    var manualExposure: Boolean
        get() = sp.getBoolean("manual_exposure", false)
        set(v) = sp.edit { putBoolean("manual_exposure", v) }

    var iso: Int
        get() = sp.getInt("iso", 100)
        set(v) = sp.edit { putInt("iso", v) }

    var exposureNs: Long
        get() = sp.getLong("exposure_ns", 16_666_667L)
        set(v) = sp.edit { putLong("exposure_ns", v) }

    var evIndex: Int
        get() = sp.getInt("ev_index", 0)
        set(v) = sp.edit { putInt("ev_index", v) }

    /** Measured speed of the last completed stack in each mode and format, used for the time estimate. */
    fun secondsPerFrame(fast: Boolean, raw: Boolean): Float =
        sp.getFloat(speedKey(fast, raw), if (fast) (if (raw) 0.2f else 0.12f) else 0.5f)

    fun setSecondsPerFrame(fast: Boolean, raw: Boolean, value: Float) =
        sp.edit { putFloat(speedKey(fast, raw), value) }

    private fun speedKey(fast: Boolean, raw: Boolean) =
        (if (fast) "spf_fast" else "spf_precise") + (if (raw) "_raw" else "")

    // Per-lens memory, keyed by how the lens is reached (focus scales differ between methods).

    fun focus(accessKey: String): Float? = getFloat("focus_$accessKey")
    fun setFocus(accessKey: String, value: Float) = putFloat("focus_$accessKey", value)

    fun start(accessKey: String): Float? = getFloat("start_$accessKey")
    fun setStart(accessKey: String, value: Float?) = putFloat("start_$accessKey", value)

    fun end(accessKey: String): Float? = getFloat("end_$accessKey")
    fun setEnd(accessKey: String, value: Float?) = putFloat("end_$accessKey", value)

    private fun getFloat(key: String): Float? = if (sp.contains(key)) sp.getFloat(key, 0f) else null

    private fun putFloat(key: String, value: Float?) = sp.edit {
        if (value == null) remove(key) else putFloat(key, value)
    }
}
