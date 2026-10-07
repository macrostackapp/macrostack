package com.macrostack.app.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import com.macrostack.app.R

/** A row of mutually exclusive options — clearer than a drop-down for two to five choices. */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called when the user picks an option. */
    var onSelected: ((Int) -> Unit)? = null

    var selectedIndex = 0
        private set

    init {
        orientation = HORIZONTAL
        setBackgroundResource(R.drawable.bg_segmented)
        val pad = (3 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    fun setOptions(labels: List<String>, selected: Int) {
        removeAllViews()
        labels.forEachIndexed { index, label ->
            val item = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(context.getColorStateList(R.color.segment_text))
                setBackgroundResource(R.drawable.bg_segment_item)
                maxLines = 1
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (selectedIndex != index) {
                        select(index)
                        onSelected?.invoke(index)
                    }
                }
            }
            addView(item, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        select(selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
    }

    fun select(index: Int) {
        selectedIndex = index
        children.forEachIndexed { i, child -> child.isSelected = i == index }
    }
}
