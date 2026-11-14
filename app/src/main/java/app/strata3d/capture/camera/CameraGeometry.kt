package app.strata3d.capture.camera

import app.strata3d.capture.format.Intrinsics
import app.strata3d.capture.format.IntrinsicsSource

/** Pure camera geometry for the CameraX capture (unit-tested). */
object CameraGeometry {

    /** A rectangle in view pixels. */
    data class Rect(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * Where the camera image appears in a view of [viewW] × [viewH] when it is scaled to fit inside it, centred (the
     * preview's FIT_CENTER), for an **upright** image of [imageW] × [imageH].
     */
    fun fitCenter(viewW: Float, viewH: Float, imageW: Int, imageH: Int): Rect {
        require(viewW > 0 && viewH > 0 && imageW > 0 && imageH > 0)
        val s = minOf(viewW / imageW, viewH / imageH)
        val w = imageW * s
        val h = imageH * s
        return Rect((viewW - w) / 2, (viewH - h) / 2, w, h)
    }

    /**
     * A tap at view pixel ([x], [y]) as (u, v) in 0..1 across the upright image shown in [content], or null when the
     * tap is outside the image (on the bars around it).
     */
    fun tapToImage(x: Float, y: Float, content: Rect): Pair<Double, Double>? {
        val u = (x - content.left) / content.width
        val v = (y - content.top) / content.height
        return if (u in 0f..1f && v in 0f..1f) u.toDouble() to v.toDouble() else null
    }

    /** Horizontal field of view assumed when the camera publishes no lens data (typical phone main camera). */
    const val ASSUMED_HFOV_DEG = 66.0

    /**
     * Intrinsics for a [w] × [h] photo when the camera publishes no lens data: [ASSUMED_HFOV_DEG] across the longer
     * side, principal point centred. Recorded as [IntrinsicsSource.ASSUMED], so reconstruction self-calibrates.
     */
    fun assumedIntrinsics(w: Int, h: Int): Intrinsics {
        val f = maxOf(w, h) / 2.0 / kotlin.math.tan(Math.toRadians(ASSUMED_HFOV_DEG / 2))
        return Intrinsics(f, f, (w - 1) / 2.0, (h - 1) / 2.0, w, h)
    }

    /** The image as stored: [rotationDegrees] clockwise shows it upright. Upright size of a stored [w] × [h]. */
    fun uprightSize(w: Int, h: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (rotationDegrees == 90 || rotationDegrees == 270) h to w else w to h

    /**
     * Pinhole intrinsics for a photo stored as [imageW] × [imageH], from the camera's characteristics. The sensor's
     * active array ([activeW] × [activeH], sensor orientation = landscape on phones) maps onto the photo scaled
     * uniformly and centre-cropped; if the photo is stored rotated by a quarter turn (portrait pixels), the array is
     * turned to match.
     *
     * - With a published calibration ([calibration] = fx, fy, cx, cy, s in active-array pixels, fx > 0), that is used.
     * - Otherwise focal length [focalMm] over the physical pixel pitch ([sensorWidthMm] / [pixelArrayW]), principal
     *   point at the centre.
     *
     * Returns null when neither is available.
     */
    fun intrinsics(
        imageW: Int, imageH: Int,
        activeW: Int, activeH: Int,
        pixelArrayW: Int?,
        focalMm: Double?, sensorWidthMm: Double?,
        calibration: FloatArray?,
    ): Pair<Intrinsics, IntrinsicsSource>? {
        if (imageW <= 0 || imageH <= 0 || activeW <= 0 || activeH <= 0) return null
        val rotated = (imageW < imageH) != (activeW < activeH)
        val aw = if (rotated) activeH else activeW
        val ah = if (rotated) activeW else activeH
        if (calibration != null && calibration.size >= 4 && calibration[0] > 0f && calibration[1] > 0f) {
            val fx = calibration[0].toDouble(); val fy = calibration[1].toDouble()
            val cx = calibration[2].toDouble(); val cy = calibration[3].toDouble()
            // In active-array axes. A photo stored turned a quarter swaps the focal lengths; which way it was turned
            // isn't known here, so the principal point is then taken as the centre (an offset of a few pixels at most).
            val k = if (!rotated) Intrinsics(fx, fy, cx, cy, aw, ah)
            else Intrinsics(fy, fx, (aw - 1) / 2.0, (ah - 1) / 2.0, aw, ah)
            return k.scaledTo(imageW, imageH) to IntrinsicsSource.CAMERA2
        }
        if (focalMm == null || focalMm <= 0 || sensorWidthMm == null || sensorWidthMm <= 0 || pixelArrayW == null || pixelArrayW <= 0) return null
        val pitchMm = sensorWidthMm / pixelArrayW            // square pixels
        val fPx = focalMm / pitchMm                            // in active-array pixels
        val k = Intrinsics(fPx, fPx, (aw - 1) / 2.0, (ah - 1) / 2.0, aw, ah)
        return k.scaledTo(imageW, imageH) to IntrinsicsSource.FOCAL_LENGTH
    }
}
