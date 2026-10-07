package com.macrostack.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.macrostack.app.R

/** The how-to, shown on first launch and from Settings. */
object HelpSheet {

    fun show(activity: Activity, onCameraInfo: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_help, null)
        val steps = view.findViewById<LinearLayout>(R.id.helpSteps)
        val density = activity.resources.displayMetrics.density
        activity.resources.getStringArray(R.array.help_steps).forEachIndexed { index, text ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (10 * density).toInt(), 0, 0)
            }
            val number = TextView(activity).apply {
                this.text = "${index + 1}"
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(activity.getColor(R.color.accent))
                setBackgroundResource(R.drawable.bg_step_number)
            }
            val size = (26 * density).toInt()
            row.addView(number, LinearLayout.LayoutParams(size, size))
            val body = TextView(activity).apply {
                this.text = text
                textSize = 14f
                setLineSpacing(2 * density, 1f)
                setTextColor(activity.getColor(R.color.text))
            }
            row.addView(
                body,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * density).toInt()
                    topMargin = (3 * density).toInt()
                },
            )
            steps.addView(row)
        }

        lateinit var sheet: BottomSheet
        view.findViewById<View>(R.id.helpDone).setOnClickListener { sheet.dismiss() }
        view.findViewById<View>(R.id.helpCameraInfo).setOnClickListener {
            sheet.dismiss()
            onCameraInfo()
        }
        sheet = BottomSheet(activity, view, scrollable = true).show()
    }
}

/** The full capability report, for diagnosing a phone remotely. */
object CameraInfoSheet {

    fun show(activity: Activity, text: String) {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_info, null)
        view.findViewById<TextView>(R.id.infoText).text = text
        lateinit var sheet: BottomSheet
        view.findViewById<View>(R.id.infoClose).setOnClickListener { sheet.dismiss() }
        view.findViewById<View>(R.id.infoCopy).setOnClickListener {
            activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("MacroStack camera info", text))
            // Android 13+ shows its own confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        sheet = BottomSheet(activity, view, scrollable = true).show()
    }
}
