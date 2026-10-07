package com.macrostack.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

private const val REPEAT_START_MS = 400L
private const val REPEAT_MIN_MS = 40L

/** Runs [action] on press and keeps repeating, faster and faster, while the button is held. */
@SuppressLint("ClickableViewAccessibility")
fun View.onPressRepeat(action: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var delayMs = REPEAT_START_MS
    val repeater = object : Runnable {
        override fun run() {
            if (!isEnabled) return
            action()
            delayMs = max(REPEAT_MIN_MS, delayMs * 3 / 4)
            handler.postDelayed(this, delayMs)
        }
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!v.isEnabled) return@setOnTouchListener false
                v.isPressed = true
                action()
                delayMs = REPEAT_START_MS
                handler.postDelayed(repeater, REPEAT_START_MS)
            }

            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                handler.removeCallbacks(repeater)
                v.performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                handler.removeCallbacks(repeater)
            }
        }
        true
    }
}
