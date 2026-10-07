package com.macrostack.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.macrostack.app.R
import kotlin.math.min

/** A thin progress ring drawn around the last-stack thumbnail while the phone is stacking. */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.accent)
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f - 2 * dp
        oval.set(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        canvas.drawOval(oval, track)
        canvas.drawArc(oval, -90f, 360f * progress, false, arc)
    }
}
