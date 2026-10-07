package com.macrostack.app.stacking

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.macrostack.app.BuildConfig
import com.macrostack.app.R
import com.macrostack.app.fusion.Parallel
import com.macrostack.app.fusion.RgbImage
import com.macrostack.app.fusion.StackFusion
import com.macrostack.app.stack.StackSaver
import com.macrostack.app.stacking.StackFiles.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stacks finished focus stacks on the phone, one at a time, in the background. Lives as long as the
 * app process, so stacking carries on while the user keeps shooting; it pauses itself while the
 * camera is busy.
 */
class FusionManager(context: Context) {

    data class Source(
        val name: String,
        val relativePath: String,
        val method: StackFusion.Method = StackFusion.Method.DEPTH_MAP,
    )

    sealed interface State {
        data object Idle : State

        data class Running(
            val source: Source,
            val phase: StackFusion.Phase,
            val done: Int,
            val total: Int,
            val preview: Bitmap?,
            val queued: Int,
        ) : State {
            /**
             * Overall progress, 0–1: aligning is quick; then both methods sweep the stack twice (a
             * depth map first, then the merge), the pyramid's merge being the slower one.
             */
            val fraction: Float
                get() {
                    val f = done.toFloat() / total
                    val measuring = if (source.method == StackFusion.Method.DEPTH_MAP) 0.43f else 0.3f
                    return when (phase) {
                        StackFusion.Phase.ALIGNING -> 0.1f * f
                        StackFusion.Phase.MEASURING -> 0.1f + measuring * f
                        StackFusion.Phase.STACKING -> 0.1f + measuring + (0.86f - measuring) * f
                        StackFusion.Phase.FINISHING -> 0.97f
                    }
                }
        }

        data class Done(
            val id: Long,
            val source: Source,
            val uri: Uri,
            val width: Int,
            val height: Int,
            val frames: Int,
            val seconds: Float,
            val unalignedPairs: Int,
            val reducedSize: Boolean,
        ) : State

        data class Failed(val id: Long, val source: Source, val message: String, val cancelled: Boolean) : State
    }

    private val appContext = context.applicationContext
    private val saver = StackSaver(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<Source>()
    private val cancelled = AtomicBoolean(false)
    private var running = false
    private var current: Source? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _active = MutableStateFlow(false)

    /** True from the moment a stack starts until the queue is empty. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** While true (the camera is shooting), stacking waits between frames to leave it the CPU. */
    @Volatile
    var paused = false

    val isRunning: Boolean @Synchronized get() = running

    /** Adds a stack to the queue; starts right away if nothing else is being stacked. */
    @Synchronized
    fun enqueue(source: Source) {
        if (source !in queue && !(running && source == current && !cancelled.get())) queue.addLast(source)
        if (!running) startNext()
    }

    /** Stops the current stack and drops the queue. */
    @Synchronized
    fun cancel() {
        queue.clear()
        cancelled.set(true)
    }

    @Synchronized
    private fun startNext() {
        val source = queue.removeFirstOrNull()
        current = source
        if (source == null) {
            running = false
            _active.value = false
            return
        }
        running = true
        if (!_active.value) {
            _active.value = true
            StackingService.start(appContext) // keeps the process alive if the user leaves the app
        }
        cancelled.set(false)
        scope.launch {
            _state.value = runOne(source)
            startNext()
        }
    }

    @Synchronized
    private fun queuedCount() = queue.size

    private fun runOne(source: Source): State {
        val id = SystemClock.elapsedRealtimeNanos()
        val started = SystemClock.elapsedRealtime()
        val files = try {
            StackFiles.query(appContext, source.relativePath)
        } catch (e: Exception) {
            return State.Failed(id, source, "Couldn't read the stack: ${e.message}", cancelled = false)
        }
        if (files.size < 2) return State.Failed(id, source, "A stack needs at least 2 photos.", cancelled = false)

        var preview: Bitmap? = null
        var current = State.Running(source, StackFusion.Phase.ALIGNING, 0, files.size, null, queuedCount())
        _state.value = current
        val listener = object : StackFusion.Listener {
            override fun onProgress(phase: StackFusion.Phase, done: Int, total: Int) {
                while (paused && !cancelled.get()) Thread.sleep(PAUSE_POLL_MS)
                current = State.Running(source, phase, done, total, preview, queuedCount())
                _state.value = current
            }

            override fun onPreview(preview: RgbImage) {
                val bitmap = preview.toBitmap()
                setPreview(bitmap)
            }

            private fun setPreview(bitmap: Bitmap) {
                preview = bitmap
                current = current.copy(preview = bitmap)
                _state.value = current
            }

            override val isCancelled: Boolean get() = cancelled.get()
        }

        val frames = files.map { StackFiles.Frame(appContext.contentResolver, it.uri) }
        return try {
            val result = fuseWithMemoryFallback(frames, listener, source.method)
            val bitmap = result.image.toBitmap()
            val uri = try {
                saver.saveStacked(source.name, bitmap, exifFor(files, source))
            } finally {
                bitmap.recycle()
            }
            State.Done(
                id = id,
                source = source,
                uri = uri,
                width = result.image.width,
                height = result.image.height,
                frames = files.size,
                seconds = (SystemClock.elapsedRealtime() - started) / 1000f,
                unalignedPairs = result.unalignedPairs,
                reducedSize = result.sampleSize > 1,
            )
        } catch (e: StackFusion.CancelledException) {
            State.Failed(id, source, "Stacking stopped.", cancelled = true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory stacking ${source.name}", e)
            State.Failed(id, source, "Not enough memory to stack these photos.", cancelled = false)
        } catch (e: Exception) {
            Log.e(TAG, "Stacking ${source.name} failed", e)
            State.Failed(id, source, e.message ?: e.javaClass.simpleName, cancelled = false)
        }
    }

    /**
     * EXIF for the result: the camera details of the middle frame (date, lens, exposure), plus what
     * made it. Not its orientation: frames are decoded upright, so the result already is.
     */
    private fun exifFor(files: List<StackFiles.FrameFile>, source: Source): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        try {
            appContext.contentResolver.openInputStream(files[files.size / 2].uri)?.use { input ->
                val exif = ExifInterface(input)
                for (tag in COPIED_EXIF_TAGS) exif.getAttribute(tag)?.let { tags[tag] = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read EXIF from ${source.name}", e)
        }
        val method = appContext.getString(
            if (source.method == StackFusion.Method.DEPTH_MAP) R.string.method_depth_map else R.string.method_pyramid
        )
        tags[ExifInterface.TAG_ORIENTATION] = ExifInterface.ORIENTATION_NORMAL.toString()
        tags[ExifInterface.TAG_SOFTWARE] = "MacroStack ${BuildConfig.VERSION_NAME}"
        tags[ExifInterface.TAG_IMAGE_DESCRIPTION] = "Focus stack of ${files.size} photos ($method)"
        return tags
    }

    /** Full resolution if it fits; if memory runs out anyway, tries again at a quarter of the pixels. */
    private fun fuseWithMemoryFallback(
        frames: List<StackFusion.Frame>,
        listener: StackFusion.Listener,
        method: StackFusion.Method,
    ): StackFusion.Result {
        Parallel().use { parallel ->
            val prefetch = Executors.newSingleThreadExecutor()
            try {
                val fusion = StackFusion(parallel, prefetch)
                return try {
                    fusion.run(frames, listener, memoryBudget(), method)
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Out of memory at full size; retrying smaller", e)
                    System.gc()
                    fusion.run(frames, listener, memoryBudget() / 4, method)
                }
            } finally {
                prefetch.shutdownNow()
            }
        }
    }

    private fun memoryBudget(): Long {
        // Photos from the stack just shot may still be waiting to be collected; don't count them.
        System.gc()
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return (rt.maxMemory() - used - HEADROOM_BYTES).coerceAtLeast(0)
    }

    private companion object {
        const val TAG = "FusionManager"
        const val PAUSE_POLL_MS = 100L
        const val HEADROOM_BYTES = 80L * 1024 * 1024

        val COPIED_EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
        )
    }
}
