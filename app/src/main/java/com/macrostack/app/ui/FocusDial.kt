package com.macrostack.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.macrostack.app.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manual focus as a scroll wheel, like the focus dial in a camera's Pro mode.
 *
 * A ruler slides under a fixed needle; drag or flick it to focus. It shows a fifth of the focus
 * range at a time, which makes small moves easy. The strip along the top maps the whole range —
 * tap or drag it to jump. Nearest focus is on the left, farthest on the right.
 *
 * Positions are shown as 0–100 (0 = nearest). The START / END markers, the band between them and a
 * dot for every frame of the stack are drawn on both the ruler and the overview.
 */
class FocusDial @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called as the user moves focus, with the new value in diopters. */
    var onChange: ((Float) -> Unit)? = null

    var maxDiopters = 10f
        set(v) {
            field = if (v > 0f) v else 1f
            invalidate()
        }

    /** Focus in diopters: 0 = farthest, [maxDiopters] = nearest. */
    var value = 0f
        private set

    var start: Float? = null
        set(v) {
            field = v
            invalidate()
        }

    var end: Float? = null
        set(v) {
            field = v
            invalidate()
        }

    var frames = 30
        set(v) {
            field = v
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val overviewHeight = 26 * dp
    private val sidePad = 14 * dp
    private val bottomArea = 26 * dp

    private val accent = context.getColor(R.color.accent)
    private val startColor = context.getColor(R.color.start)
    private val endColor = context.getColor(R.color.end)
    private val panelColor = context.getColor(R.color.panel)

    private val trackPaint = strokePaint(context.getColor(R.color.outline), 3 * dp)
    private val rangePaint = strokePaint(withAlpha(accent, 0.75f), 3 * dp)
    private val windowFill = fillPaint(0x1AFFFFFF)
    private val windowStroke = strokePaint(0x40FFFFFF, 1 * dp).apply { strokeCap = Paint.Cap.BUTT }
    private val tickPaint = strokePaint(context.getColor(R.color.text_tertiary), 1.2f * dp).apply { strokeCap = Paint.Cap.BUTT }
    private val majorTickPaint = strokePaint(context.getColor(R.color.text_secondary), 1.6f * dp).apply { strokeCap = Paint.Cap.BUTT }
    private val tickLabelPaint = textPaint(context.getColor(R.color.text_tertiary), 10 * sp, Paint.Align.CENTER)
    private val bandPaint = fillPaint(withAlpha(accent, 0.13f))
    private val dotPaint = fillPaint(accent)
    private val denseLinePaint = strokePaint(withAlpha(accent, 0.8f), 2 * dp)
    private val startPaint = strokePaint(startColor, 2 * dp).apply { strokeCap = Paint.Cap.BUTT }
    private val endPaint = strokePaint(endColor, 2 * dp).apply { strokeCap = Paint.Cap.BUTT }
    private val startFill = fillPaint(startColor)
    private val endFill = fillPaint(endColor)
    private val flagTextPaint = textPaint(context.getColor(R.color.on_accent), 10 * sp, Paint.Align.CENTER).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val needlePaint = strokePaint(accent, 2.5f * dp)
    private val needleFill = fillPaint(accent)
    private val readoutPaint = textPaint(context.getColor(R.color.text), 13 * sp, Paint.Align.CENTER).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val labelPaint = textPaint(context.getColor(R.color.text_tertiary), 10 * sp, Paint.Align.LEFT).apply {
        letterSpacing = 0.12f
    }
    private val leftFade = Paint()
    private val rightFade = Paint()
    private val rect = RectF()
    private val needlePath = Path()

    private val scroller = OverScroller(context)
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null
    private var dragMode = DragMode.NONE
    private var lastX = 0f

    private enum class DragMode { NONE, RULER, OVERVIEW }

    /** 0 = nearest (left), 1 = farthest (right). */
    private val position: Float get() = 1f - value / maxDiopters
    private fun positionOf(d: Float) = 1f - d / maxDiopters

    /** Ruler pixels per whole focus range. */
    private val pxPerRange: Float get() = (width / VISIBLE_FRACTION).coerceAtLeast(1f)

    /** Shows [d] without notifying [onChange]. Stops any fling in progress. */
    fun setValue(d: Float) {
        scroller.forceFinished(true)
        value = d.coerceIn(0f, maxDiopters)
        updateDescription()
        invalidate()
    }

    /** Focus position as shown on the dial: 0 (nearest) to 100 (farthest). */
    fun percentOf(d: Float): Float = positionOf(d) * 100f

    private fun moveTo(p: Float) {
        val clamped = p.coerceIn(0f, 1f)
        val old = position
        if (clamped == old) return
        tickHaptics(old, clamped)
        value = (1f - clamped) * maxDiopters
        updateDescription()
        invalidate()
        onChange?.invoke(value)
    }

    /** A light click every 5 %, and a firmer one when passing START or END. */
    private fun tickHaptics(from: Float, to: Float) {
        val lo = min(from, to)
        val hi = max(from, to)
        val crossedMarker = listOfNotNull(start, end).any { m -> positionOf(m).let { it > lo && it <= hi } }
        when {
            crossedMarker -> performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            floor(from / MAJOR_STEP) != floor(to / MAJOR_STEP) -> performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun updateDescription() {
        contentDescription = "Focus %.0f of 100, 0 is nearest".format(Locale.US, position * 100f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val fade = 36 * dp
        leftFade.shader = LinearGradient(0f, 0f, fade, 0f, panelColor, panelColor and 0x00FFFFFF, Shader.TileMode.CLAMP)
        rightFade.shader = LinearGradient(w - fade, 0f, w.toFloat(), 0f, panelColor and 0x00FFFFFF, panelColor, Shader.TileMode.CLAMP)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.6f
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = position
        val s = start?.let { positionOf(it) }
        val e = end?.let { positionOf(it) }

        drawOverview(canvas, w, p, s, e)

        val top = overviewHeight + 6 * dp
        val base = h - bottomArea
        val cx = w / 2f
        fun rx(q: Float) = cx + (q - p) * pxPerRange

        // Stack range band.
        if (s != null && e != null) {
            rect.set(rx(min(s, e)), top, rx(max(s, e)), base)
            canvas.drawRoundRect(rect, 6 * dp, 6 * dp, bandPaint)
        }

        // Ticks: every 0.5 %, longer every 1 %, major every 5 %, labelled every 10.
        val half = VISIBLE_FRACTION / 2f + 0.03f
        val firstK = floor((p - half) / TICK_STEP).toInt().coerceAtLeast(0)
        val lastK = ceil((p + half) / TICK_STEP).toInt().coerceAtMost(TICKS)
        for (k in firstK..lastK) {
            val x = rx(k * TICK_STEP)
            val major = k % 10 == 0
            val length = when {
                major -> 18 * dp
                k % 2 == 0 -> 11 * dp
                else -> 6 * dp
            }
            canvas.drawLine(x, base - length, x, base, if (major) majorTickPaint else tickPaint)
            if (k % 20 == 0) canvas.drawText("${k / 2}", x, base - length - 5 * dp, tickLabelPaint)
        }

        // A dot under the ruler for every photo in the stack.
        if (s != null && e != null && frames >= 2) {
            val y = base + 6 * dp
            val spacing = abs(e - s) / (frames - 1) * pxPerRange
            if (spacing >= 5 * dp) {
                for (i in 0 until frames) {
                    val x = rx(s + (e - s) * i / (frames - 1))
                    if (x > -8 * dp && x < w + 8 * dp) canvas.drawCircle(x, y, 2.2f * dp, dotPaint)
                }
            } else {
                canvas.drawLine(rx(min(s, e)), y, rx(max(s, e)), y, denseLinePaint)
            }
        }

        s?.let { drawMarker(canvas, rx(it), top, base, w, "S", startPaint, startFill) }
        e?.let { drawMarker(canvas, rx(it), top, base, w, "E", endPaint, endFill) }

        // Fade the ruler out at both edges.
        canvas.drawRect(0f, top - 16 * dp, 36 * dp, base + 10 * dp, leftFade)
        canvas.drawRect(w - 36 * dp, top - 16 * dp, w, base + 10 * dp, rightFade)

        // Needle.
        canvas.drawLine(cx, top + 4 * dp, cx, base + 2 * dp, needlePaint)
        needlePath.reset()
        needlePath.moveTo(cx - 6 * dp, top - 2 * dp)
        needlePath.lineTo(cx + 6 * dp, top - 2 * dp)
        needlePath.lineTo(cx, top + 6 * dp)
        needlePath.close()
        canvas.drawPath(needlePath, needleFill)

        // Readout and end labels.
        val textY = h - 3 * dp
        canvas.drawText("%.1f".format(Locale.US, p * 100f), cx, textY, readoutPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("NEAR", sidePad, textY, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("FAR", w - sidePad, textY, labelPaint)
    }

    private fun drawOverview(canvas: Canvas, w: Float, p: Float, s: Float?, e: Float?) {
        val y = overviewHeight / 2f
        val x0 = sidePad
        val span = w - 2 * sidePad
        fun ox(q: Float) = x0 + q * span

        canvas.drawLine(x0, y, x0 + span, y, trackPaint)
        if (s != null && e != null) canvas.drawLine(ox(min(s, e)), y, ox(max(s, e)), y, rangePaint)

        // The part of the range the ruler is showing.
        rect.set(ox(p - VISIBLE_FRACTION / 2), y - 8 * dp, ox(p + VISIBLE_FRACTION / 2), y + 8 * dp)
        canvas.drawRoundRect(rect, 5 * dp, 5 * dp, windowFill)
        canvas.drawRoundRect(rect, 5 * dp, 5 * dp, windowStroke)

        s?.let { canvas.drawCircle(ox(it), y, 4 * dp, startFill) }
        e?.let { canvas.drawCircle(ox(it), y, 4 * dp, endFill) }
        canvas.drawLine(ox(p), y - 6 * dp, ox(p), y + 6 * dp, needlePaint)
    }

    /** A marker line with a lettered flag, or an arrow at the edge when it's off the ruler. */
    private fun drawMarker(canvas: Canvas, x: Float, top: Float, base: Float, w: Float, letter: String, line: Paint, fill: Paint) {
        val flagW = 16 * dp
        val flagH = 14 * dp
        val inView = x >= 0f && x <= w
        val fx = when {
            inView -> x
            x < 0f -> sidePad + flagW / 2
            else -> w - sidePad - flagW / 2
        }
        if (inView) canvas.drawLine(x, top + flagH, x, base, line)
        rect.set(fx - flagW / 2, top, fx + flagW / 2, top + flagH)
        if (!inView) rect.inset(-4 * dp, 0f) // room for the arrow
        canvas.drawRoundRect(rect, 4 * dp, 4 * dp, fill)
        val label = when {
            inView -> letter
            x < 0f -> "‹$letter"
            else -> "$letter›"
        }
        canvas.drawText(label, fx, top + flagH - 3.5f * dp, flagTextPaint)
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                dragMode = if (event.y < overviewHeight + 4 * dp) DragMode.OVERVIEW else DragMode.RULER
                if (dragMode == DragMode.OVERVIEW) moveTo(overviewPosition(event.x))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                when (dragMode) {
                    DragMode.OVERVIEW -> moveTo(overviewPosition(event.x))
                    DragMode.RULER -> {
                        val dx = event.x - lastX
                        lastX = event.x
                        moveTo(position - dx / pxPerRange)
                    }
                    DragMode.NONE -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragMode == DragMode.RULER) {
                    velocityTracker?.let { vt ->
                        vt.addMovement(event)
                        vt.computeCurrentVelocity(1000, maxFling.toFloat())
                        val vx = vt.xVelocity
                        if (abs(vx) > minFling) {
                            val range = pxPerRange.roundToInt()
                            scroller.fling((position * range).roundToInt(), 0, (-vx).roundToInt(), 0, 0, range, 0, 0)
                            postInvalidateOnAnimation()
                        }
                    }
                }
                endDrag()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                endDrag()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun endDrag() {
        dragMode = DragMode.NONE
        velocityTracker?.recycle()
        velocityTracker = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            moveTo(scroller.currX / pxPerRange)
            postInvalidateOnAnimation()
        }
    }

    private fun overviewPosition(x: Float): Float = ((x - sidePad) / (width - 2 * sidePad)).coerceIn(0f, 1f)

    // ---------------------------------------------------------------- paints

    private fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun textPaint(color: Int, size: Float, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = align
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        ((alpha * 255).roundToInt() shl 24) or (color and 0x00FFFFFF)

    private companion object {
        /** Share of the whole focus range visible across the ruler. */
        const val VISIBLE_FRACTION = 0.2f
        const val TICK_STEP = 0.005f
        const val TICKS = 200
        const val MAJOR_STEP = 0.05f
    }
}
