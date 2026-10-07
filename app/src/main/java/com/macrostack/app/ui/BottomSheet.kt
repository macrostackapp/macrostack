package com.macrostack.app.ui

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.macrostack.app.R

/** A panel that slides up from the bottom of the screen, like the sheets in the stock camera app. */
class BottomSheet(activity: Activity, content: View, scrollable: Boolean = false) {

    private val dialog = Dialog(activity, R.style.Theme_MacroStack_Sheet)

    init {
        val frame = LayoutInflater.from(activity).inflate(R.layout.sheet_frame, null) as ViewGroup
        val holder = frame.findViewById<FrameLayout>(R.id.sheetContent)
        if (scrollable) {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.8f).toInt()
            holder.addView(MaxHeightScrollView(activity, maxHeight).apply { addView(content) })
        } else {
            holder.addView(content)
        }
        val basePadding = frame.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            v.updatePadding(bottom = basePadding + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        dialog.setContentView(frame)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(R.style.SheetAnimation)
        }
    }

    fun setOnDismiss(action: () -> Unit): BottomSheet {
        dialog.setOnDismissListener { action() }
        return this
    }

    fun show(): BottomSheet {
        dialog.show()
        return this
    }

    fun dismiss() = dialog.dismiss()

    val isShowing: Boolean get() = dialog.isShowing

    /** A ScrollView that stops growing at [maxHeight], so tall sheets scroll instead of filling the screen. */
    private class MaxHeightScrollView(activity: Activity, private val maxHeight: Int) : ScrollView(activity) {
        init {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }
}
