package app.strata3d.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.EGLExt
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import app.strata3d.model.Mesh
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * GLSurfaceView hosting [ModelRenderer] with touch controls:
 * one finger orbits, two fingers pan, pinch zooms, double tap resets the view.
 */
@SuppressLint("ViewConstructor")
class ModelSurfaceView(context: Context) : GLSurfaceView(context) {

    private val camera = OrbitCamera()
    private val renderer = ModelRenderer(camera)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentMesh: Mesh? = null

    /** Called on the main thread when rendering fails (e.g. GPU out of memory). */
    var onRenderError: ((String) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointerCount = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetCamera()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        renderer.onError = { message -> mainHandler.post { onRenderError?.invoke(message) } }
        renderer.onNeedsFrame = { requestRender() }
        renderer.onStencilSupport = { supported -> mainHandler.post { onStencilSupport?.invoke(supported) } }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: Mesh?) {
        if (mesh === currentMesh) return
        currentMesh = mesh
        camera.frame(mesh?.bounds)
        renderer.setMesh(mesh)
        requestRender()
    }

    fun setPhotoColours(on: Boolean) {
        if (renderer.photoColours == on) return
        renderer.photoColours = on
        requestRender()
    }

    fun setDisplayMode(mode: RenderMode) {
        if (renderer.renderMode == mode) return
        renderer.renderMode = mode
        requestRender()
    }

    fun setShowGrid(show: Boolean) {
        if (renderer.showGrid == show) return
        renderer.showGrid = show
        requestRender()
    }

    var isMeasureMode: Boolean = false
    /** The section as last set, so measurement picking sees exactly what is drawn. */
    @Volatile private var pickSection: SectionPlane? = null
    @Volatile private var pickFill = false
    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    /** Snap measurement taps to visible vertices within [SNAP_RADIUS_DP] of the finger. */
    var snapToVertices: Boolean = true
    /** A measurement point was picked: its world position and whether it is a snapped vertex. */
    var onPointPicked: ((FloatArray, Boolean) -> Unit)? = null

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isDragging = false
    private val pickExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RaycastPicker").apply { isDaemon = true }
    }

    fun setColors(model: FloatArray, background: FloatArray, darkBackground: Boolean) {
        renderer.modelColor = model
        renderer.backgroundColor = background
        renderer.darkBackground = darkBackground
        requestRender()
    }

    fun setMeasurementPoints(p1: FloatArray?, p2: FloatArray?, snapped1: Boolean = false, snapped2: Boolean = false) {
        renderer.setMeasurement(p1, p2, snapped1, snapped2)
        requestRender()
    }

    /** [fill]: fill the cut face when the device supports it (see [SectionCap]). */
    fun setSection(plane: SectionPlane?, fill: Boolean) {
        pickSection = plane
        pickFill = fill
        renderer.setSection(plane, fill)
        requestRender()
    }

    /** Told on the main thread, once the GL surface exists, whether section cuts can be filled on this device. */
    var onStencilSupport: ((Boolean) -> Unit)? = null

    fun setDiagnosticOverlay(overlay: app.strata3d.diagnostics.DiagnosticOverlay?) {
        renderer.setDiagnosticOverlay(overlay)
        requestRender()
    }

    /** Animated return to the default view (double-tap). */
    fun resetCamera() {
        camera.reset(animated = true)
        requestRender()
    }

    fun setProjection(projection: Projection) {
        if (camera.projection == projection) return
        camera.projection = projection
        requestRender()
    }

    fun applyPreset(preset: ViewPreset) {
        camera.applyPreset(preset)
        requestRender()
    }

    fun fitView() {
        camera.fit()
        requestRender()
    }

    /** GPU size limits, or null until the GL surface exists. */
    val gpuLimits: GpuLimits? get() = renderer.gpuLimits

    /** Pixel size of the unobstructed part of the view (a snapshot at 1x scale). */
    fun visibleSizePx(): IntArray = camera.visibleSizePx()

    /**
     * Renders [request] off-screen on the GL thread and delivers the pixels (or the failure) on the main thread.
     * The on-screen image is not affected.
     */
    fun captureSnapshot(request: SnapshotRequest, onResult: (Result<SnapshotPixels>) -> Unit) {
        queueEvent {
            val result = try {
                Result.success(renderer.renderSnapshot(request))
            } catch (e: SnapshotException) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                Result.failure(SnapshotException("Not enough memory for a ${request.width} × ${request.height} image"))
            } catch (e: RuntimeException) {
                Result.failure(SnapshotException("The image could not be rendered"))
            }
            mainHandler.post { onResult(result) }
        }
    }

    /** Screen areas (pixels) covered by floating controls; framing avoids them. */
    fun setObstructedInsets(left: Float, top: Float, right: Float, bottom: Float) {
        camera.setObstructedInsets(left, top, right, bottom)
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A new touch interrupts a running camera transition. This must happen before the detectors run:
        // a double-tap starts its own (reset) transition on this same ACTION_DOWN.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) camera.cancelAnimation()
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Use the centroid of all pointers so adding/removing a finger does not cause a jump.
        val pointerUp = event.actionMasked == MotionEvent.ACTION_POINTER_UP
        val skipIndex = if (pointerUp) event.actionIndex else -1
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) return true
        val x = sumX / count
        val y = sumY / count

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                isDragging = false
                lastX = x; lastY = y; lastPointerCount = count
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                isDragging = true
                lastX = x; lastY = y; lastPointerCount = count
            }
            MotionEvent.ACTION_MOVE -> {
                val moveDx = event.x - downX
                val moveDy = event.y - downY
                if (moveDx * moveDx + moveDy * moveDy > touchSlop * touchSlop) {
                    isDragging = true
                }
                if (count != lastPointerCount) {
                    lastX = x; lastY = y; lastPointerCount = count
                    return true
                }
                val dx = x - lastX
                val dy = y - lastY
                lastX = x; lastY = y
                if (count == 1) {
                    camera.rotate(dx, dy, resources.displayMetrics.density)
                } else {
                    camera.pan(dx, dy)
                }
                requestRender()
            }
            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - downTime
                val moveDx = event.x - downX
                val moveDy = event.y - downY
                val isTap = !isDragging && (moveDx * moveDx + moveDy * moveDy <= touchSlop * touchSlop) && duration < 300
                if (isTap && isMeasureMode) {
                    val tapX = event.x
                    val tapY = event.y
                    val mesh = currentMesh
                    if (mesh != null) {
                        val ray = camera.unprojectRay(tapX, tapY)
                        val frame = camera.pickFrame()
                        val snap = snapToVertices
                        val section = pickSection
                        // The cut is filled on screen exactly under these conditions (see ModelRenderer.drawFrame).
                        val capped = section != null && pickFill && renderer.hasStencil && renderer.renderMode != RenderMode.WIREFRAME
                        val radiusPx = SNAP_RADIUS_DP * resources.displayMetrics.density
                        pickExecutor.execute {
                            val start = System.nanoTime()
                            val pick = MeasurePicker.pick(mesh, frame, ray, tapX, tapY, radiusPx, snap, section, capped)
                            if (debuggable) {
                                android.util.Log.d(
                                    "StrataPick",
                                    "pick ${mesh.triangleCount} tris snap=$snap -> ${pick?.snapped} in " +
                                        "${(System.nanoTime() - start) / 1_000_000} ms",
                                )
                            }
                            if (pick != null) {
                                mainHandler.post { onPointPicked?.invoke(pick.point, pick.snapped) }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private companion object {
        /** Finger-sized snapping radius: a vertex this close to the tap on screen is preferred. */
        const val SNAP_RADIUS_DP = 18f
    }

    /**
     * Prefers 4× MSAA plus an 8-bit stencil buffer (used to fill section cuts), then drops the stencil, then MSAA,
     * so every ES 3.0 device still gets a working config. Section capping is simply unavailable without a stencil.
     */
    private class MultisampleConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es3 = EGLExt.EGL_OPENGL_ES3_BIT_KHR
            fun rgb(vararg extra: Int) = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, es3, *extra, EGL10.EGL_NONE,
            )
            val stencil = intArrayOf(EGL10.EGL_STENCIL_SIZE, 8)
            val msaa = intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4)
            val candidates = listOf(
                rgb(*(msaa + stencil)),
                rgb(*msaa),
                rgb(*stencil),
                rgb(),
                intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
            )
            for (attributes in candidates) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (egl.eglChooseConfig(display, attributes, configs, 1, count) && count[0] > 0) {
                    configs[0]?.let { return it }
                }
            }
            throw IllegalStateException("No OpenGL ES 3.0 compatible EGL config found")
        }
    }
}
