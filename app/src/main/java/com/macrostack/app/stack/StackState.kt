package com.macrostack.app.stack

import android.net.Uri

sealed interface StackState {
    data object Idle : StackState
    data class Countdown(val seconds: Int) : StackState
    data object Preparing : StackState

    /** [focus] is the focus distance of the latest photo, for moving the focus bar along. */
    data class Shooting(val frame: Int, val total: Int, val focus: Float?) : StackState
    data class Finishing(val total: Int) : StackState
    data class Finished(val result: StackResult) : StackState
    data class Failed(val message: String) : StackState
}

val StackState.isBusy: Boolean
    get() = this is StackState.Countdown || this is StackState.Preparing ||
        this is StackState.Shooting || this is StackState.Finishing

data class StackResult(
    /** Unique per stack, so the UI reacts to each result exactly once. */
    val id: Long,
    /** e.g. "Stack_20261004_153012". */
    val folderName: String,
    val folderPath: String,
    val total: Int,
    val jpeg: Boolean,
    val raw: Boolean,
    val savedJpeg: Int,
    val savedRaw: Int,
    /** Time the camera spent shooting, first photo to last. */
    val shootSeconds: Float,
    /** Shooting plus waiting for the last files to be written. */
    val totalSeconds: Float,
    val stopped: Boolean,
    val failedFrames: Int,
    /** Frames where the camera reported the lens still moving during the exposure. */
    val movingFrames: Int,
    /** The phone changed which physical lens it was using part-way through the stack. */
    val lensSwitched: Boolean,
    val fast: Boolean,
    /** The first save error, for diagnosis. */
    val firstError: String?,
    /** A saved frame from the middle of the stack, for the thumbnail and "View". */
    val previewUri: Uri?,
    val previewMime: String,
) {
    /** Frames saved in every requested format. */
    val saved: Int
        get() = when {
            jpeg && raw -> minOf(savedJpeg, savedRaw)
            raw -> savedRaw
            else -> savedJpeg
        }
}
