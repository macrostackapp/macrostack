package com.macrostack.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Coroutine-friendly Camera2 wrapper that keeps the lens under manual focus control.
 *
 * Public methods are called from the main thread. Camera callbacks run on a private camera
 * thread; JPEG and RAW frames arrive on their own threads.
 */
class CameraController(context: Context) {

    data class Exposure(
        val manual: Boolean = false,
        val evIndex: Int = 0,
        val iso: Int = 100,
        val exposureNs: Long = 16_666_667L,
    )

    class CaptureFailedException(reason: Int) : Exception("Capture failed (reason $reason)")

    /**
     * A RAW sensor image handed to the app. RAW frames are too big to copy, so the camera's own buffer
     * is passed along; [close] returns it to the camera. Only a few exist at once — holding them is
     * what slows the camera down when storage can't keep up.
     */
    class RawFrame internal constructor(val image: Image, private val release: () -> Unit) : AutoCloseable {
        val timestamp: Long = image.timestamp
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                image.close()
            } finally {
                release()
            }
        }
    }

    /** Callbacks for [captureBurst]; all are invoked on the camera thread. */
    interface BurstListener {
        fun onStillStarted(index: Int, timestamp: Long)
        fun onStillCompleted(index: Int, result: TotalCaptureResult)
        fun onStillFailed(index: Int)
        fun onStillBufferLost(index: Int, raw: Boolean)
    }

    /** Auto-exposure values copied at the start of a stack so every frame is exposed identically. */
    private data class FrozenExposure(val iso: Int, val exposureNs: Long, val postRawBoost: Int?)

    /** Marks a request that produces a stack photo. */
    private class StillTag(val index: Int)

    private val manager = context.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var jpegThread: HandlerThread? = null
    private var jpegHandler: Handler? = null
    private var rawThread: HandlerThread? = null
    private var rawHandler: Handler? = null

    /** How the current lens was reached. */
    var access: LensAccess? = null
        private set
    val caps: CameraCaps? get() = access?.caps
    var streams: CameraCaps.Streams? = null
        private set

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    /** One permit per RAW buffer the app may hold at once. */
    private var rawPermits = Semaphore(MAX_RAW_IMAGES)
    private val characteristicsCache = ConcurrentHashMap<String, CameraCharacteristics>()

    /** Lens position in diopters: 0 = infinity, [LensAccess.maxFocusDiopters] = closest. */
    var focusDiopters = 0f
        private set
    var exposure = Exposure()
    var stabilization = true

    /** Still photos use the camera's high-quality noise reduction and sharpening (its normal look). */
    var highQualityProcessing = true

    private var frozenExposure: FrozenExposure? = null
    private var aeLocked = false
    private var awbLocked = false

    private var lastPreviewUpdate = 0L
    private var previewUpdatePending = false

    /** Most recent frame metadata (actual ISO, shutter, lens state, active lens…). */
    @Volatile
    var lastResult: TotalCaptureResult? = null
        private set
    private val resultWaiters = CopyOnWriteArrayList<(TotalCaptureResult) -> Unit>()

    /** Receives each still JPEG as (sensor timestamp, bytes) on the JPEG thread. */
    @Volatile
    var jpegListener: ((Long, ByteArray) -> Unit)? = null

    /** Receives each RAW frame on the RAW thread. The receiver owns it and must close it. */
    @Volatile
    var rawListener: ((RawFrame) -> Unit)? = null

    /** Called on the main thread when the camera fails after it was opened. */
    var errorListener: ((String) -> Unit)? = null

    val isOpen: Boolean get() = session != null
    val shootsRaw: Boolean get() = rawReader != null
    val shootsJpeg: Boolean get() = jpegReader != null

    /** The physical lens a multi-lens camera is currently using, when the phone reports it. */
    val activePhysicalId: String?
        get() = lastResult?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)

    // ---------------------------------------------------------------- lifecycle

    fun startThreads() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("camera").apply { start() }.also { cameraHandler = Handler(it.looper) }
        jpegThread = HandlerThread("jpeg").apply { start() }.also { jpegHandler = Handler(it.looper) }
        rawThread = HandlerThread("raw").apply { start() }.also { rawHandler = Handler(it.looper) }
    }

    fun stopThreads() {
        cameraThread?.quitSafely()
        jpegThread?.quitSafely()
        rawThread?.quitSafely()
        cameraThread = null
        jpegThread = null
        rawThread = null
        cameraHandler = null
        jpegHandler = null
        rawHandler = null
    }

    /**
     * Opens the lens described by [target] and starts the preview into [preview] (sized for
     * [CameraCaps.Streams.preview]; owned by the caller) with the lens at [initialFocus] diopters.
     * Closes any camera that was already open. Throws if this phone doesn't allow this way of
     * reaching the lens.
     */
    suspend fun open(target: LensAccess, streams: CameraCaps.Streams, preview: Surface, initialFocus: Float) {
        close()
        startThreads()
        access = target
        this.streams = streams
        focusDiopters = clampFocus(initialFocus)
        try {
            val dev = openDevice(target.openId)
            device = dev

            previewSurface = preview

            jpegReader = streams.jpeg?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, MAX_JPEG_IMAGES).apply {
                    setOnImageAvailableListener({ onJpegAvailable(it) }, jpegHandler)
                }
            }
            rawPermits = Semaphore(MAX_RAW_IMAGES)
            rawReader = streams.raw?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, MAX_RAW_IMAGES).apply {
                    setOnImageAvailableListener({ drainRaw(it) }, rawHandler)
                }
            }

            session = createSession(dev, listOfNotNull(preview, jpegReader?.surface, rawReader?.surface))
            updatePreview()
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    fun close() {
        resultWaiters.clear()
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Closing session", e)
        }
        session = null
        try {
            device?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Closing device", e)
        }
        device = null
        jpegReader?.close()
        jpegReader = null
        rawReader?.close()
        rawReader = null
        previewSurface = null // owned by the preview renderer
        lastResult = null
        frozenExposure = null
        aeLocked = false
        awbLocked = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                val message = "The camera was taken by another app"
                if (cont.isActive) cont.resumeWithException(IllegalStateException(message))
                else reportError(message)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                val message = describeDeviceError(error)
                if (cont.isActive) cont.resumeWithException(IllegalStateException(message))
                else reportError(message)
            }
        }
        try {
            manager.openCamera(id, callback, cameraHandler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    private suspend fun createSession(dev: CameraDevice, surfaces: List<Surface>): CameraCaptureSession {
        val physicalId = access?.physicalId
        val outputs = surfaces.map { surface ->
            OutputConfiguration(surface).apply { if (physicalId != null) setPhysicalCameraId(physicalId) }
        }
        return suspendCancellableCoroutine { cont ->
            val handler = cameraHandler
            val executor = Executor { task -> if (handler != null) handler.post(task) else task.run() }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("the camera rejected this stream setup"))
                        }
                    }
                },
            )
            if (physicalId != null && !isSupported(dev, config)) {
                cont.resumeWithException(
                    IllegalStateException("this phone doesn't let apps stream from lens $physicalId")
                )
                return@suspendCancellableCoroutine
            }
            try {
                dev.createCaptureSession(config)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isSupported(dev: CameraDevice, config: SessionConfiguration): Boolean = try {
        dev.isSessionConfigurationSupported(config)
    } catch (e: UnsupportedOperationException) {
        true // the phone can't answer the question; just try it
    } catch (e: Exception) {
        Log.w(TAG, "Session support query failed", e)
        false
    }

    private fun reportError(message: String) {
        mainHandler.post {
            close()
            errorListener?.invoke(message)
        }
    }

    // ---------------------------------------------------------------- preview & controls

    /** Moves the lens. Preview updates are rate-limited so dragging the focus bar stays smooth. */
    fun setFocus(diopters: Float) {
        focusDiopters = clampFocus(diopters)
        requestPreviewUpdate()
    }

    /** Moves the lens immediately (used while shooting a stack). */
    fun setFocusNow(diopters: Float) {
        focusDiopters = clampFocus(diopters)
        updatePreview()
    }

    private fun clampFocus(d: Float): Float {
        val max = access?.maxFocusDiopters ?: return d
        return if (max > 0f) d.coerceIn(0f, max) else d
    }

    fun requestPreviewUpdate() {
        val wait = PREVIEW_UPDATE_INTERVAL_MS - (SystemClock.uptimeMillis() - lastPreviewUpdate)
        if (wait <= 0) {
            updatePreview()
        } else if (!previewUpdatePending) {
            previewUpdatePending = true
            mainHandler.postDelayed({
                previewUpdatePending = false
                updatePreview()
            }, wait)
        }
    }

    fun updatePreview() {
        lastPreviewUpdate = SystemClock.uptimeMillis()
        val dev = device ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        try {
            val request = newRequest(dev, CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applyControls(focusDiopters)
                honestPreview()
            }.build()
            s.setRepeatingRequest(request, repeatingCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Preview update failed", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Preview update on closed session", e)
        }
    }

    private fun newRequest(dev: CameraDevice, template: Int): CaptureRequest.Builder {
        val physicalId = access?.physicalId
        return if (physicalId != null) dev.createCaptureRequest(template, setOf(physicalId))
        else dev.createCaptureRequest(template)
    }

    /** Sets a control, and also on the physical lens when streaming from one of a multi-lens camera. */
    private fun <T : Any> CaptureRequest.Builder.put(key: CaptureRequest.Key<T>, value: T) {
        set(key, value)
        val a = access ?: return
        val physicalId = a.physicalId ?: return
        if (key in (a.logical ?: return).physicalRequestKeys) {
            try {
                setPhysicalCameraKey(key, value, physicalId)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Lens $physicalId rejected $key", e)
            }
        }
    }

    /** Settings shared by preview and still requests, so what you see is what gets shot. */
    private fun CaptureRequest.Builder.applyControls(focus: Float) {
        val a = access ?: return
        val caps = a.caps
        put(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (a.supportsManualFocus) {
            put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            put(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
        val zoom = a.zoomRatio
        if (zoom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        }

        val frozen = frozenExposure
        val e = exposure
        when {
            e.manual && caps.supportsManualExposure -> {
                put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                put(CaptureRequest.SENSOR_SENSITIVITY, e.iso)
                put(CaptureRequest.SENSOR_EXPOSURE_TIME, e.exposureNs)
                // Asking for frame duration = exposure lets the camera run as fast as the streams allow.
                put(CaptureRequest.SENSOR_FRAME_DURATION, e.exposureNs)
            }

            frozen != null -> {
                put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                put(CaptureRequest.SENSOR_SENSITIVITY, frozen.iso)
                put(CaptureRequest.SENSOR_EXPOSURE_TIME, frozen.exposureNs)
                put(CaptureRequest.SENSOR_FRAME_DURATION, frozen.exposureNs)
                if (frozen.postRawBoost != null && caps.supportsPostRawBoost) {
                    put(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, frozen.postRawBoost)
                }
            }

            else -> {
                put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                put(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    e.evIndex.coerceIn(caps.evRange.lower, caps.evRange.upper),
                )
                put(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
                // Keep AE at ≥ 30 fps so the exposure it settles on never slows a burst down.
                caps.fastFpsRange?.let { put(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
        }
        put(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        put(CaptureRequest.CONTROL_AWB_LOCK, awbLocked)
        put(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        if (caps.canControlOis) {
            put(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (stabilization && caps.supportsOis) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
    }

    /**
     * No artificial sharpening on the viewfinder: the camera's edge enhancement makes blurred edges
     * look crisp, which fools both your eye and focus peaking.
     */
    private fun CaptureRequest.Builder.honestPreview() {
        if (access?.caps?.supportsEdgeOff == true) put(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
    }

    /** Lighter image processing so the camera can keep up with a burst. */
    private fun CaptureRequest.Builder.fastProcessing() {
        val caps = access?.caps ?: return
        if (caps.supportsFastNoiseReduction) {
            put(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        }
        if (caps.supportsFastEdge) put(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            lastResult = result
            for (waiter in resultWaiters) waiter(result)
        }
    }

    /** Metadata for the lens actually in use — the physical lens's own result when streaming from one. */
    fun lensResult(result: TotalCaptureResult): CaptureResult {
        val physicalId = access?.physicalId ?: return result
        return physicalResults(result)[physicalId] ?: result
    }

    private fun physicalResults(result: TotalCaptureResult): Map<String, CaptureResult> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) result.physicalCameraTotalResults
        else @Suppress("DEPRECATION") result.physicalCameraResults

    /**
     * Camera descriptions to build a DNG from, best first. A DNG must describe the sensor that took
     * it; on a multi-lens camera that is whichever lens was active, which can differ from the camera
     * that was opened (e.g. the phone switched to its telephoto for a 3× zoom). Safe on any thread.
     */
    fun dngSources(result: TotalCaptureResult): List<Pair<CameraCharacteristics, CaptureResult>> {
        val a = access ?: return emptyList()
        if (a.physicalId != null) return listOf(a.caps.characteristics to lensResult(result))
        val sources = mutableListOf<Pair<CameraCharacteristics, CaptureResult>>()
        val active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        if (active != null && active != a.caps.id) {
            characteristicsOf(active)?.let { sources += it to (physicalResults(result)[active] ?: result) }
        }
        sources += a.caps.characteristics to result
        return sources
    }

    private fun characteristicsOf(id: String): CameraCharacteristics? =
        characteristicsCache[id] ?: try {
            manager.getCameraCharacteristics(id).also { characteristicsCache[id] = it }
        } catch (e: Exception) {
            Log.w(TAG, "No characteristics for lens $id", e)
            null
        }

    /** Suspends until a preview frame satisfies [predicate] (checked on the camera thread), or times out. */
    suspend fun awaitResult(timeoutMs: Long, predicate: (TotalCaptureResult) -> Boolean): TotalCaptureResult? {
        val deferred = CompletableDeferred<TotalCaptureResult>()
        val waiter: (TotalCaptureResult) -> Unit = { r ->
            if (!deferred.isCompleted && predicate(r)) deferred.complete(r)
        }
        resultWaiters += waiter
        try {
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            resultWaiters -= waiter
        }
    }

    /**
     * Waits until preview frames show the lens parked at [target]: the request carrying the new
     * focus has taken effect and the camera reports the lens as stationary for two frames.
     * Cameras that don't report lens state get three frames instead.
     */
    suspend fun awaitFocusSettled(target: Float, timeoutMs: Long): Boolean {
        var framesAtTarget = 0
        val result = awaitResult(timeoutMs) { r ->
            val requested = r.request.get(CaptureRequest.LENS_FOCUS_DISTANCE)
            if (requested == null || abs(requested - target) > 1e-5f) {
                false
            } else {
                framesAtTarget++
                when (lensResult(r).get(CaptureResult.LENS_STATE)) {
                    CameraMetadata.LENS_STATE_STATIONARY -> framesAtTarget >= 2
                    null -> framesAtTarget >= 3
                    else -> false
                }
            }
        }
        return result != null
    }

    /** Freezes exposure and white balance so every frame in the stack matches. */
    suspend fun lockExposureForStack() {
        val caps = caps ?: return
        awbLocked = true
        if (exposure.manual && caps.supportsManualExposure) {
            updatePreview()
            return
        }
        val r = lastResult?.let { lensResult(it) }
        val iso = r?.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureNs = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        if (caps.supportsManualExposure && r != null && iso != null && exposureNs != null) {
            // Copy what auto-exposure is doing right now into fixed manual values.
            frozenExposure = FrozenExposure(
                iso = iso,
                exposureNs = exposureNs,
                postRawBoost = r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
            )
            updatePreview()
            awaitResult(LOCK_TIMEOUT_MS) {
                it.request.get(CaptureRequest.CONTROL_AE_MODE) == CameraMetadata.CONTROL_AE_MODE_OFF
            }
        } else {
            aeLocked = true
            updatePreview()
            awaitResult(LOCK_TIMEOUT_MS) {
                it.get(CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_LOCKED
            }
        }
    }

    fun unlockExposure() {
        frozenExposure = null
        aeLocked = false
        awbLocked = false
        updatePreview()
    }

    // ---------------------------------------------------------------- still capture

    private fun stillRequest(
        dev: CameraDevice,
        focus: Float,
        jpegOrientation: Int,
        jpegQuality: Int,
        burst: Boolean,
    ): CaptureRequest.Builder = newRequest(dev, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
        val jpeg = jpegReader
        val raw = rawReader
        jpeg?.let { addTarget(it.surface) }
        raw?.let { addTarget(it.surface) }
        if (burst) previewSurface?.let { addTarget(it) } // keep the viewfinder live while the burst runs
        applyControls(focus)
        if (!highQualityProcessing) fastProcessing()
        if (raw != null && access?.caps?.supportsLensShadingMap == true) {
            put(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }
        // Zero-shutter-lag may return a frame from *before* this request — i.e. at the previous
        // focus step. Every frame must be exposed at exactly its own focus.
        set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        if (jpeg != null) {
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
        }
    }

    /** Which photo output a lost buffer belonged to: true = RAW, false = JPEG, null = neither. */
    private fun lostOutput(target: Surface): Boolean? = when (target) {
        rawReader?.surface -> true
        jpegReader?.surface -> false
        else -> null
    }

    /**
     * Shoots one photo (JPEG and/or RAW) at the current focus and exposure. Resumes once the sensor
     * has finished the exposure; the files arrive later through [jpegListener] / [rawListener].
     */
    suspend fun captureStill(
        index: Int,
        jpegOrientation: Int,
        jpegQuality: Int,
        onStarted: (timestamp: Long) -> Unit,
        onBufferLost: (raw: Boolean) -> Unit,
    ): TotalCaptureResult {
        val dev = device ?: throw IllegalStateException("Camera is closed")
        val s = session ?: throw IllegalStateException("Camera is closed")
        val request = stillRequest(dev, focusDiopters, jpegOrientation, jpegQuality, burst = false)
            .apply { setTag(StillTag(index)) }
            .build()

        return suspendCancellableCoroutine { cont ->
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long,
                ) = onStarted(timestamp)

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    if (cont.isActive) cont.resumeWithException(CaptureFailedException(failure.reason))
                }

                override fun onCaptureBufferLost(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    target: Surface,
                    frameNumber: Long,
                ) {
                    lostOutput(target)?.let(onBufferLost)
                }
            }
            try {
                s.capture(request, callback, cameraHandler)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    /**
     * Focus bracketing the way dedicated cameras do it: the whole stack is queued as one burst, each
     * photo with its own focus distance, and the sensor runs back-to-back. [settleFrames] extra frames
     * are inserted before each photo to give the focus motor time to arrive.
     *
     * Returns once the camera has finished (or aborted) the sequence. Cancelling the coroutine
     * aborts the burst.
     */
    suspend fun captureBurst(
        distances: List<Float>,
        settleFrames: Int,
        jpegOrientation: Int,
        jpegQuality: Int,
        listener: BurstListener,
    ) {
        val dev = device ?: throw IllegalStateException("Camera is closed")
        val s = session ?: throw IllegalStateException("Camera is closed")
        val preview = previewSurface ?: throw IllegalStateException("Camera is closed")

        val requests = ArrayList<CaptureRequest>(distances.size * (settleFrames + 1))
        distances.forEachIndexed { index, focus ->
            if (index > 0) {
                repeat(settleFrames) {
                    requests += newRequest(dev, CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview)
                        applyControls(focus)
                        honestPreview()
                    }.build()
                }
            }
            requests += stillRequest(dev, focus, jpegOrientation, jpegQuality, burst = true)
                .apply { setTag(StillTag(index)) }
                .build()
        }

        suspendCancellableCoroutine { cont ->
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long,
                ) {
                    (request.tag as? StillTag)?.let { listener.onStillStarted(it.index, timestamp) }
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    lastResult = result
                    (request.tag as? StillTag)?.let { listener.onStillCompleted(it.index, result) }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    (request.tag as? StillTag)?.let { listener.onStillFailed(it.index) }
                }

                override fun onCaptureBufferLost(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    target: Surface,
                    frameNumber: Long,
                ) {
                    val raw = lostOutput(target) ?: return
                    (request.tag as? StillTag)?.let { listener.onStillBufferLost(it.index, raw) }
                }

                override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            cont.invokeOnCancellation {
                mainHandler.post {
                    try {
                        session?.abortCaptures() // also clears the repeating preview…
                    } catch (e: Exception) {
                        Log.w(TAG, "Abort failed", e)
                    }
                    updatePreview() // …so restart it
                }
            }
            try {
                s.captureBurst(requests, callback, cameraHandler)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    private fun onJpegAvailable(reader: ImageReader) {
        try {
            val image = reader.acquireNextImage() ?: return
            val timestamp: Long
            val bytes: ByteArray
            try {
                val buffer = image.planes[0].buffer
                bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                timestamp = image.timestamp
            } finally {
                image.close()
            }
            jpegListener?.invoke(timestamp, bytes)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "JPEG reader closed while reading", e)
        }
    }

    /**
     * Hands out RAW frames while buffers are free. Never blocks: when the app is holding every RAW
     * buffer, remaining frames wait inside the reader and are picked up as soon as one is closed.
     */
    private fun drainRaw(reader: ImageReader) {
        val permits = rawPermits
        while (permits.tryAcquire()) {
            val image = try {
                reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                null // reader closed, or every buffer is out
            }
            if (image == null) {
                permits.release()
                return
            }
            val frame = RawFrame(image) {
                permits.release()
                rawHandler?.post { drainRaw(reader) }
            }
            val listener = rawListener
            if (listener == null) frame.close() else listener(frame)
        }
    }


    private fun describeDeviceError(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "The camera is in use by another app"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras are open — close other camera apps"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "The camera is disabled by a device policy"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "The camera hit a hardware error — try reopening the app"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "The camera service crashed — try restarting the phone"
        else -> "Camera error $error"
    }

    companion object {
        private const val TAG = "CameraController"
        private const val MAX_JPEG_IMAGES = 8
        const val MAX_RAW_IMAGES = 6
        private const val PREVIEW_UPDATE_INTERVAL_MS = 30L
        private const val LOCK_TIMEOUT_MS = 1000L
    }
}
