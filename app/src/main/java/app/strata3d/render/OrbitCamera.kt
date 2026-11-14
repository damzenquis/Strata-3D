package app.strata3d.render

import app.strata3d.model.BoundingBox
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** How the 3D scene is projected onto the screen. */
enum class Projection { PERSPECTIVE, ORTHOGRAPHIC }

/**
 * Standard camera directions. Viewer coordinate convention (see docs/ARCHITECTURE.md):
 * world **+Z is up**, **+X is right**, and **+Y points away** from the viewer in the Front view.
 */
enum class ViewPreset(internal val yawDeg: Float, internal val pitchDeg: Float) {
    /** Looking straight down −Z; screen up is +Y. */
    TOP(-90f, 90f),
    /** Looking along +Y from the −Y side; screen up is +Z. */
    FRONT(-90f, 0f),
    /** Right side: looking along −X from the +X side; screen up is +Z. */
    SIDE(0f, 0f),
    /** True isometric from the front-right-top octant (+X, −Y, +Z). */
    ISO(-45f, 35.264391f),
}

/**
 * Z-up orbit camera (STL / 3D-printing convention). Mutated from the UI thread by gestures and read from the
 * GL thread while rendering, so every public member is synchronised.
 *
 * The camera orbits [target] at [distance] with a yaw (around +Z, 0° = looking from +X) and a pitch (above the
 * XY plane, −90°…90°). In orthographic mode the visible half-height is `distance × tan(fov/2)`, the same size
 * the perspective frustum has at the target plane, so switching projection never changes the apparent scale
 * of the model at the orbit centre.
 *
 * Camera moves started by [applyPreset], [fit] and an animated [reset] run for [TRANSITION_NANOS] and are
 * advanced by [advance] from the render loop; any gesture cancels them where they are.
 *
 * Screen areas covered by floating controls are given with [setObstructedInsets]. Framing (reset, presets,
 * Fit) then fits the model into the unobstructed rectangle, and a lens shift (an off-centre projection)
 * centres the image there, so orbiting still turns around the model while nothing ends up under the controls.
 */
class OrbitCamera internal constructor(private val clock: () -> Long) {

    constructor() : this(System::nanoTime)

    private val target = floatArrayOf(0f, 0f, 0f)
    private var distance = 10f
    private var yawDeg = DEFAULT_YAW
    private var pitchDeg = DEFAULT_PITCH
    private var radius = 1f
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var bounds: BoundingBox? = null
    private var viewportKnown = false
    private var pendingFit = false

    var projection: Projection = Projection.PERSPECTIVE
        @Synchronized get
        @Synchronized set

    // Obstructed screen insets in pixels (left, top, right, bottom) and the resulting lens shift in NDC.
    private val insets = FloatArray(4)
    private var shiftX = 0f; private var shiftY = 0f
    private var shiftAnimating = false
    private var shiftStart = 0L
    private var fromShiftX = 0f; private var fromShiftY = 0f
    private var toShiftX = 0f; private var toShiftY = 0f

    // --- Transition state ---
    private var animating = false
    private var animStart = 0L
    private val fromTarget = FloatArray(3)
    private val toTarget = FloatArray(3)
    private var fromYaw = 0f; private var toYaw = 0f
    private var fromPitch = 0f; private var toPitch = 0f
    private var fromDistance = 1f; private var toDistance = 1f

    @Synchronized
    fun setViewport(width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        viewportKnown = true
        shiftX = targetShiftX(); shiftY = targetShiftY(); shiftAnimating = false
        if (pendingFit) {
            distance = fitDistance()
            pendingFit = false
        }
    }

    /** Centres the model and picks a distance that fits its bounding sphere on screen (no animation). */
    @Synchronized
    fun frame(box: BoundingBox?) {
        bounds = box
        reset(animated = false)
    }

    /** Returns to the default view: front-right, slightly above, whole bounding sphere visible. */
    @Synchronized
    fun reset(animated: Boolean = false) {
        val box = bounds
        val center = if (box != null) floatArrayOf(box.centerX, box.centerY, box.centerZ) else floatArrayOf(0f, 0f, 0f)
        radius = box?.radius ?: 1f
        if (!animated || !viewportKnown) {
            animating = false
            center.copyInto(target)
            yawDeg = DEFAULT_YAW
            pitchDeg = DEFAULT_PITCH
            distance = fitDistance()
            pendingFit = !viewportKnown
            return
        }
        startTransition(center, DEFAULT_YAW, DEFAULT_PITCH, fitDistance())
    }

    /** Animates to a standard view, centred on the model and fitted to its bounds for that direction. */
    @Synchronized
    fun applyPreset(preset: ViewPreset) {
        val box = bounds ?: return
        startTransition(
            floatArrayOf(box.centerX, box.centerY, box.centerZ),
            preset.yawDeg, preset.pitchDeg,
            fitDistanceFor(box, preset.yawDeg, preset.pitchDeg),
        )
    }

    /** Animates to frame the complete model bounds in the current direction and projection. */
    @Synchronized
    fun fit() {
        val box = bounds ?: return
        startTransition(floatArrayOf(box.centerX, box.centerY, box.centerZ), yawDeg, pitchDeg, fitDistanceFor(box, yawDeg, pitchDeg))
    }

    /**
     * Declares screen areas (in pixels) that floating controls cover. The visible image re-centres smoothly in
     * the remaining rectangle; later framing operations fit the model inside it.
     */
    @Synchronized
    fun setObstructedInsets(left: Float, top: Float, right: Float, bottom: Float) {
        if (insets[0] == left && insets[1] == top && insets[2] == right && insets[3] == bottom) return
        insets[0] = left; insets[1] = top; insets[2] = right; insets[3] = bottom
        if (!viewportKnown) {
            shiftX = targetShiftX(); shiftY = targetShiftY()
            return
        }
        fromShiftX = shiftX; fromShiftY = shiftY
        toShiftX = targetShiftX(); toShiftY = targetShiftY()
        shiftStart = clock()
        shiftAnimating = true
    }

    private fun targetShiftX(): Float = (insets[0] - insets[2]) / viewportWidth
    private fun targetShiftY(): Float = (insets[3] - insets[1]) / viewportHeight

    /** Fraction of the viewport width / height that is unobstructed (never below 25%). */
    private fun visibleWidthFraction(): Float = ((viewportWidth - insets[0] - insets[2]) / viewportWidth).coerceIn(0.25f, 1f)
    private fun visibleHeightFraction(): Float = ((viewportHeight - insets[1] - insets[3]) / viewportHeight).coerceIn(0.25f, 1f)

    @Synchronized
    fun cancelAnimation() {
        animating = false
    }

    @get:Synchronized
    val isAnimating: Boolean get() = animating || shiftAnimating

    /**
     * Advances a running transition to the current time. Returns true while the transition is still in
     * progress (the caller should draw another frame).
     */
    @Synchronized
    fun advance(): Boolean {
        if (shiftAnimating) {
            val t = ((clock() - shiftStart).toDouble() / TRANSITION_NANOS).coerceIn(0.0, 1.0).toFloat()
            val e = 1f - (1f - t) * (1f - t) * (1f - t)
            shiftX = fromShiftX + (toShiftX - fromShiftX) * e
            shiftY = fromShiftY + (toShiftY - fromShiftY) * e
            if (t >= 1f) shiftAnimating = false
        }
        if (!animating) return shiftAnimating
        val t = ((clock() - animStart).toDouble() / TRANSITION_NANOS).coerceIn(0.0, 1.0).toFloat()
        val e = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
        for (i in 0..2) target[i] = fromTarget[i] + (toTarget[i] - fromTarget[i]) * e
        yawDeg = fromYaw + (toYaw - fromYaw) * e
        pitchDeg = fromPitch + (toPitch - fromPitch) * e
        // Interpolate distance geometrically so zoom feels uniform.
        distance = exp(ln(fromDistance) + (ln(toDistance) - ln(fromDistance)) * e)
        if (t >= 1f) animating = false
        return animating || shiftAnimating
    }

    private fun startTransition(newTarget: FloatArray, newYaw: Float, newPitch: Float, newDistance: Float) {
        target.copyInto(fromTarget)
        newTarget.copyInto(toTarget)
        fromYaw = yawDeg
        // Take the shortest way round.
        var delta = (newYaw - yawDeg) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        toYaw = yawDeg + delta
        fromPitch = pitchDeg
        toPitch = newPitch
        fromDistance = max(distance, MIN_DISTANCE)
        toDistance = max(newDistance, MIN_DISTANCE)
        animStart = clock()
        animating = true
    }

    @Synchronized
    fun rotate(dxPx: Float, dyPx: Float, density: Float) {
        animating = false
        val degreesPerPx = ROTATE_DEG_PER_DP / density
        yawDeg -= dxPx * degreesPerPx
        pitchDeg = (pitchDeg + dyPx * degreesPerPx).coerceIn(-90f, 90f)
    }

    @Synchronized
    fun pan(dxPx: Float, dyPx: Float) {
        animating = false
        val worldPerPx = 2f * halfHeight() / viewportHeight
        val (right, up) = rightAndUp()
        for (i in 0..2) {
            target[i] += (-right[i] * dxPx + up[i] * dyPx) * worldPerPx
        }
    }

    @Synchronized
    fun zoom(scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        animating = false
        distance = (distance / scaleFactor).coerceIn(radius * 0.05f, radius * 50f)
    }

    /** Writes the view and projection matrices and returns the camera eye position in [eyeOut]. */
    @Synchronized
    fun computeMatrices(view: FloatArray, projectionOut: FloatArray, eyeOut: FloatArray) {
        computeMatrices(view, projectionOut, eyeOut, viewportWidth.toFloat() / viewportHeight)
    }

    /** As above, for an explicit [aspect] ratio (used by off-screen rendering). */
    @Synchronized
    fun computeMatrices(view: FloatArray, projectionOut: FloatArray, eyeOut: FloatArray, aspect: Float) {
        val eye = eyePosition()
        Mat4.lookAt(view, eye, target, cameraUp())
        if (projection == Projection.PERSPECTIVE) {
            val near = max(distance - radius * 4f, distance * 0.01f)
            val far = distance + radius * 20f
            Mat4.perspective(projectionOut, FOV_Y_DEG.toFloat(), aspect, near, far)
        } else {
            val h = halfHeight()
            // Depth range around the target that always contains the model and its grid, even when the
            // eye sits inside the model's extent (orthographic allows a negative near plane).
            val span = radius * 3f + offsetFromBounds()
            Mat4.ortho(projectionOut, -h * aspect, h * aspect, -h, h, distance - span, distance + span)
        }
        if (shiftX != 0f || shiftY != 0f) {
            // Lens shift: x' = x + shiftX·w, y' = y + shiftY·w (moves the image without changing perspective).
            for (c in 0 until 4) {
                projectionOut[c * 4] += shiftX * projectionOut[c * 4 + 3]
                projectionOut[c * 4 + 1] += shiftY * projectionOut[c * 4 + 3]
            }
        }
        eye.copyInto(eyeOut)
    }

    /** Pixel size of the unobstructed rectangle: what a snapshot at scale 1 contains. */
    @Synchronized
    fun visibleSizePx(): IntArray = intArrayOf(
        Math.round(viewportWidth * visibleWidthFraction()),
        Math.round(viewportHeight * visibleHeightFraction()),
    )

    /**
     * Matrices for an off-screen image of exactly the unobstructed rectangle, at any resolution with the same
     * aspect ratio. The lens shift is dropped and the projection is scaled so that the visible rectangle fills
     * the image: the result matches what is on screen, without the controls.
     */
    @Synchronized
    fun computeSnapshotMatrices(view: FloatArray, projectionOut: FloatArray, eyeOut: FloatArray) {
        val savedX = shiftX; val savedY = shiftY
        shiftX = 0f; shiftY = 0f
        try {
            computeMatrices(view, projectionOut, eyeOut, viewportWidth.toFloat() / viewportHeight)
        } finally {
            shiftX = savedX; shiftY = savedY
        }
        val sx = 1f / visibleWidthFraction()
        val sy = 1f / visibleHeightFraction()
        for (c in 0 until 4) {
            projectionOut[c * 4] *= sx
            projectionOut[c * 4 + 1] *= sy
        }
    }

    /** Half the visible height at the target plane; identical in both projections. */
    private fun halfHeight(): Float = distance * tan(Math.toRadians(FOV_Y_DEG / 2.0)).toFloat()

    private fun offsetFromBounds(): Float {
        val b = bounds ?: return 0f
        val dx = target[0] - b.centerX; val dy = target[1] - b.centerY; val dz = target[2] - b.centerZ
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun eyePosition(): FloatArray {
        val d = direction(yawDeg, pitchDeg)
        return floatArrayOf(target[0] + d[0] * distance, target[1] + d[1] * distance, target[2] + d[2] * distance)
    }

    /** Unit vector from the target towards the eye. */
    private fun direction(yaw: Float, pitch: Float): FloatArray {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        val cp = cos(p)
        return floatArrayOf((cp * cos(y)).toFloat(), (cp * sin(y)).toFloat(), sin(p).toFloat())
    }

    /**
     * Screen-up vector: the derivative of the eye direction with respect to pitch. It equals the classic +Z-up
     * look-at result for any pitch below 90°, and stays well defined looking straight down or up.
     */
    private fun cameraUp(yaw: Float = yawDeg, pitch: Float = pitchDeg): FloatArray {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        val sp = sin(p)
        return floatArrayOf((-sp * cos(y)).toFloat(), (-sp * sin(y)).toFloat(), cos(p).toFloat())
    }

    private fun rightAndUp(yaw: Float = yawDeg, pitch: Float = pitchDeg): Pair<FloatArray, FloatArray> {
        val toEye = direction(yaw, pitch)
        val f = floatArrayOf(-toEye[0], -toEye[1], -toEye[2])
        val up = cameraUp(yaw, pitch)
        val right = normalize(cross(f, up))
        return right to up
    }

    /** Distance that fits the bounding sphere (used by reset and on load, unchanged from earlier versions). */
    private fun fitDistance(): Float {
        val aspect = viewportWidth.toFloat() / viewportHeight
        val tanFull = tan(Math.toRadians(FOV_Y_DEG / 2.0))
        val halfFovY = atan(tanFull * visibleHeightFraction())
        val halfFovX = atan(tanFull * aspect * visibleWidthFraction())
        val halfFov = min(halfFovY, halfFovX)
        return (radius / sin(halfFov)).toFloat() * 1.1f
    }

    /**
     * Distance that fits all eight corners of [box] (the complete, already-transformed model bounds) when
     * looking from ([yaw], [pitch]) at the box centre, for the current projection, with a small margin.
     */
    private fun fitDistanceFor(box: BoundingBox, yaw: Float, pitch: Float): Float {
        val aspect = viewportWidth.toFloat() / viewportHeight
        val tanFull = tan(Math.toRadians(FOV_Y_DEG / 2.0)).toFloat()
        // Only the unobstructed part of the screen counts.
        val tanY = tanFull * visibleHeightFraction()
        val tanX = tanFull * aspect * visibleWidthFraction()
        val toEye = direction(yaw, pitch)
        val (right, up) = rightAndUp(yaw, pitch)
        var maxX = 0f; var maxY = 0f
        var perspective = 0f
        var maxTowardEye = 0f
        for (i in 0 until 8) {
            val cx = (if (i and 1 == 0) box.minX else box.maxX) - box.centerX
            val cy = (if (i and 2 == 0) box.minY else box.maxY) - box.centerY
            val cz = (if (i and 4 == 0) box.minZ else box.maxZ) - box.centerZ
            val x = abs(cx * right[0] + cy * right[1] + cz * right[2]) * FIT_MARGIN
            val y = abs(cx * up[0] + cy * up[1] + cz * up[2]) * FIT_MARGIN
            val z = cx * toEye[0] + cy * toEye[1] + cz * toEye[2] // positive = closer to the eye
            maxX = max(maxX, x); maxY = max(maxY, y)
            maxTowardEye = max(maxTowardEye, z)
            // A corner at depth (d − z) is inside the frustum when |x| ≤ (d − z)·tanX and |y| ≤ (d − z)·tanY.
            perspective = max(perspective, max(x / tanX, y / tanY) + z)
        }
        val fitted = if (projection == Projection.PERSPECTIVE) {
            max(perspective, maxTowardEye * 1.05f + radius * 0.01f)
        } else {
            max(maxY / visibleHeightFraction(), maxX / (aspect * visibleWidthFraction())) / tanFull
        }
        return fitted.coerceIn(max(radius * 0.05f, MIN_DISTANCE), max(radius * 50f, MIN_DISTANCE))
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (len > 0f) floatArrayOf(v[0] / len, v[1] / len, v[2] / len) else v
    }

    /**
     * Unprojects screen coordinates [screenX], [screenY] (in pixels) into a 3D [Ray] in world coordinates.
     * Perspective rays start at the eye; orthographic rays start on the near plane and all share the view
     * direction.
     */
    @Synchronized
    fun unprojectRay(screenX: Float, screenY: Float): Ray {
        val view = FloatArray(16)
        val proj = FloatArray(16)
        val eye = FloatArray(3)
        computeMatrices(view, proj, eye)

        val vp = FloatArray(16)
        val invVp = FloatArray(16)
        Mat4.multiply(vp, proj, view)
        if (!Mat4.invert(invVp, vp)) {
            val forward = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
            return Ray(eye[0], eye[1], eye[2], forward[0], forward[1], forward[2])
        }

        val ndcX = (2f * screenX / viewportWidth) - 1f
        val ndcY = 1f - (2f * screenY / viewportHeight)

        val nearPoint = FloatArray(4)
        val farPoint = FloatArray(4)
        Mat4.multiplyVec(nearPoint, invVp, floatArrayOf(ndcX, ndcY, -1f, 1f))
        Mat4.multiplyVec(farPoint, invVp, floatArrayOf(ndcX, ndcY, 1f, 1f))

        val nx = nearPoint[0] / nearPoint[3]
        val ny = nearPoint[1] / nearPoint[3]
        val nz = nearPoint[2] / nearPoint[3]

        val fx = farPoint[0] / farPoint[3]
        val fy = farPoint[1] / farPoint[3]
        val fz = farPoint[2] / farPoint[3]

        val dx = fx - nx
        val dy = fy - ny
        val dz = fz - nz
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val invLen = if (len > 0f) 1f / len else 1f

        return if (projection == Projection.ORTHOGRAPHIC) {
            Ray(nx, ny, nz, dx * invLen, dy * invLen, dz * invLen)
        } else {
            Ray(eye[0], eye[1], eye[2], dx * invLen, dy * invLen, dz * invLen)
        }
    }

    /** A copy of the current camera state for picking off the UI thread (see [MeasurePicker]). */
    @Synchronized
    fun pickFrame(): PickFrame {
        val view = FloatArray(16)
        val proj = FloatArray(16)
        val eye = FloatArray(3)
        computeMatrices(view, proj, eye)
        val vp = FloatArray(16)
        Mat4.multiply(vp, proj, view)
        val forward = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        return PickFrame(vp, viewportWidth, viewportHeight, projection == Projection.ORTHOGRAPHIC, eye, forward)
    }

    /**
     * Projects a 3D world coordinate [x, y, z] to screen space pixels [screenX, screenY].
     * Returns null if the point is behind the camera.
     */
    @Synchronized
    fun projectToScreen(x: Float, y: Float, z: Float): FloatArray? {
        val view = FloatArray(16)
        val proj = FloatArray(16)
        val eye = FloatArray(3)
        computeMatrices(view, proj, eye)

        val mvp = FloatArray(16)
        Mat4.multiply(mvp, proj, view)

        val clip = FloatArray(4)
        Mat4.multiplyVec(clip, mvp, floatArrayOf(x, y, z, 1f))
        if (clip[3] <= 0f) return null

        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]

        val screenX = (ndcX + 1f) * 0.5f * viewportWidth
        val screenY = (1f - ndcY) * 0.5f * viewportHeight
        return floatArrayOf(screenX, screenY)
    }

    internal companion object {
        const val FOV_Y_DEG = 40.0
        /** Looking from the front-right (+X, -Y), slightly above: the usual "slicer" default view. */
        const val DEFAULT_YAW = -60f
        const val DEFAULT_PITCH = 25f
        const val ROTATE_DEG_PER_DP = 0.45f
        /** Duration of preset / fit / animated-reset transitions. */
        const val TRANSITION_NANOS = 250_000_000L
        /** Fit leaves 8% breathing room around the model. */
        const val FIT_MARGIN = 1.08f
        private const val MIN_DISTANCE = 1e-6f
    }
}
