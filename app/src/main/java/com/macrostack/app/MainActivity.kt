package com.macrostack.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.macrostack.app.camera.CameraCaps
import com.macrostack.app.camera.CameraController
import com.macrostack.app.camera.ExposureValues
import com.macrostack.app.camera.Lens
import com.macrostack.app.camera.LensAccess
import com.macrostack.app.camera.LensCatalog
import com.macrostack.app.camera.Peaking
import com.macrostack.app.camera.PreviewRenderer
import com.macrostack.app.camera.PhotoFormat
import com.macrostack.app.stack.StackPlan
import com.macrostack.app.stack.StackResult
import com.macrostack.app.stack.StackRunner
import com.macrostack.app.stack.StackSaver
import com.macrostack.app.stack.StackState
import com.macrostack.app.stack.isBusy
import com.macrostack.app.ui.AspectFrameLayout
import com.macrostack.app.ui.CameraInfoSheet
import com.macrostack.app.ui.ExposureSheet
import com.macrostack.app.ui.FocusDial
import com.macrostack.app.ui.HelpSheet
import com.macrostack.app.ui.SettingsSheet
import com.macrostack.app.ui.RingProgressView
import com.macrostack.app.ui.ShutterButton
import com.macrostack.app.ui.StackingSheet
import com.macrostack.app.stacking.FusionManager
import com.macrostack.app.ui.onPressRepeat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var cameraManager: CameraManager
    private lateinit var camera: CameraController
    private lateinit var runner: StackRunner

    /** Draws the preview and focus peaking on the GPU; exists while the TextureView has a surface. */
    private var renderer: PreviewRenderer? = null
    private lateinit var orientationListener: OrientationEventListener

    // Viewfinder
    private lateinit var root: View
    private lateinit var previewArea: FrameLayout
    private lateinit var previewFrame: AspectFrameLayout
    private lateinit var zoomLayer: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var lensChips: LinearLayout
    private lateinit var lensWarning: TextView
    private lateinit var peakingButton: ImageView
    private lateinit var zoomButton: TextView
    private lateinit var notice: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeAction: TextView
    private lateinit var countdownText: TextView
    private lateinit var stackCounter: TextView
    private lateinit var exposureChip: TextView
    private lateinit var formatChip: TextView
    private lateinit var modeChip: TextView

    // Controls
    private lateinit var focusDial: FocusDial
    private lateinit var startCard: View
    private lateinit var endCard: View
    private lateinit var setStartButton: View
    private lateinit var setEndButton: View
    private lateinit var goStartButton: ImageView
    private lateinit var goEndButton: ImageView
    private lateinit var startValue: TextView
    private lateinit var endValue: TextView
    private lateinit var framesMinus: TextView
    private lateinit var framesPlus: TextView
    private lateinit var framesValue: TextView
    private lateinit var planInfo: TextView
    private lateinit var lastStackButton: ImageView
    private lateinit var shutterButton: ShutterButton
    private lateinit var settingsButton: ImageView
    private lateinit var stackingRing: RingProgressView

    /** Stacks finished stacks on the phone; shared with the whole app. */
    private lateinit var fusion: FusionManager
    private var lastFusionId = 0L

    /** Every lens found on this phone; discovered once per launch. */
    private var catalog: LensCatalog.Result? = null
    private var lens: Lens? = null

    /** How the current lens was reached, once it opened. */
    private var access: LensAccess? = null
    private val caps: CameraCaps? get() = access?.caps

    private var startPoint: Float? = null
    private var endPoint: Float? = null
    private var frames = 30
    private var isoOptions: List<Int> = emptyList()
    private var shutterOptions: List<Long> = emptyList()
    private var deviceOrientation = 0
    private var zoomLevel = 1
    private var openJob: Job? = null
    private var isActivityResumed = false
    private var permissionRequested = false
    private var noticeIsCameraError = false
    private var lastHandledResultId = 0L
    private var announceLensOnOpen = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class NoticeKind { INFO, SUCCESS, ERROR }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera()
            else showNotice(getString(R.string.need_camera_permission), NoticeKind.ERROR, cameraError = true)
        }

    /** The answer doesn't matter: without notifications, stacking still runs, just without a progress bar. */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val liveUpdater = object : Runnable {
        override fun run() {
            updateExposureChip()
            updateLensWarning()
            mainHandler.postDelayed(this, LIVE_UPDATE_MS)
        }
    }

    private val hideNoticeRunnable = Runnable { hideNotice() }

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()

        prefs = Prefs(this)
        cameraManager = getSystemService(CameraManager::class.java)
        camera = CameraController(this)
        fusion = (application as MacroStackApp).fusion
        runner = StackRunner(camera, StackSaver(this), lifecycleScope)
        camera.errorListener = { message -> showNotice(message, NoticeKind.ERROR, cameraError = true) }
        frames = prefs.frames.coerceIn(MIN_FRAMES, MAX_FRAMES)

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                // Phone lying flat (common for macro) reports UNKNOWN: keep the last real value.
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientation = ((orientation + 45) / 90 * 90) % 360
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupListeners()
        applyPeakingPrefs()
        updateChips()
        updatePlan()
        loadLastStackThumbnail()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runner.state.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fusion.state.collect { renderFusion(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        camera.startThreads()
        orientationListener.enable()
        mainHandler.post(liveUpdater)
        when {
            hasCameraPermission() -> openCamera()
            !permissionRequested -> {
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }

            else -> showNotice(getString(R.string.need_camera_permission), NoticeKind.ERROR, cameraError = true)
        }
    }

    override fun onPause() {
        isActivityResumed = false
        runner.cancel()
        openJob?.cancel()
        access?.let { prefs.setFocus(it.key, camera.focusDiopters) }
        camera.close()
        camera.stopThreads()
        fusion.paused = false // the camera is closed: stacking gets the CPU, even in the background
        orientationListener.disable()
        mainHandler.removeCallbacks(liveUpdater)
        super.onPause()
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ================================================================ lenses & camera

    /**
     * Opens the chosen lens. A lens can often be reached several ways (see [LensAccess]); they are
     * tried in turn — the one that worked last time first — until one delivers a picture.
     */
    private fun openCamera(force: Boolean = false) {
        if (!isActivityResumed || !hasCameraPermission() || !textureView.isAvailable) return
        if (!force && (camera.isOpen || openJob?.isActive == true)) return
        openJob?.cancel()
        openJob = lifecycleScope.launch {
            val found = catalog ?: withContext(Dispatchers.Default) { LensCatalog.discover(cameraManager) }
            catalog = found
            if (found.lenses.isEmpty()) {
                showNotice(getString(R.string.no_camera), NoticeKind.ERROR, cameraError = true)
                return@launch
            }
            val selected = found.lenses.firstOrNull { it.key == prefs.lensKey }
                ?: found.lenses.firstOrNull { it.ratio == 1f && it.accesses.any { a -> a.supportsManualFocus } }
                ?: found.lenses.first()
            lens = selected
            renderLensChips()

            val preview = renderer ?: return@launch
            val remembered = prefs.workingAccess(selected.key)
            val attempts = selected.accesses.sortedBy { if (it.key == remembered) 0 else 1 }
            val failures = mutableListOf<String>()
            // If RAW is wanted but a lens can't stream it alongside the preview, fall back to JPEG there.
            val formats = listOf(prefs.photoFormat, PhotoFormat.JPEG).distinct()
            for (candidate in attempts) {
                for (format in formats) {
                    val initialFocus = prepareFor(candidate)
                    try {
                        val streams = candidate.caps.streams(prefs.fullResolution, format)
                        val surface = preview.cameraSurface(streams.preview.width, streams.preview.height)
                        peakingButton.isVisible = preview.peakingAvailable
                        camera.open(candidate, streams, surface, initialFocus)
                        // Some routes "open" but never deliver a picture; treat that as a failure too.
                        if (camera.awaitResult(FIRST_FRAME_TIMEOUT_MS) { true } == null) {
                            throw IllegalStateException("no picture arrived")
                        }
                        prefs.setWorkingAccess(selected.key, candidate.key)
                        onCameraOpened()
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Lens ${selected.label} via ${candidate.description} (${format.label}) failed", e)
                        failures += "• ${candidate.description} (${format.label}): ${e.message ?: e.javaClass.simpleName}"
                        camera.close()
                    }
                }
            }
            access = null
            updateLensWarning()
            render(runner.state.value)
            showNotice(
                "Couldn't open the ${selected.label} lens. Tried:\n" + failures.joinToString("\n"),
                NoticeKind.ERROR,
                cameraError = true,
            )
        }
    }

    /** Loads per-lens state into the UI. Returns the focus to start the preview at. */
    private fun prepareFor(a: LensAccess): Float {
        access = a
        val c = a.caps
        val streams = c.streams(prefs.fullResolution, prefs.photoFormat)
        previewFrame.aspect = if (c.sensorOrientation % 180 == 90) {
            streams.preview.height.toFloat() / streams.preview.width
        } else {
            streams.preview.width.toFloat() / streams.preview.height
        }

        val max = a.maxFocusDiopters
        focusDial.maxDiopters = if (max > 0f) max else 1f
        startPoint = prefs.start(a.key)?.coerceIn(0f, max)
        endPoint = prefs.end(a.key)?.coerceIn(0f, max)
        val focus = (prefs.focus(a.key) ?: (max / 2f)).coerceIn(0f, max)

        camera.stabilization = prefs.stabilization
        camera.highQualityProcessing = prefs.highQualityProcessing
        isoOptions = ExposureValues.isoOptions(c)
        shutterOptions = ExposureValues.shutterOptions(c)
        camera.exposure = CameraController.Exposure(
            manual = prefs.manualExposure && c.supportsManualExposure,
            evIndex = prefs.evIndex.coerceIn(c.evRange.lower, c.evRange.upper),
            iso = ExposureValues.nearestIso(isoOptions, prefs.iso),
            exposureNs = ExposureValues.nearestShutter(shutterOptions, prefs.exposureNs),
        )

        setZoom(1)
        focusDial.setValue(focus)
        updatePlan(focus)
        updateChips()
        return focus
    }

    private fun onCameraOpened() {
        val a = access ?: return
        if (!a.supportsManualFocus) {
            showNotice(getString(R.string.no_manual_focus), NoticeKind.ERROR, cameraError = true)
        } else if (noticeIsCameraError) {
            hideNotice()
        }
        if (announceLensOnOpen) {
            announceLensOnOpen = false
            val eq = a.optics.equivalentFocalMm ?: lens?.focalEq
            showNotice(listOfNotNull(lens?.label, eq?.let { "$it mm" }).joinToString(" · "))
        }
        focusDial.setValue(camera.focusDiopters)
        updatePlan()
        updateChips()
        updateLensWarning()
        render(runner.state.value)
        if (!prefs.seenHelp) {
            prefs.seenHelp = true
            showHelp()
        }
    }

    private fun renderLensChips() {
        lensChips.removeAllViews()
        val found = catalog ?: return
        val density = resources.displayMetrics.density
        for (l in found.lenses) {
            val chip = TextView(this).apply {
                text = l.label
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColorStateList(R.color.lens_text))
                setBackgroundResource(R.drawable.bg_lens_item)
                minWidth = (44 * density).roundToInt()
                val pad = (8 * density).roundToInt()
                setPadding(pad, 0, pad, 0)
                isSelected = l.key == lens?.key
                isEnabled = !runner.isRunning
                contentDescription = "Lens ${l.label}"
                setOnClickListener { selectLens(l) }
            }
            lensChips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (34 * density).roundToInt()))
        }
    }

    private fun selectLens(l: Lens) {
        if (runner.isRunning) {
            showNotice(getString(R.string.lens_busy))
            return
        }
        if (l.key == lens?.key && camera.isOpen) return
        access?.let { prefs.setFocus(it.key, camera.focusDiopters) }
        prefs.lensKey = l.key
        lens = l
        announceLensOnOpen = true
        renderLensChips()
        openCamera(force = true)
    }

    /** Short warning under the lens buttons, only when something about the lens needs attention. */
    private fun updateLensWarning() {
        val warning = currentLensWarning()
        lensWarning.text = warning?.first
        lensWarning.isVisible = warning != null && !runner.isRunning
        lensWarning.setOnClickListener { warning?.let { showNotice(it.second, duration = LONG_NOTICE_MS) } }
    }

    /** (short text, explanation), or null when all is well. */
    private fun currentLensWarning(): Pair<String, String>? {
        val a = access ?: return null
        if (!camera.isOpen) return null
        if (!a.supportsManualFocus) {
            return getString(R.string.warning_no_mf) to getString(R.string.no_manual_focus)
        }
        // With zoom, the phone picks the lens. Say so when it isn't the one this button is for.
        val active = camera.activePhysicalId
        val expectedEq = a.optics.equivalentFocalMm ?: lens?.focalEq
        if (a is LensAccess.Zoom && active != null && expectedEq != null) {
            val activeEq = catalog?.camerasById?.get(active)?.equivalentFocalMm
            if (activeEq != null && abs(activeEq - expectedEq) > expectedEq * 0.15f) {
                return getString(R.string.warning_other_lens, activeEq) to
                    getString(R.string.warning_other_lens_detail, activeEq, expectedEq)
            }
        }
        if (prefs.photoFormat.raw && !camera.shootsRaw) {
            return getString(R.string.warning_no_raw) to getString(R.string.no_raw_on_lens)
        }
        return null
    }

    // ================================================================ UI wiring

    private fun bindViews() {
        root = findViewById(R.id.root)
        previewArea = findViewById(R.id.previewArea)
        previewFrame = findViewById(R.id.previewFrame)
        zoomLayer = findViewById(R.id.zoomLayer)
        textureView = findViewById(R.id.textureView)
        lensChips = findViewById(R.id.lensChips)
        lensWarning = findViewById(R.id.lensWarning)
        peakingButton = findViewById(R.id.peakingButton)
        zoomButton = findViewById(R.id.zoomButton)
        notice = findViewById(R.id.notice)
        noticeText = findViewById(R.id.noticeText)
        noticeAction = findViewById(R.id.noticeAction)
        countdownText = findViewById(R.id.countdownText)
        stackCounter = findViewById(R.id.stackCounter)
        exposureChip = findViewById(R.id.exposureChip)
        formatChip = findViewById(R.id.formatChip)
        modeChip = findViewById(R.id.modeChip)
        focusDial = findViewById(R.id.focusDial)
        startCard = findViewById(R.id.startCard)
        endCard = findViewById(R.id.endCard)
        setStartButton = findViewById(R.id.setStartButton)
        setEndButton = findViewById(R.id.setEndButton)
        goStartButton = findViewById(R.id.goStartButton)
        goEndButton = findViewById(R.id.goEndButton)
        startValue = findViewById(R.id.startValue)
        endValue = findViewById(R.id.endValue)
        framesMinus = findViewById(R.id.framesMinus)
        framesPlus = findViewById(R.id.framesPlus)
        framesValue = findViewById(R.id.framesValue)
        planInfo = findViewById(R.id.planInfo)
        lastStackButton = findViewById(R.id.lastStackButton)
        shutterButton = findViewById(R.id.shutterButton)
        settingsButton = findViewById(R.id.settingsButton)
        stackingRing = findViewById(R.id.stackingRing)
    }

    private fun setupListeners() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                renderer = PreviewRenderer(surface, width, height)
                applyPeakingPrefs()
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                renderer?.resize(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                camera.close()
                renderer?.release() // also releases this SurfaceTexture, after the GL surface on it
                renderer = null
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        // Preview: double-tap to magnify where you tapped, drag to look around while magnified.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomAt(nextZoom(), e.x - previewFrame.left, e.y - previewFrame.top)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                pan(-distanceX, -distanceY)
                return true
            }
        })
        previewArea.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            gestures.onTouchEvent(e)
        }
        zoomButton.setOnClickListener { setZoom(nextZoom()) }
        peakingButton.setOnClickListener {
            prefs.peakingEnabled = !prefs.peakingEnabled
            applyPeakingPrefs()
        }

        // Capture chips
        exposureChip.setOnClickListener { showExposureSheet() }
        formatChip.setOnClickListener { cycleFormat() }
        modeChip.setOnClickListener { toggleMode() }

        // Focus
        focusDial.onChange = { d ->
            camera.setFocus(d)
            updatePointCards(d)
        }
        setStartButton.setOnClickListener { markPoint(isStart = true) }
        setEndButton.setOnClickListener { markPoint(isStart = false) }
        goStartButton.setOnClickListener { startPoint?.let { moveFocus(it) } }
        goEndButton.setOnClickListener { endPoint?.let { moveFocus(it) } }

        // Frames
        framesMinus.onPressRepeat { changeFrames(-1) }
        framesPlus.onPressRepeat { changeFrames(+1) }

        // Bottom bar
        shutterButton.setOnClickListener { toggleStack() }
        settingsButton.setOnClickListener { showSettings() }
        lastStackButton.setOnClickListener {
            if (fusion.state.value == FusionManager.State.Idle && lastStackSource() == null) openLastStack() else showStacking()
        }
    }

    // ================================================================ focus & plan

    private fun moveFocus(d: Float) {
        camera.setFocus(d)
        focusDial.setValue(d)
        updatePointCards(d)
    }

    private fun markPoint(isStart: Boolean) {
        val a = access ?: return
        val focus = camera.focusDiopters
        if (isStart) {
            startPoint = focus
            prefs.setStart(a.key, focus)
        } else {
            endPoint = focus
            prefs.setEnd(a.key, focus)
        }
        val card = if (isStart) startCard else endCard
        card.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.KEYBOARD_TAP
        )
        card.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
            card.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }.start()
        updatePlan()
    }

    private fun changeFrames(delta: Int) {
        frames = (frames + delta).coerceIn(MIN_FRAMES, MAX_FRAMES)
        prefs.frames = frames
        updatePlan()
    }

    /** Refreshes everything that depends on start, end and frame count. */
    private fun updatePlan(focus: Float = camera.focusDiopters) {
        focusDial.start = startPoint
        focusDial.end = endPoint
        focusDial.frames = frames
        framesValue.text = getString(R.string.frames_count, frames)

        val max = access?.maxFocusDiopters ?: 0f
        val s = startPoint
        val e = endPoint
        planInfo.text = if (s == null || e == null || max <= 0f) {
            getString(R.string.plan_needs_points)
        } else {
            val stepPercent = abs(e - s) / (frames - 1) / max * 100f
            val perFrame = prefs.secondsPerFrame(prefs.fastMode, raw = camera.shootsRaw)
            val seconds = (frames * perFrame + prefs.startDelaySec).roundToInt().coerceAtLeast(1)
            getString(R.string.plan_summary, "%.1f".format(Locale.US, stepPercent), formatDuration(seconds))
        }
        updatePointCards(focus)
    }

    private fun updatePointCards(focus: Float) {
        bindPoint(startPoint, startValue, goStartButton, startCard, focus)
        bindPoint(endPoint, endValue, goEndButton, endCard, focus)
    }

    private fun bindPoint(point: Float?, value: TextView, go: ImageView, card: View, focus: Float) {
        if (point == null) {
            value.text = getString(R.string.tap_to_mark)
            value.setTextColor(getColor(R.color.text_tertiary))
        } else {
            value.text = "%.1f".format(Locale.US, focusDial.percentOf(point))
            value.setTextColor(getColor(R.color.text))
        }
        go.isEnabled = point != null && card.isEnabled
        go.alpha = if (go.isEnabled) 1f else 0.3f
        val tolerance = (access?.maxFocusDiopters ?: 1f) * 0.0005f
        card.isActivated = point != null && abs(point - focus) <= tolerance
    }

    private fun formatDuration(seconds: Int): String =
        if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"

    // ================================================================ chips: exposure, format, mode

    private fun updateChips() {
        updateExposureChip()
        formatChip.text = when (prefs.photoFormat) {
            PhotoFormat.JPEG -> "JPEG"
            PhotoFormat.RAW -> "RAW"
            PhotoFormat.RAW_JPEG -> "RAW+JPG"
        }
        formatChip.isSelected = prefs.photoFormat.raw
        modeChip.text = if (prefs.fastMode) "FAST" else "PRECISE"
    }

    private fun updateExposureChip() {
        val e = camera.exposure
        val manual = e.manual && caps?.supportsManualExposure == true
        val r = camera.lastResult?.let { camera.lensResult(it) }
        val liveIso = r?.get(CaptureResult.SENSOR_SENSITIVITY)
        val liveShutter = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        // "ISO 320 · 1/50" in auto (what the camera picked), "M  ISO 100 · 1/60" in manual.
        exposureChip.text = buildString {
            if (manual) {
                append("M  ISO ${e.iso} · ${ExposureValues.formatShutter(e.exposureNs)}")
            } else {
                val c = caps
                if (c != null && e.evIndex != 0) append(ExposureValues.formatEv(e.evIndex, c.evStep)).append("  ")
                if (liveIso != null && liveShutter != null) {
                    append("ISO $liveIso · ${ExposureValues.formatShutter(liveShutter)}")
                } else {
                    append(getString(R.string.auto).uppercase(Locale.US))
                }
            }
        }
    }

    private fun cycleFormat() {
        val next = PhotoFormat.entries[(prefs.photoFormat.ordinal + 1) % PhotoFormat.entries.size]
        prefs.photoFormat = next
        updateChips()
        showNotice(getString(SettingsSheet.formatHintFor(next)))
        access?.let { prefs.setFocus(it.key, camera.focusDiopters) }
        openCamera(force = true)
    }

    private fun toggleMode() {
        prefs.fastMode = !prefs.fastMode
        updateChips()
        updatePlan()
        showNotice(getString(if (prefs.fastMode) R.string.mode_fast_hint else R.string.mode_precise_hint))
    }

    // ================================================================ exposure

    private fun changeExposure(update: (CameraController.Exposure) -> CameraController.Exposure) {
        val e = update(camera.exposure)
        camera.exposure = e
        camera.requestPreviewUpdate()
        prefs.manualExposure = e.manual
        prefs.evIndex = e.evIndex
        prefs.iso = e.iso
        prefs.exposureNs = e.exposureNs
        updateExposureChip()
    }

    private fun showExposureSheet() {
        ExposureSheet(this, object : ExposureSheet.Host {
            override fun isManual() = camera.exposure.manual && caps?.supportsManualExposure == true

            override fun setManual(manual: Boolean): Boolean {
                val c = caps ?: return false
                if (!manual) {
                    changeExposure { it.copy(manual = false) }
                    return true
                }
                if (!c.supportsManualExposure) {
                    showNotice(getString(R.string.no_manual_exposure))
                    return false
                }
                // Start manual mode from whatever auto exposure is using right now.
                val r = camera.lastResult?.let { camera.lensResult(it) }
                val iso = r?.get(CaptureResult.SENSOR_SENSITIVITY)
                val exposureNs = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                changeExposure {
                    it.copy(
                        manual = true,
                        iso = iso?.let { v -> ExposureValues.nearestIso(isoOptions, v) } ?: it.iso,
                        exposureNs = exposureNs?.let { v -> ExposureValues.nearestShutter(shutterOptions, v) }
                            ?: it.exposureNs,
                    )
                }
                return true
            }

            override fun stepEv(delta: Int) {
                val c = caps ?: return
                changeExposure { it.copy(evIndex = (it.evIndex + delta).coerceIn(c.evRange.lower, c.evRange.upper)) }
            }

            override fun stepIso(delta: Int) {
                if (isoOptions.isEmpty()) return
                changeExposure {
                    val i = isoOptions.indexOf(ExposureValues.nearestIso(isoOptions, it.iso))
                    it.copy(iso = isoOptions[(i + delta).coerceIn(0, isoOptions.size - 1)])
                }
            }

            /** −1 = faster shutter (darker), +1 = slower shutter (brighter). */
            override fun stepShutter(delta: Int) {
                if (shutterOptions.isEmpty()) return
                changeExposure {
                    val i = shutterOptions.indexOf(ExposureValues.nearestShutter(shutterOptions, it.exposureNs))
                    it.copy(exposureNs = shutterOptions[(i + delta).coerceIn(0, shutterOptions.size - 1)])
                }
            }

            override fun evText() = caps?.let { ExposureValues.formatEv(camera.exposure.evIndex, it.evStep) } ?: "0.0"
            override fun isoText() = "${camera.exposure.iso}"
            override fun shutterText() = ExposureValues.formatShutter(camera.exposure.exposureNs)

            override fun liveText(): String {
                val r = camera.lastResult?.let { camera.lensResult(it) } ?: return ""
                val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return ""
                val exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return ""
                return "Camera now: ISO $iso · ${ExposureValues.formatShutter(exposureNs)}"
            }
        }).show()
    }

    // ================================================================ preview zoom & peaking

    private fun nextZoom() = when (zoomLevel) {
        1 -> 2
        2 -> 4
        else -> 1
    }

    private fun setZoom(level: Int) = zoomAt(level, zoomLayer.width / 2f, zoomLayer.height / 2f)

    /** Magnifies the preview to [level]×, centring the point ([x], [y]) in preview coordinates. */
    private fun zoomAt(level: Int, x: Float, y: Float) {
        val oldZoom = zoomLevel.toFloat()
        // Offset of the tapped point from the content centre, in unmagnified pixels.
        val u = (x - zoomLayer.width / 2f - zoomLayer.translationX) / oldZoom
        val v = (y - zoomLayer.height / 2f - zoomLayer.translationY) / oldZoom
        zoomLevel = level
        zoomLayer.scaleX = level.toFloat()
        zoomLayer.scaleY = level.toFloat()
        zoomLayer.translationX = 0f
        zoomLayer.translationY = 0f
        if (level > 1) pan(-u * level, -v * level)
        zoomButton.text = "$level×"
        zoomButton.isSelected = level > 1
    }

    private fun pan(dx: Float, dy: Float) {
        if (zoomLevel == 1) return
        val maxX = (zoomLevel - 1) * zoomLayer.width / 2f
        val maxY = (zoomLevel - 1) * zoomLayer.height / 2f
        zoomLayer.translationX = (zoomLayer.translationX + dx).coerceIn(-maxX, maxX)
        zoomLayer.translationY = (zoomLayer.translationY + dy).coerceIn(-maxY, maxY)
    }

    private fun applyPeakingPrefs() {
        val on = prefs.peakingEnabled
        val level = prefs.peakingLevel.coerceIn(0, Peaking.SHARPNESS.size - 1)
        renderer?.peaking = PreviewRenderer.PeakingSettings(
            enabled = on,
            color = Peaking.COLORS[prefs.peakingColor.coerceIn(0, Peaking.COLORS.size - 1)],
            sharpness = Peaking.SHARPNESS[level],
            minContrast = Peaking.MIN_CONTRAST[level],
        )
        peakingButton.isSelected = on
    }

    // ================================================================ stacking

    private fun toggleStack() {
        if (runner.isRunning) runner.cancel() else startStack()
    }

    private fun startStack() {
        val a = access ?: return
        if (!camera.isOpen) return
        if (!a.supportsManualFocus) {
            showNotice(getString(R.string.no_manual_focus), NoticeKind.ERROR)
            return
        }
        val s = startPoint
        val e = endPoint
        if (s == null || e == null) {
            showNotice(getString(R.string.set_points_first))
            return
        }
        if (abs(s - e) < a.maxFocusDiopters / 1000f) {
            showNotice(getString(R.string.same_points))
            return
        }
        hideNotice()
        runner.start(
            StackPlan(s, e, frames),
            StackRunner.Options(
                fast = prefs.fastMode,
                startDelaySec = prefs.startDelaySec,
                settleFrames = prefs.settleFrames,
                settleMs = prefs.settleMs,
                jpegQuality = prefs.jpegQuality,
                orientation = (a.caps.sensorOrientation + deviceOrientation) % 360,
            ),
        )
    }

    private fun render(state: StackState) {
        val busy = state.isBusy
        // Leave the CPU to the camera while it shoots; stacking picks up again afterwards.
        fusion.paused = busy
        setControlsEnabled(!busy)
        shutterButton.isEnabled = busy || access?.supportsManualFocus == true
        countdownText.isVisible = state is StackState.Countdown
        stackCounter.isVisible = state is StackState.Preparing || state is StackState.Shooting || state is StackState.Finishing

        when (state) {
            is StackState.Countdown -> {
                countdownText.text = state.seconds.toString()
                shutterButton.showCountdown(state.seconds)
            }

            StackState.Preparing -> {
                hideNotice()
                stackCounter.text = getString(R.string.getting_ready)
                shutterButton.showProgress(0f)
            }

            is StackState.Shooting -> {
                stackCounter.text = "${state.frame} / ${state.total}"
                shutterButton.showProgress(state.frame / state.total.toFloat())
                state.focus?.let { focusDial.setValue(it) }
            }

            is StackState.Finishing -> {
                stackCounter.text = getString(R.string.saving)
                shutterButton.showSaving()
            }

            is StackState.Finished -> {
                shutterButton.showReady()
                focusDial.setValue(camera.focusDiopters)
                updatePointCards(camera.focusDiopters)
                if (state.result.id != lastHandledResultId) {
                    lastHandledResultId = state.result.id
                    onStackFinished(state.result)
                }
            }

            is StackState.Failed -> {
                shutterButton.showReady()
                focusDial.setValue(camera.focusDiopters)
                showNotice("Stack failed: ${state.message}", NoticeKind.ERROR)
            }

            StackState.Idle -> shutterButton.showReady()
        }
    }

    private fun onStackFinished(r: StackResult) {
        r.previewUri?.let { uri ->
            prefs.lastStackUri = uri.toString()
            prefs.lastStackMime = r.previewMime
            loadLastStackThumbnail()
        }

        val kind = when {
            r.raw && r.jpeg -> " RAW+JPEG"
            r.raw -> " RAW"
            else -> ""
        }
        val lines = mutableListOf<String>()
        lines += if (r.stopped) {
            "Stopped · ${r.saved} of ${r.total}$kind photos saved"
        } else {
            val perSecond = if (r.shootSeconds > 0f) r.total / r.shootSeconds else 0f
            "✓ ${r.saved}$kind photos · %.1f s · %.1f per second".format(Locale.US, r.shootSeconds, perSecond)
        }
        if (r.raw && r.jpeg && r.savedRaw != r.savedJpeg) lines += "RAW ${r.savedRaw} · JPEG ${r.savedJpeg}"
        if (r.failedFrames > 0) lines += "${r.failedFrames} file(s) failed" + (r.firstError?.let { ": $it" } ?: "")
        if (r.movingFrames > 0) {
            val fix = if (r.fast) "Settings › Lens settle" else "Settings › Extra wait"
            lines += "${r.movingFrames} shot while the lens was still moving: try $fix"
        }
        if (r.lensSwitched) lines += "The phone switched lenses mid-stack, so frames may not line up"

        if (r.saved > 0) {
            prefs.lastStackFolder = r.folderPath
            prefs.lastStackName = r.folderName
        }
        val stackNow = prefs.stackOnPhone && r.saved >= 2
        if (stackNow) {
            stackOnPhone(FusionManager.Source(r.folderName, r.folderPath, prefs.mergeMethod))
            lines += getString(R.string.stacking_started)
        }
        showNotice(
            lines.joinToString("\n"),
            if (r.saved == 0) NoticeKind.ERROR else NoticeKind.SUCCESS,
            actionLabel = when {
                stackNow -> getString(R.string.watch)
                r.previewUri != null -> getString(R.string.view)
                else -> null
            },
            action = { if (stackNow) showStacking() else openLastStack() },
        )
        if (!r.stopped && r.saved == r.total && r.total > 0) {
            prefs.setSecondsPerFrame(r.fast, r.raw, r.totalSeconds / r.total)
            updatePlan()
        }
        if (prefs.beep && !r.stopped) beep()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val focusEnabled = enabled && access?.supportsManualFocus != false
        focusDial.isEnabled = focusEnabled
        for (v in listOf(startCard, endCard, setStartButton, setEndButton)) v.isEnabled = focusEnabled
        startCard.alpha = if (focusEnabled) 1f else 0.5f
        endCard.alpha = if (focusEnabled) 1f else 0.5f
        for (v in listOf<View>(framesMinus, framesPlus, exposureChip, formatChip, modeChip, settingsButton, lastStackButton)) {
            v.isEnabled = enabled
            v.alpha = if (enabled) 1f else 0.4f
        }
        lensChips.children.forEach { it.isEnabled = enabled }
        updatePointCards(camera.focusDiopters)
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            mainHandler.postDelayed({ tone.release() }, 600)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Beep failed", e)
        }
    }

    // ================================================================ last stack

    private fun loadLastStackThumbnail() {
        val uri = prefs.lastStackUri?.let(Uri::parse)
        if (uri == null) {
            showThumbnailPlaceholder()
            return
        }
        lifecycleScope.launch {
            val size = (64 * resources.displayMetrics.density).roundToInt()
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    contentResolver.loadThumbnail(uri, Size(size, size), null)
                } catch (e: Exception) {
                    Log.w(TAG, "No thumbnail for $uri", e)
                    null
                }
            }
            if (bitmap == null) showThumbnailPlaceholder() else showThumbnail(bitmap)
        }
    }

    private fun showThumbnail(bitmap: Bitmap) {
        lastStackButton.imageTintList = null
        lastStackButton.setPadding(0, 0, 0, 0)
        lastStackButton.scaleType = ImageView.ScaleType.CENTER_CROP
        lastStackButton.clipToOutline = true
        lastStackButton.setImageBitmap(bitmap)
    }

    private fun showThumbnailPlaceholder() {
        val pad = (14 * resources.displayMetrics.density).roundToInt()
        lastStackButton.setPadding(pad, pad, pad, pad)
        lastStackButton.scaleType = ImageView.ScaleType.FIT_CENTER
        lastStackButton.imageTintList = getColorStateList(R.color.text_secondary)
        lastStackButton.setImageResource(R.drawable.ic_gallery)
    }

    private fun openLastStack() {
        val uri = prefs.lastStackUri?.let(Uri::parse)
        if (uri == null) {
            showNotice(getString(R.string.no_stacks_yet))
            return
        }
        openInGallery(uri, prefs.lastStackMime ?: "image/*")
    }

    private fun openInGallery(uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showNotice(getString(R.string.no_gallery))
        }
    }

    // ================================================================ on-phone stacking

    private fun renderFusion(state: FusionManager.State) {
        stackingRing.isVisible = state is FusionManager.State.Running
        when (state) {
            is FusionManager.State.Running -> {
                stackingRing.progress = state.fraction
                state.preview?.let { showThumbnail(it) }
            }

            is FusionManager.State.Done -> if (state.id != lastFusionId) {
                lastFusionId = state.id
                prefs.lastStackUri = state.uri.toString()
                prefs.lastStackMime = StackSaver.JPEG_MIME
                loadLastStackThumbnail()
                if (!runner.isRunning) {
                    showNotice(
                        getString(R.string.stacking_finished, "%.0f".format(Locale.US, state.seconds)),
                        NoticeKind.SUCCESS,
                        actionLabel = getString(R.string.view),
                        action = { openInGallery(state.uri, StackSaver.JPEG_MIME) },
                    )
                }
            }

            is FusionManager.State.Failed -> if (state.id != lastFusionId) {
                lastFusionId = state.id
                loadLastStackThumbnail()
                if (!state.cancelled && !runner.isRunning) {
                    showNotice(state.message, NoticeKind.ERROR, actionLabel = getString(R.string.view), action = { showStacking() })
                }
            }

            FusionManager.State.Idle -> Unit
        }
    }

    /** Queues a stack for merging; the first time, asks to show its progress as a notification. */
    private fun stackOnPhone(source: FusionManager.Source) {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.askedNotifications = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        fusion.enqueue(source)
    }

    private fun showStacking() {
        StackingSheet(
            activity = this,
            manager = fusion,
            lastStack = lastStackSource(),
            onOpenResult = { uri -> openInGallery(uri, StackSaver.JPEG_MIME) },
            onOpenPhotos = ::openLastStack,
            onStack = ::stackOnPhone,
        ).show()
    }

    /** The last shot stack, with the current merge method — for stacking it (again) by hand. */
    private fun lastStackSource(): FusionManager.Source? {
        val folder = prefs.lastStackFolder ?: return null
        val name = prefs.lastStackName ?: return null
        return FusionManager.Source(name, folder, prefs.mergeMethod)
    }

    // Volume keys and Bluetooth camera remotes (which send volume-up or enter) start / stop a stack.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && isTriggerKey(keyCode)) {
            if (event.repeatCount == 0) toggleStack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && isTriggerKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isTriggerKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
        keyCode == KeyEvent.KEYCODE_CAMERA ||
        keyCode == KeyEvent.KEYCODE_ENTER

    // ================================================================ notices & sheets

    /**
     * A message near the top of the viewfinder. Info fades after a few seconds, results stay a
     * little longer, errors stay until tapped.
     */
    private fun showNotice(
        text: CharSequence,
        kind: NoticeKind = NoticeKind.INFO,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
        cameraError: Boolean = false,
        duration: Long? = null,
    ) {
        mainHandler.removeCallbacks(hideNoticeRunnable)
        noticeText.text = text
        notice.setBackgroundResource(if (kind == NoticeKind.ERROR) R.drawable.bg_notice_error else R.drawable.bg_notice)
        noticeAction.text = actionLabel
        noticeAction.isVisible = actionLabel != null && action != null
        noticeAction.setOnClickListener {
            hideNotice()
            action?.invoke()
        }
        notice.setOnClickListener { hideNotice() }
        notice.animate().cancel()
        if (!notice.isVisible) {
            notice.alpha = 0f
            notice.isVisible = true
        }
        notice.animate().alpha(1f).setDuration(150).start()
        noticeIsCameraError = cameraError
        val showFor = duration ?: when (kind) {
            NoticeKind.INFO -> INFO_NOTICE_MS
            NoticeKind.SUCCESS -> RESULT_NOTICE_MS
            NoticeKind.ERROR -> 0L
        }
        if (showFor > 0) mainHandler.postDelayed(hideNoticeRunnable, showFor)
    }

    private fun hideNotice() {
        mainHandler.removeCallbacks(hideNoticeRunnable)
        noticeIsCameraError = false
        if (!notice.isVisible) return
        notice.animate().cancel()
        notice.animate().alpha(0f).setDuration(150).withEndAction { notice.isVisible = false }.start()
    }

    private fun showSettings() {
        SettingsSheet(
            activity = this,
            prefs = prefs,
            oisSupported = caps?.supportsOis ?: true,
            onChanged = {
                applyPeakingPrefs()
                camera.stabilization = prefs.stabilization
                camera.highQualityProcessing = prefs.highQualityProcessing
                camera.requestPreviewUpdate()
                updateChips()
                updatePlan()
            },
            onClosed = { reopen ->
                updateChips()
                updatePlan()
                if (reopen) {
                    access?.let { prefs.setFocus(it.key, camera.focusDiopters) }
                    openCamera(force = true)
                }
            },
            onHelp = ::showHelp,
            onCameraInfo = ::showCameraInfo,
        ).show()
    }

    private fun showHelp() = HelpSheet.show(this, onCameraInfo = ::showCameraInfo)

    private fun showCameraInfo() = CameraInfoSheet.show(this, diagnosticsText())

    private fun diagnosticsText(): String = buildString {
        appendLine("MacroStack ${BuildConfig.VERSION_NAME}")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
        appendLine("Mode: ${if (prefs.fastMode) "fast, settle ${prefs.settleFrames} frame(s)" else "precise"}")
        appendLine(
            "Format: ${prefs.photoFormat.label} (shooting: " +
                listOfNotNull("RAW".takeIf { camera.shootsRaw }, "JPEG".takeIf { camera.shootsJpeg })
                    .joinToString(" + ").ifEmpty { "—" } +
                ") · processing ${if (prefs.highQualityProcessing) "high quality" else "fast"}"
        )
        appendLine()
        val found = catalog
        if (found == null) appendLine("Lenses not discovered yet.") else append(found.report)
        appendLine()

        val a = access
        if (a == null) {
            appendLine("No lens open.")
        } else {
            appendLine("── In use: ${lens?.label} via ${a.description} ──")
            append(a.caps.describe(camera.streams))
            if (a.optics !== a.caps) {
                appendLine("Expected lens:")
                append(a.optics.describe())
            }
        }
        camera.lastResult?.let { total ->
            val r = camera.lensResult(total)
            appendLine("── Live ──")
            appendLine("Active physical lens: ${camera.activePhysicalId ?: "not reported"}")
            appendLine("Focus requested: ${total.request.get(CaptureRequest.LENS_FOCUS_DISTANCE)} D")
            appendLine("Focus reported: ${r.get(CaptureResult.LENS_FOCUS_DISTANCE)} D")
            val lensState = when (r.get(CaptureResult.LENS_STATE)) {
                CameraMetadata.LENS_STATE_STATIONARY -> "stationary"
                CameraMetadata.LENS_STATE_MOVING -> "moving"
                else -> "not reported"
            }
            appendLine("Lens state: $lensState")
            appendLine("AE state: ${r.get(CaptureResult.CONTROL_AE_STATE)}")
            val exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            appendLine(
                "ISO ${r.get(CaptureResult.SENSOR_SENSITIVITY)} · shutter " +
                    (exposureNs?.let { ExposureValues.formatShutter(it) } ?: "?") +
                    " · frame ${r.get(CaptureResult.SENSOR_FRAME_DURATION)?.let { "%.1f ms".format(Locale.US, it / 1e6) } ?: "?"}"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                appendLine("Zoom ratio: ${total.get(CaptureResult.CONTROL_ZOOM_RATIO)}")
            }
        }
    }

    private companion object {
        const val TAG = "MacroStack"
        const val MIN_FRAMES = 3
        const val MAX_FRAMES = 300
        const val LIVE_UPDATE_MS = 500L
        const val FIRST_FRAME_TIMEOUT_MS = 2500L
        const val INFO_NOTICE_MS = 3500L
        const val RESULT_NOTICE_MS = 9000L
        const val LONG_NOTICE_MS = 8000L
    }
}
