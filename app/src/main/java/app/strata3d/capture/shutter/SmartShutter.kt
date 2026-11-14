package app.strata3d.capture.shutter

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A unit quaternion (x, y, z, w) rotating phone (device) coordinates into the world frame of Android's
 * game-rotation-vector sensor (+z up, opposite gravity). Android's device axes: +x right, +y up along the screen,
 * +z out of the screen; the back camera looks along −z.
 */
data class DeviceOrientation(val x: Double, val y: Double, val z: Double, val w: Double) {

    /** Rotates the device-frame vector ([vx], [vy], [vz]) into the world frame. */
    fun rotate(vx: Double, vy: Double, vz: Double): DoubleArray {
        // v' = v + 2w(q×v) + 2 q×(q×v), q = (x, y, z)
        val cx = y * vz - z * vy
        val cy = z * vx - x * vz
        val cz = x * vy - y * vx
        val ccx = y * cz - z * cy
        val ccy = z * cx - x * cz
        val ccz = x * cy - y * cx
        return doubleArrayOf(vx + 2 * (w * cx + ccx), vy + 2 * (w * cy + ccy), vz + 2 * (w * cz + ccz))
    }

    /** Unit direction the back camera looks along, in the world frame. */
    fun cameraForward(): DoubleArray = rotate(0.0, 0.0, -1.0).normalized()

    companion object {
        /** From Android's rotation-vector quaternion, normalised (sensors report it slightly off unit length). */
        fun of(x: Double, y: Double, z: Double, w: Double): DeviceOrientation {
            val n = sqrt(x * x + y * y + z * z + w * w)
            require(n > 1e-9) { "zero quaternion" }
            return DeviceOrientation(x / n, y / n, z / n, w / n)
        }
    }
}

internal fun DoubleArray.normalized(): DoubleArray {
    val n = sqrt(this[0] * this[0] + this[1] * this[1] + this[2] * this[2])
    return if (n > 1e-12) doubleArrayOf(this[0] / n, this[1] / n, this[2] / n) else this
}

/** Angle in degrees between two unit vectors. */
internal fun angleDeg(a: DoubleArray, b: DoubleArray): Double =
    Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))

/**
 * Decides when the smart shutter takes a photo, from motion-sensor readings alone. It never touches the camera:
 * the capture engine asks it on every sensor reading and, on [State.FIRE], calls the same capture function the
 * manual button uses.
 *
 * A photo fires when all of these hold:
 * 1. The object has been marked ([arm]) and the shutter is not paused.
 * 2. No photo is in flight: the previous one has been reported saved or failed ([onPhotoFinished]). This also gates
 *    the manual button ([tryManual]), so the camera never gets a second request while one is being saved.
 * 3. The camera now looks in a direction at least [Settings.stepDeg] away from every photo taken so far. When the
 *    user circles the object while keeping it in frame, the camera's direction turns with them, so this spaces photos
 *    evenly around it. Twisting the phone about its own lens axis does not count, and walking back to a spot already
 *    covered does not fire again. The first photo needs no movement.
 * 4. The phone has been steady (rotation speed under [Settings.steadyRadPerS]) for [Settings.steadyMs], so the photo
 *    is sharp.
 * 5. At least [Settings.minIntervalMs] since the previous photo started.
 *
 * Not thread-safe: call from one thread (the sensor thread). Pure logic, unit-tested without a phone.
 */
class SmartShutter(private val settings: Settings = Settings()) {

    data class Settings(
        /** Smallest change in viewing direction between photos, in degrees. Provisional; tune on real captures. */
        val stepDeg: Double = 9.0,
        /** Rotation speed (rad/s, gyroscope magnitude) below which the phone counts as steady. */
        val steadyRadPerS: Double = 0.20,
        /** How long the phone must stay steady before a photo fires. */
        val steadyMs: Long = 150,
        /** Shortest time between the starts of two automatic photos. */
        val minIntervalMs: Long = 400,
    ) {
        init {
            require(stepDeg > 0 && steadyRadPerS > 0 && steadyMs >= 0 && minIntervalMs >= 0)
        }
    }

    enum class State {
        /** The object is not marked yet; no photo can be taken. */
        LOCKED,
        /** The user paused the smart shutter. */
        PAUSED,
        /** A photo is being taken or saved; nothing else fires until it finishes. */
        SAVING,
        /** Not far enough from the photos already taken; [Output.progress] tells how far toward the next one. */
        MOVING,
        /** Far enough, waiting for the phone to be steady. */
        HOLD_STEADY,
        /** Take a photo now. The shutter is already in [SAVING] until [onPhotoFinished]. */
        FIRE,
    }

    /** [progress] is 0..1 toward the next photo's viewing direction (1 in [State.HOLD_STEADY] and [State.FIRE]). */
    data class Output(val state: State, val progress: Double)

    private val taken = ArrayList<DoubleArray>()
    private var pending: DoubleArray? = null
    private var armed = false
    private var paused = false
    private var inFlight = false
    private var steadySinceNs = Long.MIN_VALUE
    private var lastStartNs = Long.MIN_VALUE

    /** Number of photos reported saved. */
    val photoCount: Int get() = taken.size

    /** Called once the object is marked: the shutter may now fire. */
    fun arm() { armed = true }

    fun setPaused(p: Boolean) { paused = p; steadySinceNs = Long.MIN_VALUE }

    /**
     * Feeds one sensor reading. [timestampNs] is the sensor timestamp (monotonic), [orientation] the latest game
     * rotation vector, [angularSpeedRadPerS] the latest gyroscope magnitude.
     */
    fun onReading(timestampNs: Long, orientation: DeviceOrientation, angularSpeedRadPerS: Double): Output {
        if (!armed) return Output(State.LOCKED, 0.0)
        if (inFlight) return Output(State.SAVING, 1.0)
        if (paused) return Output(State.PAUSED, 0.0)

        val forward = orientation.cameraForward()
        val nearest = taken.minOfOrNull { angleDeg(it, forward) }
        val progress = if (nearest == null) 1.0 else (nearest / settings.stepDeg).coerceIn(0.0, 1.0)

        if (angularSpeedRadPerS < settings.steadyRadPerS) {
            if (steadySinceNs == Long.MIN_VALUE) steadySinceNs = timestampNs
        } else {
            steadySinceNs = Long.MIN_VALUE
        }
        if (progress < 1.0) return Output(State.MOVING, progress)

        val steadyLongEnough = steadySinceNs != Long.MIN_VALUE &&
            timestampNs - steadySinceNs >= settings.steadyMs * 1_000_000
        val intervalOk = lastStartNs == Long.MIN_VALUE ||
            timestampNs - lastStartNs >= settings.minIntervalMs * 1_000_000
        if (!steadyLongEnough || !intervalOk) return Output(State.HOLD_STEADY, 1.0)

        start(timestampNs, forward)
        return Output(State.FIRE, 1.0)
    }

    /**
     * The manual button was pressed. Returns true if a photo may start now (the object is marked and no photo is in
     * flight); the shutter is then in [State.SAVING] until [onPhotoFinished]. Manual photos skip the spacing and
     * steadiness checks: the user decides.
     */
    fun tryManual(timestampNs: Long, orientation: DeviceOrientation?): Boolean {
        if (!armed || inFlight) return false
        start(timestampNs, orientation?.cameraForward())
        return true
    }

    /**
     * The photo started by [State.FIRE] or [tryManual] finished. Only a [saved] photo counts toward coverage; after a
     * failure, the same viewpoint stays open.
     */
    fun onPhotoFinished(saved: Boolean) {
        val dir = pending
        if (saved && dir != null) taken.add(dir)
        pending = null
        inFlight = false
        steadySinceNs = Long.MIN_VALUE
    }

    private fun start(timestampNs: Long, forward: DoubleArray?) {
        inFlight = true
        pending = forward
        lastStartNs = timestampNs
    }
}
