package app.strata3d.capture.format

/**
 * Data model of a capture dataset, format "strata3d-capture" (see docs/CAPTURE_FORMAT.md).
 *
 * - Version 1 (ARCore capture, no longer written): every photo has an ARCore camera pose and tracking state.
 * - Version 2 (CameraX capture): no poses (reconstruction computes them from the photos). Each photo has the phone's
 *   orientation from the motion sensors, and the capture has the user's tap on the object.
 *
 * A capture is a folder holding `manifest.json`, `frames.jsonl` (one [FrameRecord] per line, appended as photos
 * land), `images/` (full-resolution JPEGs in sensor orientation) and, when the phone supports it, `depth/`.
 * Everything here is plain Kotlin so it can be unit-tested and reused by reconstruction later.
 */

/** A 3D vector in metres (ARCore world space unless stated otherwise). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = kotlin.math.sqrt(dot(this))
    fun normalized(): Vec3 { val l = length(); return if (l > 1e-12) this * (1.0 / l) else this }

    companion object { val ZERO = Vec3(0.0, 0.0, 0.0) }
}

/**
 * A rigid pose: translation plus unit quaternion (x, y, z, w). In a capture it is always the
 * **camera-to-world** transform in ARCore's world frame (metres, +y up), with ARCore's camera axes:
 * +x right and +y up in the sensor image, looking down −z (the OpenGL convention).
 */
data class Pose(
    val tx: Double, val ty: Double, val tz: Double,
    val qx: Double, val qy: Double, val qz: Double, val qw: Double,
) {
    val position: Vec3 get() = Vec3(tx, ty, tz)

    companion object { val IDENTITY = Pose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0) }
}

/** Pinhole intrinsics in pixels for an image of [width] × [height]; pixel (0, 0) is the top-left pixel's centre. */
data class Intrinsics(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val width: Int, val height: Int,
) {
    /**
     * The same camera described for an image of [newWidth] × [newHeight] that shows the same field of view, scaled
     * uniformly and centre-cropped if the aspect ratio differs (how Android derives stream sizes from the sensor).
     */
    fun scaledTo(newWidth: Int, newHeight: Int): Intrinsics {
        val s = maxOf(newWidth.toDouble() / width, newHeight.toDouble() / height)
        val cropX = (width * s - newWidth) / 2.0
        val cropY = (height * s - newHeight) / 2.0
        return Intrinsics(fx * s, fy * s, cx * s - cropX, cy * s - cropY, newWidth, newHeight)
    }
}

/** Where a frame's intrinsics came from. Reconstruction refines them either way. */
enum class IntrinsicsSource(val id: String) {
    /** ARCore's CPU-image intrinsics scaled to the photo size (version 1 captures). */
    ARCORE_SCALED("arcore_scaled"),
    /** Camera2 LENS_INTRINSIC_CALIBRATION, where the phone publishes it. */
    CAMERA2("camera2"),
    /** From the lens focal length and the physical sensor size (Camera2 characteristics); principal point centred. */
    FOCAL_LENGTH("focal_length"),
    /** The camera published no lens data: a typical field of view was assumed. Reconstruction must self-calibrate. */
    ASSUMED("assumed");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: ARCORE_SCALED }
}

/** ARCore's tracking state for the frame a photo was taken from. */
enum class Tracking(val id: String) {
    TRACKING("tracking"), PAUSED("paused"), STOPPED("stopped");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: STOPPED }
}

/** ARCore raw depth saved next to a photo: uint16 millimetres (little-endian) plus optional 8-bit confidence. */
data class DepthRef(val file: String, val confidenceFile: String?, val width: Int, val height: Int)

/**
 * The phone's orientation as a unit quaternion (x, y, z, w) rotating device coordinates into the world frame of
 * Android's game rotation vector sensor: +z points up (against gravity); the heading (rotation about +z) is relative
 * and drifts slowly, since no compass is used. Device axes: +x right, +y up along the screen, +z out of the screen;
 * the back camera looks along -z.
 */
data class SensorOrientation(val x: Double, val y: Double, val z: Double, val w: Double) {
    /** World "down" (the gravity direction) expressed in device coordinates. */
    fun downInDevice(): Vec3 {
        // Rotate (0, 0, -1) by the inverse (conjugate) quaternion.
        val qx = -x; val qy = -y; val qz = -z
        val vx = 0.0; val vy = 0.0; val vz = -1.0
        val cx = qy * vz - qz * vy; val cy = qz * vx - qx * vz; val cz = qx * vy - qy * vx
        val ccx = qy * cz - qz * cy; val ccy = qz * cx - qx * cz; val ccz = qx * cy - qy * cx
        return Vec3(vx + 2 * (w * cx + ccx), vy + 2 * (w * cy + ccy), vz + 2 * (w * cz + ccz))
    }
}

/**
 * Where the user tapped the object before the first photo: ([u], [v]) in 0..1 across the **upright** camera image
 * (as shown on screen; origin top-left), with the phone's [orientation] at that moment (null if the phone has no
 * rotation sensor). Reconstruction turns it into a ray to find the object among the SfM points.
 */
data class ObjectTap(val u: Double, val v: Double, val timestampNs: Long, val orientation: SensorOrientation?)

/** One photo of a capture. */
data class FrameRecord(
    val index: Int,
    /** Path of the JPEG relative to the capture folder, for example `images/000001.jpg`. */
    val image: String,
    /** Camera sensor timestamp (ns), the same clock as ARCore's frame timestamps. */
    val timestampNs: Long,
    /** Camera-to-world pose (version 1, ARCore), or null: version 2 never has one. */
    val pose: Pose?,
    /** For the image as stored (before [rotationDegrees] is applied). */
    val intrinsics: Intrinsics,
    /** ARCore tracking state (version 1 only). */
    val tracking: Tracking?,
    val exposureNs: Long? = null,
    val iso: Int? = null,
    /** Focus distance in diopters (1/m) as reported by Camera2; 0 means infinity. */
    val focusDiopters: Double? = null,
    /** Variance of the Laplacian on a sub-sampled luma grid; higher is sharper. Comparable within one capture. */
    val sharpness: Double = 0.0,
    /** True when the user added the photo by hand rather than the automatic shutter. */
    val manual: Boolean = false,
    val depth: DepthRef? = null,
    /** Phone orientation when the photo was taken (version 2), or null when the phone has no rotation sensor. */
    val orientation: SensorOrientation? = null,
    /** Clockwise rotation (0, 90, 180, 270) that shows the stored image upright; the same as its EXIF orientation. */
    val rotationDegrees: Int = 0,
)

/** How the object marker was placed. */
enum class Placement(val id: String) {
    /** On a detected surface: [CaptureTarget.base] is where the object stands, the centre is one radius above it. */
    SURFACE("surface"),
    /** Estimated from two taps on the same feature from two views; the centre is that (estimated) point. */
    TWO_VIEW("two_view");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: TWO_VIEW }
}

/**
 * The object being captured: the centre and radius (metres) of the bubble around it. For [Placement.SURFACE] the
 * [base] is the point on the supporting surface (centre = base + radius along world up); otherwise it is null.
 */
data class CaptureTarget(
    val center: Vec3,
    val radius: Double,
    val base: Vec3? = null,
    val placement: Placement = Placement.TWO_VIEW,
) {
    companion object {
        /** A bubble resting on a surface at [base]. */
        fun onSurface(base: Vec3, radius: Double) = CaptureTarget(base + Vec3(0.0, radius, 0.0), radius, base, Placement.SURFACE)
    }

    /** The same target with another radius (a surface bubble keeps its base and grows upwards). */
    fun withRadius(r: Double) = if (base != null) onSurface(base, r) else copy(radius = r)
}

enum class CaptureState(val id: String) {
    /** The capture screen is (or was, if the app died) writing photos. */
    RECORDING("recording"),
    /** The user finished the capture. */
    COMPLETE("complete"),
    /** Recording ended unexpectedly; the photos written so far are kept. */
    INTERRUPTED("interrupted");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: INTERRUPTED }
}

data class DeviceInfo(val manufacturer: String, val model: String, val soc: String?, val sdkInt: Int)

data class CameraInfo(
    val cameraId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val focalLengthMm: Double?,
    val sensorWidthMm: Double?,
    val sensorHeightMm: Double?,
    val intrinsicsSource: IntrinsicsSource,
)

/** Everything about a capture except its frames. Written atomically whenever it changes. */
data class CaptureManifest(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val appVersion: String,
    val device: DeviceInfo,
    val camera: CameraInfo,
    /** Version 1 only. */
    val arcoreVersion: String?,
    /** Version 1 only: the ARCore bubble around the object. */
    val target: CaptureTarget?,
    val frameCount: Int,
    /** Fraction (0..1) of the coverage cells that have at least one photo (v1: dome, v2: both rings averaged). */
    val coverage: Double,
    val state: CaptureState,
    val version: Int = FORMAT_VERSION,
    /** Version 2: the user's tap on the object before the first photo. */
    val objectTap: ObjectTap? = null,
) {
    companion object {
        const val FORMAT = "strata3d-capture"
        const val FORMAT_VERSION = 2
    }
}
