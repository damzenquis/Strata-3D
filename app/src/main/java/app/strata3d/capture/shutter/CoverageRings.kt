package app.strata3d.capture.shutter

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The two-ring coverage dial on the capture screen: which directions around the object have photos, for a lower ring
 * and an upper ring (looking down from above, tilt [Settings.upperTiltDeg] or more).
 *
 * It uses the **same rule as the smart shutter**: a cell is covered when a photo's viewing direction is within
 * [Settings.stepDeg] of the cell's centre direction. So a cell shows as missing exactly when the shutter would take a
 * photo at its centre; the dial never asks for a spot the shutter refuses. (Before, each photo filled only its own
 * 15° cell, so a cell between two photos 19° apart showed as missing while the shutter, which wants 9° from every
 * photo, would barely fire there: going back to fill it, the ring stalled.)
 *
 * A cell's centre is at its azimuth (measured from the first photo's, counter-clockwise from above) and at the ring's
 * typical tilt: the median tilt of that ring's photos, or [Settings.lowerTiltDeg] / [Settings.upperDefaultTiltDeg]
 * before it has any. Without a compass the azimuth can drift a few degrees over a long capture; the dial is guidance.
 */
class CoverageRings(private val settings: Settings = Settings()) {

    data class Settings(
        /** Cells per ring. 24 cells = 15° each. */
        val cells: Int = 24,
        /** Camera tilt below the horizon (degrees) from which a photo belongs to the upper ring. */
        val upperTiltDeg: Double = 35.0,
        /** The smart shutter's spacing between photos ([SmartShutter.Settings.stepDeg]): the covered radius. */
        val stepDeg: Double = SmartShutter.Settings().stepDeg,
        /** Centre tilt of the lower ring before it has photos. */
        val lowerTiltDeg: Double = 15.0,
        /** Centre tilt of the upper ring before it has photos. */
        val upperDefaultTiltDeg: Double = 50.0,
    ) {
        init { require(cells in 4..360 && upperTiltDeg in 0.0..90.0 && stepDeg > 0) }
    }

    enum class Ring { LOWER, UPPER }

    private class Photo(val forward: DoubleArray, val tiltDeg: Double, val upper: Boolean)

    private val photos = ArrayList<Photo>()
    private var referenceAzimuthDeg: Double? = null

    /** Records a saved photo taken with [orientation]. */
    fun add(orientation: DeviceOrientation) {
        val f = orientation.cameraForward()
        val tiltDeg = Math.toDegrees(asin((-f[2]).coerceIn(-1.0, 1.0)))   // positive = looking down
        if (referenceAzimuthDeg == null) referenceAzimuthDeg = Math.toDegrees(atan2(f[1], f[0]))
        photos += Photo(f, tiltDeg, tiltDeg >= settings.upperTiltDeg)
    }

    /** Covered cells of [ring], cell 0 being the first photo's direction, increasing counter-clockwise from above. */
    fun cells(ring: Ring): List<Boolean> {
        val ref = referenceAzimuthDeg ?: return List(settings.cells) { false }
        val tilt = Math.toRadians(centreTiltDeg(ring))
        val cellDeg = 360.0 / settings.cells
        return List(settings.cells) { c ->
            val az = Math.toRadians(ref + c * cellDeg)
            val centre = doubleArrayOf(cos(tilt) * cos(az), cos(tilt) * sin(az), -sin(tilt))
            photos.any { angleDeg(it.forward, centre) <= settings.stepDeg }
        }
    }

    /** Fraction 0..1 of [ring]'s cells that are covered. */
    fun fraction(ring: Ring): Double = cells(ring).count { it }.toDouble() / settings.cells

    /** The tilt the ring's cell centres are at: the median tilt of its photos, or its default. */
    fun centreTiltDeg(ring: Ring): Double {
        val tilts = photos.filter { it.upper == (ring == Ring.UPPER) }.map { it.tiltDeg }.sorted()
        if (tilts.isEmpty()) return if (ring == Ring.UPPER) settings.upperDefaultTiltDeg else settings.lowerTiltDeg
        val n = tilts.size
        return if (n % 2 == 1) tilts[n / 2] else (tilts[n / 2 - 1] + tilts[n / 2]) / 2
    }

    private fun angleDeg(a: DoubleArray, b: DoubleArray) =
        Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))
}
