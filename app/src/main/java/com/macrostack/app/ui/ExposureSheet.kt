package com.macrostack.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.macrostack.app.R

/** Auto (with brightness) or manual ISO and shutter, with what the camera is doing right now. */
class ExposureSheet(private val activity: Activity, private val host: Host) {

    interface Host {
        fun isManual(): Boolean

        /** Returns false if this lens can't do manual exposure. */
        fun setManual(manual: Boolean): Boolean
        fun stepEv(delta: Int)
        fun stepIso(delta: Int)
        fun stepShutter(delta: Int)
        fun evText(): String
        fun isoText(): String
        fun shutterText(): String

        /** What the camera is using at this moment, e.g. "Camera now: ISO 320 · 1/50". */
        fun liveText(): String
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var modeControl: SegmentedControl
    private lateinit var autoGroup: View
    private lateinit var manualGroup: View
    private lateinit var evValue: TextView
    private lateinit var isoValue: TextView
    private lateinit var shutterValue: TextView
    private lateinit var liveReadout: TextView

    private val liveUpdater = object : Runnable {
        override fun run() {
            liveReadout.text = host.liveText()
            handler.postDelayed(this, 500)
        }
    }

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_exposure, null)
        modeControl = view.findViewById(R.id.exposureModeControl)
        autoGroup = view.findViewById(R.id.autoGroup)
        manualGroup = view.findViewById(R.id.manualGroup)
        evValue = view.findViewById(R.id.evValue)
        isoValue = view.findViewById(R.id.isoValue)
        shutterValue = view.findViewById(R.id.shutterValue)
        liveReadout = view.findViewById(R.id.liveReadout)

        modeControl.setOptions(
            listOf(activity.getString(R.string.auto), activity.getString(R.string.manual)),
            if (host.isManual()) 1 else 0,
        )
        modeControl.onSelected = { index ->
            if (!host.setManual(index == 1)) modeControl.select(0)
            refresh()
        }
        view.findViewById<View>(R.id.evMinus).onPressRepeat { host.stepEv(-1); refresh() }
        view.findViewById<View>(R.id.evPlus).onPressRepeat { host.stepEv(+1); refresh() }
        view.findViewById<View>(R.id.isoMinus).onPressRepeat { host.stepIso(-1); refresh() }
        view.findViewById<View>(R.id.isoPlus).onPressRepeat { host.stepIso(+1); refresh() }
        view.findViewById<View>(R.id.shutterMinus).onPressRepeat { host.stepShutter(-1); refresh() }
        view.findViewById<View>(R.id.shutterPlus).onPressRepeat { host.stepShutter(+1); refresh() }

        refresh()
        handler.post(liveUpdater)
        BottomSheet(activity, view)
            .setOnDismiss { handler.removeCallbacks(liveUpdater) }
            .show()
    }

    private fun refresh() {
        val manual = host.isManual()
        autoGroup.isVisible = !manual
        manualGroup.isVisible = manual
        evValue.text = host.evText()
        isoValue.text = host.isoText()
        shutterValue.text = host.shutterText()
    }
}
