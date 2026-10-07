package com.macrostack.app.ui

import android.graphics.ImageDecoder
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.macrostack.app.R
import com.macrostack.app.fusion.StackFusion
import com.macrostack.app.stacking.FusionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/** Watch the phone merge a stack, live, then open the result. */
class StackingSheet(
    private val activity: ComponentActivity,
    private val manager: FusionManager,
    /** The most recently shot stack, offered for (re)stacking when nothing is running. */
    private val lastStack: FusionManager.Source?,
    private val onOpenResult: (Uri) -> Unit,
    private val onOpenPhotos: () -> Unit,
    /** Stacks [source] (again), possibly with a different method. */
    private val onStack: (FusionManager.Source) -> Unit,
) {
    private lateinit var sheet: BottomSheet
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var image: ImageView
    private lateinit var placeholder: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var primary: TextView
    private lateinit var secondary: TextView
    private lateinit var gap: View
    private var shownResult: Uri? = null

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_stacking, null)
        title = view.findViewById(R.id.stackingTitle)
        subtitle = view.findViewById(R.id.stackingSubtitle)
        image = view.findViewById(R.id.stackingImage)
        placeholder = view.findViewById(R.id.stackingPlaceholder)
        progress = view.findViewById(R.id.stackingProgress)
        status = view.findViewById(R.id.stackingStatus)
        primary = view.findViewById(R.id.stackingPrimary)
        secondary = view.findViewById(R.id.stackingSecondary)
        gap = view.findViewById(R.id.stackingButtonGap)
        view.findViewById<View>(R.id.stackingImageFrame).updateLayoutParams {
            height = (activity.resources.displayMetrics.heightPixels * 0.42f).toInt()
        }

        val job = activity.lifecycleScope.launch { manager.state.collect { render(it) } }
        sheet = BottomSheet(activity, view).setOnDismiss { job.cancel() }.show()
    }

    private fun render(state: FusionManager.State) {
        when (state) {
            FusionManager.State.Idle -> {
                title.setText(R.string.stacking_title_idle)
                subtitle.text = ""
                progress.isVisible = false
                showImage(null)
                if (lastStack == null) {
                    status.setText(R.string.stacking_idle)
                    buttons(primaryText = R.string.close, primaryAction = { sheet.dismiss() })
                } else {
                    status.text = activity.getString(R.string.stack_last_prompt, lastStack.name)
                    buttons(
                        primaryText = R.string.stack_it,
                        primaryAction = { onStack(lastStack) },
                        secondaryText = R.string.open_photos,
                        secondaryAction = { onOpenPhotos() },
                    )
                }
            }

            is FusionManager.State.Running -> {
                title.setText(R.string.stacking_title_running)
                subtitle.text = buildString {
                    append(activity.getString(R.string.stacking_keep_shooting, state.total))
                    if (state.queued > 0) append(" · ").append(activity.getString(R.string.stacking_queued, state.queued))
                }
                status.text = when (state.phase) {
                    StackFusion.Phase.ALIGNING -> activity.getString(R.string.stacking_aligning, state.done, state.total)
                    StackFusion.Phase.MEASURING -> activity.getString(R.string.stacking_measuring, state.done, state.total)
                    StackFusion.Phase.STACKING -> activity.getString(R.string.stacking_merging, state.done, state.total)
                    StackFusion.Phase.FINISHING -> activity.getString(R.string.saving)
                }
                progress.isVisible = true
                progress.progress = (state.fraction * 1000).toInt()
                state.preview?.let { showImage(it) } ?: showImage(null)
                shownResult = null
                buttons(
                    primaryText = R.string.stacking_hide,
                    primaryAction = { sheet.dismiss() },
                    secondaryText = R.string.stacking_stop,
                    secondaryAction = { manager.cancel() },
                )
            }

            is FusionManager.State.Done -> {
                title.setText(R.string.stacking_title_done)
                subtitle.text = "${state.source.name} · ${methodName(state.source.method)}"
                progress.isVisible = false
                status.text = buildString {
                    append(
                        activity.getString(
                            R.string.stacking_done_summary,
                            state.frames,
                            "%.0f".format(Locale.US, state.seconds),
                            "%.1f".format(Locale.US, state.width * state.height / 1e6),
                        )
                    )
                    if (state.unalignedPairs > 0) append("\n").append(activity.getString(R.string.stacking_unaligned, state.unalignedPairs))
                    if (state.reducedSize) append("\n").append(activity.getString(R.string.stacking_reduced))
                }
                loadResult(state.uri)
                val other = if (state.source.method == StackFusion.Method.DEPTH_MAP) StackFusion.Method.PYRAMID else StackFusion.Method.DEPTH_MAP
                buttons(
                    primaryText = R.string.open_in_gallery,
                    primaryAction = { onOpenResult(state.uri) },
                    secondaryLabel = activity.getString(R.string.redo_as, methodName(other)),
                    secondaryAction = { onStack(state.source.copy(method = other)) },
                )
            }

            is FusionManager.State.Failed -> {
                title.setText(if (state.cancelled) R.string.stacking_title_stopped else R.string.stacking_title_failed)
                subtitle.text = state.source.name
                status.text = state.message
                progress.isVisible = false
                buttons(
                    primaryText = R.string.stacking_retry,
                    primaryAction = { onStack(state.source) },
                    secondaryText = R.string.close,
                    secondaryAction = { sheet.dismiss() },
                )
            }
        }
    }

    private fun methodName(method: StackFusion.Method) = activity.getString(
        if (method == StackFusion.Method.DEPTH_MAP) R.string.method_depth_map else R.string.method_pyramid
    )

    private fun showImage(bitmap: android.graphics.Bitmap?) {
        if (bitmap != null) image.setImageBitmap(bitmap) else if (shownResult == null) image.setImageDrawable(null)
        placeholder.isVisible = bitmap == null && shownResult == null
    }

    /** Shows the saved result, decoded at a size that suits the screen. */
    private fun loadResult(uri: Uri) {
        if (shownResult == uri) return
        shownResult = uri
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { decoder, info, _ ->
                        val longest = max(info.size.width, info.size.height)
                        if (longest > MAX_PREVIEW) {
                            val f = MAX_PREVIEW.toFloat() / longest
                            decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                        }
                    }
                }.getOrNull()
            }
            if (bitmap != null && shownResult == uri) {
                image.setImageBitmap(bitmap)
                placeholder.isVisible = false
            }
        }
    }

    private fun buttons(
        primaryText: Int,
        primaryAction: () -> Unit,
        secondaryText: Int? = null,
        secondaryAction: (() -> Unit)? = null,
        secondaryLabel: String? = secondaryText?.let { activity.getString(it) },
    ) {
        primary.setText(primaryText)
        primary.setOnClickListener { primaryAction() }
        secondary.isVisible = secondaryLabel != null
        gap.isVisible = secondaryLabel != null
        if (secondaryLabel != null) {
            secondary.text = secondaryLabel
            secondary.setOnClickListener { secondaryAction?.invoke() }
        }
    }

    private companion object {
        const val MAX_PREVIEW = 1600
    }
}
