package com.macrostack.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.macrostack.app.R
import kotlin.math.min

/**
 * The big round shutter. Ready: an amber disc. Counting down: the seconds left. Shooting: a stop
 * square inside a progress ring. Saving: a spinning ring.
 */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class Mode { READY, COUNTDOWN, SHOOTING, SAVING }

    private var mode = Mode.READY
    private var progress = 0f
    private var secondsLeft = 0

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val accent = context.getColor(R.color.accent)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * dp
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * dp
        strokeCap = Paint.Cap.ROUND
        color = accent
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.on_accent)
        textSize = 26 * sp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val oval = RectF()

    init {
        isClickable = true
        isFocusable = true
        showReady()
    }

    fun showReady() = update(Mode.READY, "Start stack")

    fun showCountdown(seconds: Int) {
        secondsLeft = seconds
        update(Mode.COUNTDOWN, "Cancel countdown")
    }

    fun showProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        update(Mode.SHOOTING, "Stop stack")
    }

    fun showSaving() = update(Mode.SAVING, "Saving")

    private fun update(newMode: Mode, description: String) {
        mode = newMode
        contentDescription = description
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 2.5f * dp
        oval.set(cx - r, cy - r, cx + r, cy + r)

        when (mode) {
            Mode.READY, Mode.COUNTDOWN -> {
                ringPaint.color = if (isEnabled) 0xE6FFFFFF.toInt() else 0x40FFFFFF
                canvas.drawCircle(cx, cy, r, ringPaint)
                val inner = r - (if (isPressed) 10f else 7.5f) * dp
                fillPaint.color = if (isEnabled) accent else context.getColor(R.color.surface_high)
                canvas.drawCircle(cx, cy, inner, fillPaint)
                if (mode == Mode.COUNTDOWN) {
                    canvas.drawText(secondsLeft.toString(), cx, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
                }
            }

            Mode.SHOOTING, Mode.SAVING -> {
                ringPaint.color = 0x33FFFFFF
                canvas.drawCircle(cx, cy, r, ringPaint)
                if (mode == Mode.SHOOTING) {
                    canvas.drawArc(oval, -90f, 360f * progress, false, arcPaint)
                } else {
                    val spin = (SystemClock.uptimeMillis() % SPIN_MS) / SPIN_MS.toFloat() * 360f
                    canvas.drawArc(oval, spin - 90f, 100f, false, arcPaint)
                    postInvalidateOnAnimation()
                }
                val half = (if (isPressed) 11f else 13f) * dp
                fillPaint.color = if (mode == Mode.SHOOTING) context.getColor(R.color.danger) else 0x66FFFFFF
                oval.set(cx - half, cy - half, cx + half, cy + half)
                canvas.drawRoundRect(oval, 5 * dp, 5 * dp, fillPaint)
            }
        }
    }

    private companion object {
        const val SPIN_MS = 900L
    }
}
