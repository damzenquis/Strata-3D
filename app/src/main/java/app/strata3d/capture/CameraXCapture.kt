package app.strata3d.capture

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import app.strata3d.capture.camera.CameraGeometry
import app.strata3d.capture.camera.DeviceGuards
import app.strata3d.capture.format.CameraInfo
import app.strata3d.capture.format.CaptureManifest
import app.strata3d.capture.format.CaptureState
import app.strata3d.capture.format.CaptureStore
import app.strata3d.capture.format.DeviceInfo
import app.strata3d.capture.format.FrameRecord
import app.strata3d.capture.format.Intrinsics
import app.strata3d.capture.format.IntrinsicsSource
import app.strata3d.capture.format.ObjectTap
import app.strata3d.capture.format.SensorOrientation
import app.strata3d.capture.format.StoredCapture
import app.strata3d.capture.shutter.CoverageRings
import app.strata3d.capture.shutter.DeviceOrientation
import app.strata3d.capture.shutter.MotionSensors
import app.strata3d.capture.shutter.SmartShutter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The capture engine: CameraX preview and full-resolution photos, the motion sensors, and the [SmartShutter].
 *
 * Rules that keep it safe for the camera:
 * - **One photo in flight.** The smart shutter and the manual button both go through [SmartShutter], which allows a
 *   new photo only after the previous one is reported saved or failed. The camera never gets a second request.
 * - The smart shutter only decides *when*; both modes call the same [takePhoto]. It never changes camera settings.
 * - A failed photo is reported and the smart shutter pauses; it never retries on its own.
 * - Camera use cases are bound to the activity lifecycle (CameraX opens and closes the camera); sensors run only
 *   while the screen is resumed ([onResume] / [onPause]).
 *
 * Threads: UI calls come on the main thread; sensor readings arrive on the sensor thread; photo results and all file
 * writes run on [io], one at a time. Shared capture state is guarded by [lock].
 */
class CameraXCapture(
    private val context: Context,
    private val store: CaptureStore,
    private val appVersion: String,
) {
    enum class Phase { STARTING, AIMING, CAPTURING, SAVING, SAVED, ERROR }
    enum class Mode { SMART, MANUAL }

    /** A short message over the camera; null when there is nothing to say. */
    enum class Notice { NONE, TAP_OBJECT, KEEP_MOVING, HOLD_STEADY, SAVING_PHOTO, PAUSED, MANUAL, HOT, LOW_STORAGE, PHOTO_FAILED, NO_SENSORS }

    data class UiState(
        val phase: Phase = Phase.STARTING,
        val mode: Mode = Mode.SMART,
        val smartAvailable: Boolean = true,
        val notice: Notice = Notice.NONE,
        /** 0..1 toward the next smart photo. */
        val progress: Double = 0.0,
        val photoInFlight: Boolean = false,
        val paused: Boolean = false,
        val photoCount: Int = 0,
        val lowerRing: List<Boolean> = List(CELLS) { false },
        val upperRing: List<Boolean> = List(CELLS) { false },
        /** The tap in view pixels, shown until the first photo. */
        val tapX: Float? = null,
        val tapY: Float? = null,
        /** Increments with every saved photo, for the confirmation flash. */
        val flashCount: Int = 0,
        val hot: Boolean = false,
        val errorMessage: String? = null,
        val savedCaptureId: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "strata-capture-io") }
    private val guardExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "strata-thermal") }
    private val guards = DeviceGuards(context, store.root) { main.post { onThermalChange() } }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sensors = MotionSensors(context, ::onSensorReading)
    /** What this phone offers; photo size, JPEG quality and shutter pace follow from it (see [DeviceProfile]). */
    private val device = DeviceProfile.read(context)
    private val shutter = SmartShutter(SmartShutter.Settings(minIntervalMs = device.minShutterIntervalMs))
    private val rings = CoverageRings(CoverageRings.Settings(cells = CELLS))

    private val lock = Any()
    // Guarded by lock.
    private var capture: StoredCapture? = null
    private var manifest: CaptureManifest? = null
    private var photoCount = 0
    private var inFlight = false
    private var finishing: Boolean = false
    private var discardOnFinish = false
    private var shutdownRequested = false
    private var cameraInfo: CameraInfo? = null

    // Main thread.
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var resumed = false
    private var lastProgressPublishNs = 0L
    /** Sensor thread: the ring-full vibration was given for the next photo. */
    @Volatile private var buzzedForNext = false

    init {
        val mode = if (!sensors.available) Mode.MANUAL else Mode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: Mode.SMART
        _state.update { it.copy(mode = mode, smartAvailable = sensors.available, notice = Notice.TAP_OBJECT) }
    }

    // ---- lifecycle (main thread) ----

    /** Binds the preview and photo use cases to [owner]; CameraX opens the camera when it is started. */
    fun bind(owner: LifecycleOwner, view: PreviewView) {
        previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = try { future.get() } catch (e: Exception) {
                fail("The camera could not start (${e.javaClass.simpleName})."); return@addListener
            }
            provider = p
            try {
                bindUseCases(owner, p, view)
            } catch (e: Exception) {
                Log.e(TAG, "bind", e)
                fail("The camera could not start (${e.javaClass.simpleName}).")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(owner: LifecycleOwner, p: ProcessCameraProvider, view: PreviewView) {
        val ratio = AspectRatioStrategy(androidx.camera.core.AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val maxPhoto = device.maxPhotoSize.let { (w, h) -> Size(w, h) }
        val photoSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(ratio)
            .setResolutionStrategy(ResolutionStrategy(maxPhoto, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(photoSelector)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(device.jpegQuality)
            .setTargetRotation(view.display?.rotation ?: android.view.Surface.ROTATION_0)
            .build()
        p.unbindAll()
        val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        preview.surfaceProvider = view.surfaceProvider
        camera = cam
        imageCapture = capture
        cameraInfo = readCameraInfo(cam, capture)
        Log.i(TAG, "bound: photo ${capture.resolutionInfo?.resolution} rotation ${capture.resolutionInfo?.rotationDegrees}")
        if (_state.value.phase == Phase.STARTING) _state.update { it.copy(phase = Phase.AIMING) }
    }

    fun onResume() {
        resumed = true
        guards.start(guardExecutor)
        sensors.start()
    }

    fun onPause() {
        if (!resumed) return
        resumed = false
        sensors.stop()
        guards.stop()
    }

    /** The screen is gone: unbind the camera, stop sensors, and keep (or discard) what was recorded. */
    fun shutdown() {
        onPause()
        val open = synchronized(lock) {
            shutdownRequested = true
            if (capture != null && !finishing) { finishing = true; discardOnFinish = false }
            capture != null
        }
        // Unbinding makes CameraX report a photo still in flight as failed, on [io]; [completeEnd] then closes the
        // capture and shuts [io] down. Without an open capture there is nothing to wait for.
        provider?.unbindAll()
        provider = null; camera = null; imageCapture = null; previewView = null
        if (!open) io.shutdown()
        else if (!synchronized(lock) { inFlight }) runIo { completeEnd() }
        guardExecutor.shutdown()
    }

    /** Runs [block] on [io]; a no-op once [io] has stopped (the capture is then already closed). */
    private fun runIo(block: () -> Unit) {
        try { io.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    // ---- user actions (main thread) ----

    /**
     * The user tapped the preview at view pixel ([x], [y]). Before the first photo this marks (or re-marks) the
     * object and arms the shutter; afterwards it does nothing.
     */
    fun onTap(x: Float, y: Float) {
        val view = previewView ?: return
        val ic = imageCapture ?: return
        if (synchronized(lock) { photoCount > 0 || finishing }) return
        val info = ic.resolutionInfo
        val (uw, uh) = if (info != null) CameraGeometry.uprightSize(info.resolution.width, info.resolution.height, info.rotationDegrees)
        else 3 to 4   // portrait 4:3 until CameraX reports the actual size
        val content = CameraGeometry.fitCenter(view.width.toFloat(), view.height.toFloat(), uw, uh)
        val (u, v) = CameraGeometry.tapToImage(x, y, content) ?: return
        val o = sensors.latest
        val tap = ObjectTap(u, v, SystemClock.elapsedRealtimeNanos(), o?.toSensorOrientation())
        synchronized(lock) {
            if (capture == null) {
                if (guards.freeBytes() < DeviceGuards.MIN_FREE_TO_START) {
                    _state.update { it.copy(notice = Notice.LOW_STORAGE) }
                    return
                }
                try {
                    val created = store.create(newManifest(tap))
                    capture = created
                    manifest = created.manifest
                } catch (e: Exception) {
                    fail("Could not create the capture folder (${e.message})."); return
                }
            } else {
                manifest = manifest?.copy(objectTap = tap)
                manifest?.let { m -> capture?.let { c -> runIo { writeManifestQuietly(c.dir, m) } } }
            }
            shutter.arm()
        }
        _state.update {
            it.copy(phase = Phase.CAPTURING, tapX = x, tapY = y, notice = if (it.mode == Mode.SMART) Notice.HOLD_STEADY else Notice.MANUAL)
        }
    }

    /** Switches between the smart shutter and the manual button for this capture. */
    fun setMode(mode: Mode) {
        if (mode == Mode.SMART && !sensors.available) {
            _state.update { it.copy(notice = Notice.NO_SENSORS) }; return
        }
        synchronized(lock) { shutter.setPaused(false) }
        _state.update { it.copy(mode = mode, paused = false, notice = noticeFor(mode, armed = it.phase == Phase.CAPTURING)) }
    }

    fun togglePause() {
        val paused = !_state.value.paused
        synchronized(lock) { shutter.setPaused(paused) }
        _state.update { it.copy(paused = paused, notice = if (paused) Notice.PAUSED else Notice.KEEP_MOVING) }
    }

    /** The manual shutter button. Ignored while a photo is in flight (the button shows that). */
    fun manualShutter() {
        val o = sensors.latest
        val allowed = synchronized(lock) {
            !finishing && capture != null && shutter.tryManual(SystemClock.elapsedRealtimeNanos(), o)
        }
        if (allowed) takePhoto(o, manual = true)
    }

    /** Ends the capture and keeps it (after any photo in flight lands). */
    fun finish() = endCapture(discard = false)

    /** Ends the capture and deletes it. */
    fun discard() = endCapture(discard = true)

    // ---- sensors (sensor thread) ----

    private fun onSensorReading(ns: Long, o: DeviceOrientation, speed: Double) {
        if (_state.value.mode != Mode.SMART) return
        var hasPhotos = false
        val out = synchronized(lock) {
            if (capture == null || finishing) return
            hasPhotos = shutter.photoCount > 0
            shutter.onReading(ns, o, speed)
        }
        if (out.state == SmartShutter.State.FIRE) {
            main.post { takePhoto(o, manual = false) }
            return
        }
        // One short vibration when the ring fills: the cue to pause, without watching the screen. Once per photo
        // (re-armed when a photo finishes), so hovering at the threshold can't repeat it; not for the first photo.
        if (out.state == SmartShutter.State.HOLD_STEADY && !buzzedForNext && hasPhotos) {
            buzzedForNext = true
            main.post { haptic() }
        }
        // Publish the progress ring at most ~15 times a second.
        if (ns - lastProgressPublishNs < 66_000_000L && out.state == SmartShutter.State.MOVING) return
        lastProgressPublishNs = ns
        val notice = when (out.state) {
            SmartShutter.State.MOVING -> Notice.KEEP_MOVING
            SmartShutter.State.HOLD_STEADY -> Notice.HOLD_STEADY
            SmartShutter.State.SAVING -> Notice.SAVING_PHOTO
            SmartShutter.State.PAUSED -> Notice.PAUSED
            else -> null
        }
        _state.update { s ->
            val keep = s.notice == Notice.HOT || s.notice == Notice.LOW_STORAGE || s.notice == Notice.PHOTO_FAILED
            s.copy(progress = out.progress, notice = if (keep || notice == null) s.notice else notice)
        }
    }

    // ---- photos ----

    /**
     * Takes one full-resolution photo. Called only after [SmartShutter] allowed it ([SmartShutter.State.FIRE] or
     * [SmartShutter.tryManual]), so exactly one is ever in flight. Main thread.
     */
    private fun takePhoto(orientation: DeviceOrientation?, manual: Boolean) {
        val ic = imageCapture ?: run { synchronized(lock) { shutter.onPhotoFinished(false) }; return }
        val (c, index) = synchronized(lock) {
            val c = capture
            if (c == null || finishing) { shutter.onPhotoFinished(false); return }
            inFlight = true
            c to photoCount + 1
        }
        if (guards.freeBytes() < DeviceGuards.MIN_FREE_TO_CONTINUE) {
            synchronized(lock) { inFlight = false; shutter.onPhotoFinished(false); shutter.setPaused(true) }
            _state.update { it.copy(notice = Notice.LOW_STORAGE, paused = true) }
            finish()
            return
        }
        _state.update { it.copy(photoInFlight = true, notice = if (it.mode == Mode.SMART) Notice.SAVING_PHOTO else it.notice) }
        val file = File(c.dir, CaptureStore.imageName(index))
        val firedNs = SystemClock.elapsedRealtimeNanos()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(options, io, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val ok = try {
                    recordFrame(c, index, file, firedNs, orientation, manual)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "recording photo $index", e)
                    file.delete()
                    false
                }
                photoDone(ok, orientation)
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "photo $index failed: ${e.imageCaptureError}", e)
                file.delete()
                photoDone(false, orientation)
            }
        })
    }

    /** Appends the frame line and updates the manifest. io thread. */
    private fun recordFrame(c: StoredCapture, index: Int, file: File, firedNs: Long, o: DeviceOrientation?, manual: Boolean) {
        val exif = ExifInterface(file)
        val rotation = exif.rotationDegrees
        val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
        val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
        require(w > 0 && h > 0) { "photo size unknown" }
        val ci = cameraInfo
        val known = intrinsicsFor(w, h)
        if (known == null) Log.w(TAG, "no lens data from the camera: field of view assumed, reconstruction must self-calibrate")
        val k = known?.first ?: CameraGeometry.assumedIntrinsics(w, h)
        val source = known?.second ?: IntrinsicsSource.ASSUMED
        val record = FrameRecord(
            index = index,
            image = CaptureStore.imageName(index),
            timestampNs = firedNs,
            pose = null,
            intrinsics = k,
            tracking = null,
            exposureNs = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { (it * 1e9).toLong() },
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            manual = manual,
            orientation = o?.toSensorOrientation(),
            rotationDegrees = rotation,
        )
        store.appendFrame(c.dir, record)
        synchronized(lock) {
            photoCount = index
            o?.let { rings.add(it) }
            val m = manifest ?: return
            val updated = m.copy(
                frameCount = index,
                coverage = (rings.fraction(CoverageRings.Ring.LOWER) + rings.fraction(CoverageRings.Ring.UPPER)) / 2,
                updatedAt = System.currentTimeMillis(),
                camera = if (index == 1 && ci != null) ci.copy(imageWidth = w, imageHeight = h, intrinsicsSource = source) else m.camera,
            )
            manifest = updated
            store.writeManifest(c.dir, updated)
        }
        Log.d(TAG, "photo $index ${if (manual) "manual" else "smart"}: ${w}x$h rot $rotation, ${(SystemClock.elapsedRealtimeNanos() - firedNs) / 1_000_000} ms")
    }

    /** A photo finished (saved or not). io thread. */
    private fun photoDone(saved: Boolean, o: DeviceOrientation?) {
        buzzedForNext = false
        val endNow = synchronized(lock) {
            inFlight = false
            shutter.onPhotoFinished(saved)
            if (!saved) shutter.setPaused(true)   // never retry on its own
            finishing
        }
        _state.update { s ->
            s.copy(
                photoInFlight = false,
                photoCount = synchronized(lock) { photoCount },
                lowerRing = rings.cells(CoverageRings.Ring.LOWER),
                upperRing = rings.cells(CoverageRings.Ring.UPPER),
                flashCount = if (saved) s.flashCount + 1 else s.flashCount,
                tapX = if (saved) null else s.tapX, tapY = if (saved) null else s.tapY,
                paused = if (!saved) true else s.paused,
                notice = if (!saved) Notice.PHOTO_FAILED else if (s.mode == Mode.SMART) Notice.KEEP_MOVING else Notice.MANUAL,
            )
        }
        if (endNow) completeEnd()
    }

    // ---- ending ----

    private fun endCapture(discard: Boolean) {
        val wait = synchronized(lock) {
            if (finishing) return
            finishing = true
            discardOnFinish = discard
            inFlight
        }
        _state.update { it.copy(phase = Phase.SAVING) }
        if (!wait) runIo { completeEnd() }
    }

    /** Writes the final manifest (or deletes the capture). io thread. */
    private fun completeEnd() {
        val (c, m, discard) = synchronized(lock) {
            if (capture == null && _state.value.phase == Phase.SAVED) return
            Triple(capture, manifest, discardOnFinish)
        }
        var savedId: String? = null
        if (c != null) {
            try {
                if (discard || photoCount == 0) store.delete(c.dir)
                else {
                    val frames = store.readFrames(c.dir).size
                    m?.let { store.writeManifest(c.dir, it.copy(state = CaptureState.COMPLETE, frameCount = frames, updatedAt = System.currentTimeMillis())) }
                    savedId = c.id
                }
            } catch (e: Exception) {
                Log.e(TAG, "finishing", e)
            }
        }
        val stopIo = synchronized(lock) { capture = null; manifest = null; shutdownRequested }
        _state.update { it.copy(phase = Phase.SAVED, savedCaptureId = savedId) }
        if (stopIo) io.shutdown()   // queued work (none expected) still runs; nothing new is accepted
    }

    // ---- helpers ----

    /**
     * One short plain vibration (60 ms), marked as touch feedback so it follows the phone's touch-vibration setting.
     * A plain timed vibration works on every vibration motor; the earlier "confirm" haptic effect was silently dropped
     * by phones whose motor has no effect support (the Galaxy A17 logged it as ignored_unsupported). Main thread.
     */
    private fun haptic() {
        val vibrator = (if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }).takeIf { it.hasVibrator() } ?: return
        val effect = android.os.VibrationEffect.createOneShot(BUZZ_MS, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
    }

    private fun noticeFor(mode: Mode, armed: Boolean) = when {
        !armed -> Notice.TAP_OBJECT
        mode == Mode.SMART -> Notice.KEEP_MOVING
        else -> Notice.MANUAL
    }

    private fun onThermalChange() {
        val hot = guards.hot
        if (hot) synchronized(lock) { shutter.setPaused(true) }
        _state.update { it.copy(hot = hot, paused = it.paused || hot, notice = if (hot) Notice.HOT else if (it.notice == Notice.HOT) Notice.PAUSED else it.notice) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(phase = Phase.ERROR, errorMessage = message) }
    }

    private fun writeManifestQuietly(dir: File, m: CaptureManifest) {
        try { store.writeManifest(dir, m) } catch (e: Exception) { Log.e(TAG, "manifest", e) }
    }

    private fun newManifest(tap: ObjectTap): CaptureManifest {
        val now = System.currentTimeMillis()
        val ci = cameraInfo ?: CameraInfo("", 0, 0, null, null, null, IntrinsicsSource.ASSUMED)
        return CaptureManifest(
            id = "", name = "", createdAt = now, updatedAt = now, appVersion = appVersion,
            device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null, Build.VERSION.SDK_INT),
            camera = ci, arcoreVersion = null, target = null, frameCount = 0, coverage = 0.0,
            state = CaptureState.RECORDING, objectTap = tap,
        )
    }

    // Camera characteristics for intrinsics, read once when the camera is bound (main thread).
    private var activeW = 0
    private var activeH = 0
    private var pixelArrayW: Int? = null
    private var focalMm: Double? = null
    private var sensorWidthMm: Double? = null
    private var calibration: FloatArray? = null

    @SuppressLint("UnsafeOptInUsageError")
    private fun readCameraInfo(cam: Camera, ic: ImageCapture): CameraInfo {
        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val active = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        activeW = active?.width() ?: 0
        activeH = active?.height() ?: 0
        pixelArrayW = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width
        focalMm = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble()
        val physical = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        sensorWidthMm = physical?.width?.toDouble()
        calibration = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val res = ic.resolutionInfo?.resolution
        val source = intrinsicsFor(res?.width ?: 0, res?.height ?: 0)?.second ?: IntrinsicsSource.ASSUMED
        // Image size and source are confirmed from the first saved photo (resolution info can lag the binding).
        return CameraInfo(c2.cameraId, res?.width ?: 0, res?.height ?: 0, focalMm, sensorWidthMm, physical?.height?.toDouble(), source)
    }

    private fun intrinsicsFor(w: Int, h: Int): Pair<Intrinsics, IntrinsicsSource>? =
        CameraGeometry.intrinsics(w, h, activeW, activeH, pixelArrayW, focalMm, sensorWidthMm, calibration)

    companion object {
        private const val TAG = "CameraXCapture"
        const val PREFS = "capture"
        /** Smart (default) or manual shutter, as chosen in the capture settings. */
        const val KEY_MODE = "shutterMode"
        const val CELLS = 24
        /** Length of the "hold steady" buzz. */
        private const val BUZZ_MS = 60L
    }
}

/** The format's orientation type (the shutter's own type keeps the format package free of sensor code). */
internal fun DeviceOrientation.toSensorOrientation() = SensorOrientation(x, y, z, w)
