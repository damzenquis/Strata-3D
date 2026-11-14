package app.strata3d.render

import app.strata3d.model.BoundingBox

/** World axis a section plane is perpendicular to (the viewer convention: +Z up, front view looks along +Y). */
enum class SectionAxis { X, Y, Z }

/**
 * An axis-aligned cross-section plane in world (model) coordinates, the same coordinates as the camera presets
 * and measurements. Geometry is never modified: the renderer discards fragments on the removed side.
 *
 * By default the side facing the default camera (front-right, above: +X, −Y, +Z) is removed, so the cut face is
 * what you look at. [flipped] keeps the other side instead.
 *
 * @property position plane coordinate along [axis], in model units.
 */
data class SectionPlane(val axis: SectionAxis, val position: Float, val flipped: Boolean = false) {

    /**
     * Plane equation `(a, b, c, d)`: a point is kept when `a·x + b·y + c·z + d ≥ 0`. The same test runs in the
     * fragment shader, so CPU and GPU agree on what is visible.
     */
    fun equation(): FloatArray {
        // Default kept side: X ≤ p, Y ≥ p, Z ≤ p (the removed half faces the default camera).
        val keepBelow = when (axis) {
            SectionAxis.X -> true
            SectionAxis.Y -> false
            SectionAxis.Z -> true
        } != flipped
        val s = if (keepBelow) -1f else 1f
        return when (axis) {
            SectionAxis.X -> floatArrayOf(s, 0f, 0f, -s * position)
            SectionAxis.Y -> floatArrayOf(0f, s, 0f, -s * position)
            SectionAxis.Z -> floatArrayOf(0f, 0f, s, -s * position)
        }
    }

    /** True when the point is on the kept side (or on the plane). */
    fun keeps(x: Float, y: Float, z: Float): Boolean {
        val e = equation()
        return e[0] * x + e[1] * y + e[2] * z + e[3] >= 0f
    }

    companion object {
        /** Minimum and maximum of [bounds] along [axis]. */
        fun range(bounds: BoundingBox, axis: SectionAxis): Pair<Float, Float> = when (axis) {
            SectionAxis.X -> bounds.minX to bounds.maxX
            SectionAxis.Y -> bounds.minY to bounds.maxY
            SectionAxis.Z -> bounds.minZ to bounds.maxZ
        }

        /** The plane at [fraction] (0..1) of the model's extent along [axis]. */
        fun at(bounds: BoundingBox, axis: SectionAxis, fraction: Float, flipped: Boolean): SectionPlane {
            val (lo, hi) = range(bounds, axis)
            return SectionPlane(axis, lo + (hi - lo) * fraction.coerceIn(0f, 1f), flipped)
        }
    }
}
