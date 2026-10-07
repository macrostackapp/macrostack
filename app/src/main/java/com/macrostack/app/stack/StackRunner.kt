package com.macrostack.app.stack

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.macrostack.app.camera.CameraController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Shoots a focus stack.
 *
 * Fast mode works like a dedicated camera's focus bracketing: the whole stack is handed to the
 * camera as one burst, each photo carrying its own focus distance, so the sensor shoots
 * back-to-back. Precise mode moves the lens, waits until the camera reports it parked, shoots one
 * photo, and repeats — slower, but it never relies on the phone's per-frame timing.
 *
 * Each frame becomes a JPEG and/or a DNG, depending on what the camera session was opened with.
 * Files are written in the background while shooting continues.
 */
class StackRunner(
    private val camera: CameraController,
    private val saver: StackSaver,
    private val scope: CoroutineScope,
) {

    data class Options(
        val fast: Boolean,
        val startDelaySec: Int,
        /** Fast mode: extra sensor frames before each photo for the focus motor to arrive. */
        val settleFrames: Int,
        /** Precise mode: extra wait after the lens reports parked. */
        val settleMs: Int,
        val jpegQuality: Int,
        /** Clockwise rotation (0/90/180/270) that makes the photo upright. */
        val orientation: Int,
    )

    private val _state = MutableStateFlow<StackState>(StackState.Idle)
    val state: StateFlow<StackState> = _state.asStateFlow()

    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    fun start(plan: StackPlan, options: Options) {
        if (isRunning) return
        job = scope.launch { run(plan, options) }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Bookkeeping for one stack, shared by the camera, JPEG, RAW and writer threads. */
    private inner class Run(
        val distances: List<Float>,
        private val folder: StackSaver.Folder,
        val jpeg: Boolean,
        val raw: Boolean,
        jpegWidth: Int,
        jpegHeight: Int,
        private val exifOrientation: Int,
    ) {
        val total = distances.size
        /** Files each frame produces. */
        val outputs = (if (jpeg) 1 else 0) + (if (raw) 1 else 0)

        val captured = AtomicInteger()
        val savedJpeg = AtomicInteger()
        val savedRaw = AtomicInteger()
        val saveErrors = AtomicInteger()
        val lost = AtomicInteger()
        val moving = AtomicInteger()
        val failed = AtomicInteger()
        val firstError = AtomicReference<String?>(null)

        /** Files finished — saved, failed to save, or dropped by the camera. */
        val completed = MutableStateFlow(0)
        val activeLenses: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Saved files by frame, in the format shown as the stack's thumbnail (JPEG if any). */
        val previews = ConcurrentHashMap<Int, Uri>()
        val previewMime: String = if (jpeg) StackSaver.JPEG_MIME else StackSaver.DNG_MIME

        /** The saved frame nearest the middle of the stack — usually the most representative. */
        fun middlePreview(): Uri? = previews.keys.minByOrNull { abs(it - total / 2) }?.let { previews[it] }

        private val jpegMatcher = FrameMatcher()
        private val startedAt = ConcurrentHashMap<Int, Long>()

        private val jpegWriter = FrameWriter(FrameWriter.capacityFor(jpegWidth, jpegHeight)) { error ->
            onFileDone(error, savedJpeg)
        }

        // RAW frames are the camera's own buffers, so at most MAX_RAW_IMAGES exist; the queue never fills.
        private val rawWriter = FrameWriter(CameraController.MAX_RAW_IMAGES + 2, threads = 2) { error ->
            onFileDone(error, savedRaw)
        }

        private val rawPairer = TimestampPairer<CameraController.RawFrame, Pair<Int, TotalCaptureResult>>(
            onPair = { frame, (index, result) -> submitDng(frame, index, result) },
            onDiscard = { frame -> frame.close() },
        )

        private fun onFileDone(error: Exception?, savedCounter: AtomicInteger) {
            if (error == null) {
                savedCounter.incrementAndGet()
            } else {
                saveErrors.incrementAndGet()
                firstError.compareAndSet(null, error.message ?: error.javaClass.simpleName)
            }
            completed.update { it + 1 }
        }

        fun filesExpected() = captured.get() * outputs

        fun submitted(index: Int) {
            if (jpeg) jpegMatcher.submitted(index)
        }

        fun started(index: Int, timestamp: Long) {
            if (jpeg) jpegMatcher.started(index, timestamp)
            startedAt[index] = timestamp
        }

        fun failed(index: Int) {
            if (jpeg) jpegMatcher.failed(index)
            startedAt.remove(index)?.let { rawPairer.drop(it) }
        }

        fun bufferLost(index: Int, rawOutput: Boolean) {
            val counted = if (rawOutput) true else jpegMatcher.lost(index)
            if (counted) {
                lost.incrementAndGet()
                completed.update { it + 1 }
            }
        }

        fun onJpeg(timestamp: Long, bytes: ByteArray) {
            val index = jpegMatcher.onJpeg(timestamp) ?: return
            jpegWriter.submit(index) { previews[index] = saver.saveJpeg(folder, index, bytes) }
        }

        fun onRaw(frame: CameraController.RawFrame) = rawPairer.addFirst(frame.timestamp, frame)

        fun recordStill(index: Int, result: TotalCaptureResult) {
            captured.incrementAndGet()
            if (camera.lensResult(result).get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING) {
                moving.incrementAndGet()
            }
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { activeLenses += it }
            if (raw) {
                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: startedAt[index]
                if (timestamp != null) rawPairer.addSecond(timestamp, index to result)
            }
            _state.value = StackState.Shooting(captured.get(), total, distances[index])
        }

        private fun submitDng(frame: CameraController.RawFrame, index: Int, result: TotalCaptureResult) {
            val sources = camera.dngSources(result)
            rawWriter.submit(index) { frame.use { writeDng(it, index, sources) } }
        }

        /** Tries each camera description until DngCreator accepts one for this image. */
        private fun writeDng(
            frame: CameraController.RawFrame,
            index: Int,
            sources: List<Pair<CameraCharacteristics, CaptureResult>>,
        ) {
            var lastError: Exception? = null
            for ((characteristics, metadata) in sources) {
                try {
                    val uri = saver.saveDng(folder, index) { out ->
                        DngCreator(characteristics, metadata).use { dng ->
                            dng.setOrientation(exifOrientation)
                            dng.writeImage(out, frame.image)
                        }
                    }
                    if (!jpeg) previews[index] = uri
                    return
                } catch (e: IllegalArgumentException) {
                    lastError = e
                } catch (e: IllegalStateException) {
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("No camera description for DNG")
        }

        /** Stops taking files, releases unpaired RAW buffers and waits for writes to finish. */
        fun finish(timeoutMs: Long) {
            rawPairer.discardAll()
            jpegWriter.finish(timeoutMs)
            rawWriter.finish(timeoutMs)
        }
    }

    private suspend fun run(plan: StackPlan, options: Options) {
        try {
            for (s in options.startDelaySec downTo 1) {
                _state.value = StackState.Countdown(s)
                delay(1000)
            }
        } catch (e: CancellationException) {
            _state.value = StackState.Idle
            throw e
        }
        shoot(plan, options)
    }

    private suspend fun shoot(plan: StackPlan, options: Options) {
        _state.value = StackState.Preparing
        val focusBefore = camera.focusDiopters
        val folder = saver.newFolder()
        val distances = plan.distances()
        val jpegSize = camera.streams?.jpeg
        val run = Run(
            distances = distances,
            folder = folder,
            jpeg = camera.shootsJpeg,
            raw = camera.shootsRaw,
            jpegWidth = jpegSize?.width ?: 4000,
            jpegHeight = jpegSize?.height ?: 3000,
            exifOrientation = exifOrientation(options.orientation),
        )
        camera.jpegListener = run::onJpeg
        camera.rawListener = run::onRaw

        var stopped = false
        var error: Exception? = null
        var shootStart = SystemClock.elapsedRealtime()
        try {
            camera.lockExposureForStack()
            // Park the lens on the first frame before the clock starts. The move from wherever it was
            // can be long, so give the focus motor extra time to stop ringing.
            camera.setFocusNow(distances.first())
            delay(FIRST_MOVE_EXTRA_MS)
            camera.awaitFocusSettled(distances.first(), SETTLE_TIMEOUT_MS)
            shootStart = SystemClock.elapsedRealtime()
            if (options.fast) shootBurst(run, options) else shootPrecise(run, options)
        } catch (e: CancellationException) {
            stopped = true
        } catch (e: Exception) {
            Log.e(TAG, "Stack failed", e)
            error = e
        }
        val shootEnd = SystemClock.elapsedRealtime()

        val wasStopped = stopped
        val failure = error
        withContext(NonCancellable) {
            _state.value = StackState.Finishing(run.total)
            withTimeoutOrNull(if (wasStopped) STOPPED_DRAIN_TIMEOUT_MS else DRAIN_TIMEOUT_MS) {
                run.completed.first { it >= run.filesExpected() }
            }
            camera.jpegListener = null
            camera.rawListener = null
            withContext(Dispatchers.IO) { run.finish(WRITER_TIMEOUT_MS) }
            camera.unlockExposure()
            camera.setFocusNow(focusBefore)

            val result = StackResult(
                id = SystemClock.elapsedRealtimeNanos(),
                folderName = folder.name,
                folderPath = folder.relativePath,
                total = run.total,
                jpeg = run.jpeg,
                raw = run.raw,
                savedJpeg = run.savedJpeg.get(),
                savedRaw = run.savedRaw.get(),
                shootSeconds = (shootEnd - shootStart) / 1000f,
                totalSeconds = (SystemClock.elapsedRealtime() - shootStart) / 1000f,
                stopped = wasStopped || failure != null,
                failedFrames = run.failed.get() + run.lost.get() + run.saveErrors.get(),
                movingFrames = run.moving.get(),
                lensSwitched = run.activeLenses.size > 1,
                fast = options.fast,
                firstError = run.firstError.get(),
                previewUri = run.middlePreview(),
                previewMime = run.previewMime,
            )
            _state.value = if (failure != null && result.savedJpeg + result.savedRaw == 0) {
                StackState.Failed(failure.message ?: failure.toString())
            } else {
                StackState.Finished(result)
            }
        }
    }

    private suspend fun shootBurst(run: Run, options: Options) {
        val dropped = ConcurrentLinkedQueue<Int>()
        run.distances.indices.forEach { run.submitted(it) }
        _state.value = StackState.Shooting(0, run.total, run.distances.first())
        camera.captureBurst(
            distances = run.distances,
            settleFrames = options.settleFrames,
            jpegOrientation = options.orientation,
            jpegQuality = options.jpegQuality,
            listener = object : CameraController.BurstListener {
                override fun onStillStarted(index: Int, timestamp: Long) = run.started(index, timestamp)
                override fun onStillCompleted(index: Int, result: TotalCaptureResult) = run.recordStill(index, result)
                override fun onStillBufferLost(index: Int, raw: Boolean) = run.bufferLost(index, raw)
                override fun onStillFailed(index: Int) {
                    run.failed(index)
                    dropped += index
                }
            },
        )
        // Re-shoot, one at a time, any frame the camera dropped during the burst.
        for (index in dropped.sorted()) {
            if (!captureOne(run, index, options)) run.failed.incrementAndGet()
        }
    }

    private suspend fun shootPrecise(run: Run, options: Options) {
        for ((index, distance) in run.distances.withIndex()) {
            _state.value = StackState.Shooting(index + 1, run.total, distance)
            if (!captureOne(run, index, options)) run.failed.incrementAndGet()
        }
    }

    /** Move, wait for the lens to park, shoot — retrying a failed capture a couple of times. */
    private suspend fun captureOne(run: Run, index: Int, options: Options): Boolean {
        val distance = run.distances[index]
        camera.setFocusNow(distance)
        if (!camera.awaitFocusSettled(distance, SETTLE_TIMEOUT_MS)) {
            Log.w(TAG, "Frame ${index + 1}: lens did not report settled in time")
        }
        if (options.settleMs > 0) delay(options.settleMs.toLong())
        // Don't let unsaved photos pile up in memory.
        withTimeoutOrNull(BACKPRESSURE_TIMEOUT_MS) {
            run.completed.first { run.filesExpected() - it < MAX_IN_FLIGHT * run.outputs }
        }
        repeat(MAX_ATTEMPTS) { attempt ->
            run.submitted(index)
            try {
                val result = camera.captureStill(
                    index = index,
                    jpegOrientation = options.orientation,
                    jpegQuality = options.jpegQuality,
                    onStarted = { timestamp -> run.started(index, timestamp) },
                    onBufferLost = { raw -> run.bufferLost(index, raw) },
                )
                run.recordStill(index, result)
                return true
            } catch (e: CameraController.CaptureFailedException) {
                run.failed(index)
                Log.w(TAG, "Frame ${index + 1} attempt ${attempt + 1} failed", e)
            }
        }
        return false
    }

    private fun exifOrientation(degrees: Int): Int = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {
        const val TAG = "StackRunner"
        const val FIRST_MOVE_EXTRA_MS = 250L
        const val SETTLE_TIMEOUT_MS = 1000L
        const val MAX_IN_FLIGHT = 3
        const val BACKPRESSURE_TIMEOUT_MS = 4000L
        const val MAX_ATTEMPTS = 3
        const val DRAIN_TIMEOUT_MS = 30_000L
        const val STOPPED_DRAIN_TIMEOUT_MS = 3_000L
        const val WRITER_TIMEOUT_MS = 60_000L
    }
}
