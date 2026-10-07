package com.macrostack.app.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.isVisible
import com.macrostack.app.BuildConfig
import com.macrostack.app.Prefs
import com.macrostack.app.R
import com.macrostack.app.camera.Peaking
import com.macrostack.app.camera.PhotoFormat
import com.macrostack.app.fusion.StackFusion

/**
 * Settings, grouped and applied as you tap — no OK button. Choices that need the camera reopened
 * (photo format and size) take effect when the sheet closes.
 */
class SettingsSheet(
    private val activity: Activity,
    private val prefs: Prefs,
    private val oisSupported: Boolean,
    /** Called after any change that can apply right away. */
    private val onChanged: () -> Unit,
    /** Called when the sheet closes; true when the camera must be reopened. */
    private val onClosed: (reopenCamera: Boolean) -> Unit,
    private val onHelp: () -> Unit,
    private val onCameraInfo: () -> Unit,
) {

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_settings, null)
        val initialFormat = prefs.photoFormat
        val initialFullResolution = prefs.fullResolution

        // Photos
        val formatHint = view.findViewById<TextView>(R.id.formatHint)
        fun showFormatHint() {
            formatHint.text = activity.getString(formatHintFor(prefs.photoFormat))
        }
        view.findViewById<SegmentedControl>(R.id.formatControl).apply {
            setOptions(listOf("JPEG", "RAW", "RAW + JPEG"), prefs.photoFormat.ordinal)
            onSelected = {
                prefs.photoFormat = PhotoFormat.entries[it]
                showFormatHint()
            }
        }
        showFormatHint()
        view.findViewById<SegmentedControl>(R.id.sizeControl).apply {
            setOptions(listOf("Standard · fast", "Maximum"), if (prefs.fullResolution) 1 else 0)
            onSelected = { prefs.fullResolution = it == 1 }
        }
        view.findViewById<SegmentedControl>(R.id.processingControl).apply {
            setOptions(listOf("High quality", "Fast"), if (prefs.highQualityProcessing) 0 else 1)
            onSelected = {
                prefs.highQualityProcessing = it == 0
                onChanged()
            }
        }
        view.findViewById<SegmentedControl>(R.id.qualityControl).apply {
            setOptions(QUALITIES.map { "$it" }, QUALITIES.indexOf(prefs.jpegQuality))
            onSelected = { prefs.jpegQuality = QUALITIES[it] }
        }

        // Speed
        val modeHint = view.findViewById<TextView>(R.id.modeHint)
        val settleFramesGroup = view.findViewById<View>(R.id.settleFramesGroup)
        val settleMsGroup = view.findViewById<View>(R.id.settleMsGroup)
        fun showMode() {
            modeHint.text = activity.getString(if (prefs.fastMode) R.string.mode_fast_hint else R.string.mode_precise_hint)
            settleFramesGroup.isVisible = prefs.fastMode
            settleMsGroup.isVisible = !prefs.fastMode
        }
        view.findViewById<SegmentedControl>(R.id.modeControl).apply {
            setOptions(listOf("Fast", "Precise"), if (prefs.fastMode) 0 else 1)
            onSelected = {
                prefs.fastMode = it == 0
                showMode()
                onChanged()
            }
        }
        showMode()
        view.findViewById<SegmentedControl>(R.id.settleFramesControl).apply {
            setOptions(SETTLE_FRAMES.map { if (it == 0) "None" else "$it" }, SETTLE_FRAMES.indexOf(prefs.settleFrames))
            onSelected = { prefs.settleFrames = SETTLE_FRAMES[it] }
        }
        view.findViewById<SegmentedControl>(R.id.settleMsControl).apply {
            setOptions(listOf("None", "0.1 s", "0.25 s", "0.5 s", "1 s"), SETTLE_MS.indexOf(prefs.settleMs))
            onSelected = { prefs.settleMs = SETTLE_MS[it] }
        }

        // Shooting
        view.findViewById<SegmentedControl>(R.id.delayControl).apply {
            setOptions(DELAYS.map { if (it == 0) "Off" else "$it s" }, DELAYS.indexOf(prefs.startDelaySec))
            onSelected = {
                prefs.startDelaySec = DELAYS[it]
                onChanged()
            }
        }
        view.findViewById<Switch>(R.id.volumeSwitch).apply {
            isChecked = prefs.volumeKeys
            setOnCheckedChangeListener { _, on -> prefs.volumeKeys = on }
        }
        view.findViewById<Switch>(R.id.beepSwitch).apply {
            isChecked = prefs.beep
            setOnCheckedChangeListener { _, on -> prefs.beep = on }
        }
        view.findViewById<Switch>(R.id.oisSwitch).apply {
            isChecked = prefs.stabilization
            isEnabled = oisSupported
            setOnCheckedChangeListener { _, on ->
                prefs.stabilization = on
                onChanged()
            }
        }

        // Stacking on the phone
        view.findViewById<Switch>(R.id.stackSwitch).apply {
            isChecked = prefs.stackOnPhone
            setOnCheckedChangeListener { _, on -> prefs.stackOnPhone = on }
        }
        val methodHint = view.findViewById<TextView>(R.id.methodHint)
        fun showMethodHint() {
            methodHint.text = activity.getString(
                if (prefs.mergeMethod == StackFusion.Method.DEPTH_MAP) R.string.method_depth_map_hint else R.string.method_pyramid_hint
            )
        }
        view.findViewById<SegmentedControl>(R.id.methodControl).apply {
            setOptions(
                listOf(activity.getString(R.string.method_depth_map), activity.getString(R.string.method_pyramid)),
                prefs.mergeMethod.ordinal,
            )
            onSelected = {
                prefs.mergeMethod = StackFusion.Method.entries[it]
                showMethodHint()
            }
        }
        showMethodHint()

        // Focus peaking
        view.findViewById<SegmentedControl>(R.id.peakingControl).apply {
            setOptions(Peaking.LEVEL_NAMES.toList(), prefs.peakingLevel)
            onSelected = {
                prefs.peakingLevel = it
                onChanged()
            }
        }
        buildSwatches(view.findViewById(R.id.colorSwatches))

        view.findViewById<TextView>(R.id.versionText).text = "MacroStack ${BuildConfig.VERSION_NAME}"

        lateinit var sheet: BottomSheet
        view.findViewById<View>(R.id.helpRow).setOnClickListener {
            sheet.dismiss()
            onHelp()
        }
        view.findViewById<View>(R.id.cameraInfoRow).setOnClickListener {
            sheet.dismiss()
            onCameraInfo()
        }
        sheet = BottomSheet(activity, view, scrollable = true)
            .setOnDismiss {
                onClosed(prefs.photoFormat != initialFormat || prefs.fullResolution != initialFullResolution)
            }
            .show()
    }

    private fun buildSwatches(row: LinearLayout) {
        val density = activity.resources.displayMetrics.density
        val size = (30 * density).toInt()
        val gap = (14 * density).toInt()
        val swatches = Peaking.COLORS.mapIndexed { index, color ->
            View(activity).apply {
                contentDescription = Peaking.COLOR_NAMES[index]
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                tag = color
            }
        }
        fun refresh() {
            swatches.forEachIndexed { index, swatch ->
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatch.tag as Int)
                    if (index == prefs.peakingColor) setStroke((3 * density).toInt(), 0xFFFFFFFF.toInt())
                }
                swatch.isSelected = index == prefs.peakingColor
            }
        }
        swatches.forEachIndexed { index, swatch ->
            swatch.setOnClickListener {
                prefs.peakingColor = index
                refresh()
                onChanged()
            }
            row.addView(swatch)
        }
        refresh()
    }

    companion object {
        private val QUALITIES = listOf(90, 95, 100)
        private val SETTLE_FRAMES = listOf(0, 1, 2, 3)
        private val SETTLE_MS = listOf(0, 100, 250, 500, 1000)
        private val DELAYS = listOf(0, 2, 5, 10)

        fun formatHintFor(format: PhotoFormat): Int = when (format) {
            PhotoFormat.JPEG -> R.string.format_jpeg_hint
            PhotoFormat.RAW -> R.string.format_raw_hint
            PhotoFormat.RAW_JPEG -> R.string.format_raw_jpeg_hint
        }
    }
}
