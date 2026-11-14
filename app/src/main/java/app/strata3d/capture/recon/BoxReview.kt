package app.strata3d.capture.recon

import java.util.Locale
import kotlin.math.abs

/**
 * Geometry for the box review screen (pure, unit-tested): the object box drawn on the scene's undistorted photos, and
 * how dragging a handle changes it.
 *
 * Frames: after `objbox`, the scene is aligned with +z up and the object standing at the origin, so the box is
 * x, y in [-radius, radius] and z in [bottom, top] ([ObjectBox]). MVE cameras are world-to-camera (`rotation`
 * row-major, `translation`), camera +x right, +y down, +z forward; `focal_length` is relative to the larger image side
 * and the principal point is the image centre (undistorted images).
 */
object BoxReview {

    data class V3(val x: Double, val y: Double, val z: Double)

    /** The upright object box in the aligned scene frame. */
    data class ObjectBox(val radius: Double, val bottom: Double, val top: Double) {
        init { require(radius > 0 && top > bottom) { "invalid box" } }

        /** objbox.txt / dmrecon --bounding-box format. */
        fun encode(): String = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", -radius, -radius, bottom, radius, radius, top)

        fun corners(): List<V3> = listOf(bottom, top).flatMap { z ->
            listOf(V3(-radius, -radius, z), V3(radius, -radius, z), V3(radius, radius, z), V3(-radius, radius, z))
        }

        /** The 12 edges as pairs of [corners] indices. */
        val edges: List<Pair<Int, Int>> get() = EDGES

        companion object {
            private val EDGES = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)

            /** Parses objbox.txt; only boxes centred on the vertical axis (as objbox v2 writes them) are reviewable. */
            fun parse(text: String): ObjectBox? {
                val v = text.trim().split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (v.size != 6) return null
                val r = (v[3] - v[0]) / 2
                if (abs(v[0] + v[3]) > 1e-3 || abs(v[1] + v[4]) > 1e-3 || abs((v[4] - v[1]) / 2 - r) > 1e-3) return null
                return if (r > 0 && v[5] > v[2]) ObjectBox(r, v[2], v[5]) else null
            }
        }
    }

    /** An MVE camera from a view's meta.ini. */
    data class Camera(val focal: Double, val rot: DoubleArray, val trans: DoubleArray) {
        /** Camera centre in the world: -R^T t. */
        val centre: V3 get() = V3(
            -(rot[0] * trans[0] + rot[3] * trans[1] + rot[6] * trans[2]),
            -(rot[1] * trans[0] + rot[4] * trans[1] + rot[7] * trans[2]),
            -(rot[2] * trans[0] + rot[5] * trans[1] + rot[8] * trans[2]),
        )

        override fun equals(other: Any?) = other is Camera && focal == other.focal && rot.contentEquals(other.rot) && trans.contentEquals(other.trans)
        override fun hashCode() = focal.hashCode() * 31 + rot.contentHashCode()

        companion object {
            /** Parses a view's meta.ini; null if the view has no (valid) camera. */
            fun parseMetaIni(text: String): Camera? {
                fun nums(key: String) = text.lineSequence().firstOrNull { it.trimStart().startsWith("$key ") || it.trimStart().startsWith("$key=") }
                    ?.substringAfter('=')?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }
                val f = nums("focal_length")?.firstOrNull() ?: return null
                val r = nums("rotation") ?: return null
                val t = nums("translation") ?: return null
                if (f <= 0 || r.size != 9 || t.size != 3) return null
                return Camera(f, r.toDoubleArray(), t.toDoubleArray())
            }
        }
    }

    /**
     * Projects world point [p] into an image of [w] × [h] (stored orientation), in pixels; null when the point is
     * behind the camera.
     */
    fun project(c: Camera, w: Int, h: Int, p: V3): Pair<Double, Double>? {
        val r = c.rot; val t = c.trans
        val x = r[0] * p.x + r[1] * p.y + r[2] * p.z + t[0]
        val y = r[3] * p.x + r[4] * p.y + r[5] * p.z + t[1]
        val z = r[6] * p.x + r[7] * p.y + r[8] * p.z + t[2]
        if (z <= 1e-9) return null
        val f = c.focal * maxOf(w, h)
        return (f * x / z + w / 2.0) to (f * y / z + h / 2.0)
    }

    /** A stored-image pixel shown upright after [rotation] degrees clockwise; returns upright pixel coordinates. */
    fun toUpright(px: Double, py: Double, w: Int, h: Int, rotation: Int): Pair<Double, Double> = when (rotation) {
        90 -> (h - py) to px
        180 -> (w - px) to (h - py)
        270 -> py to (w - px)
        else -> px to py
    }

    /** Upright size of a stored [w] × [h] image. */
    fun uprightSize(w: Int, h: Int, rotation: Int): Pair<Int, Int> = if (rotation == 90 || rotation == 270) h to w else w to h

    enum class Handle { TOP, BOTTOM, SIDE_POS_X, SIDE_NEG_X, SIDE_POS_Y, SIDE_NEG_Y }

    /** The 3D point of [handle] on [box]: the centre of its face. */
    fun handlePoint(box: ObjectBox, handle: Handle): V3 {
        val mid = (box.bottom + box.top) / 2
        return when (handle) {
            Handle.TOP -> V3(0.0, 0.0, box.top)
            Handle.BOTTOM -> V3(0.0, 0.0, box.bottom)
            Handle.SIDE_POS_X -> V3(box.radius, 0.0, mid)
            Handle.SIDE_NEG_X -> V3(-box.radius, 0.0, mid)
            Handle.SIDE_POS_Y -> V3(0.0, box.radius, mid)
            Handle.SIDE_NEG_Y -> V3(0.0, -box.radius, mid)
        }
    }

    /** [box] with the parameter [handle] controls changed by [delta] (top, bottom or radius), kept valid. */
    fun adjust(box: ObjectBox, handle: Handle, delta: Double, minSize: Double): ObjectBox = when (handle) {
        Handle.TOP -> box.copy(top = maxOf(box.top + delta, box.bottom + minSize))
        Handle.BOTTOM -> box.copy(bottom = minOf(box.bottom + delta, box.top - minSize))
        else -> box.copy(radius = maxOf(box.radius + delta, minSize / 2))
    }

    /**
     * A drag of ([dx], [dy]) upright pixels on [handle], seen through [project] (world point to upright pixel):
     * the parameter change whose on-screen motion best matches the drag (a least-squares fit along the handle's
     * screen direction, measured by a small finite step).
     */
    fun dragDelta(box: ObjectBox, handle: Handle, dx: Double, dy: Double, project: (V3) -> Pair<Double, Double>?): Double {
        val eps = maxOf(box.radius, box.top - box.bottom) * 1e-3
        val a = project(handlePoint(box, handle)) ?: return 0.0
        val b = project(handlePoint(adjust(box, handle, eps, 0.0), handle)) ?: return 0.0
        val sx = (b.first - a.first) / eps
        val sy = (b.second - a.second) / eps
        val n2 = sx * sx + sy * sy
        if (n2 < 1e-12) return 0.0          // the handle's motion is along the line of sight: not draggable here
        return (dx * sx + dy * sy) / n2
    }

    /**
     * Picks [count] photos spread evenly around the object for the review: starting from the first registered view,
     * the views nearest (by angle around the box axis, seen from above) to equal steps of 360 / [count] degrees.
     */
    fun pickViews(centres: List<V3>, count: Int): List<Int> {
        if (centres.isEmpty()) return emptyList()
        val ang = centres.map { kotlin.math.atan2(it.y, it.x) }
        val chosen = mutableListOf(0)
        for (k in 1 until minOf(count, centres.size)) {
            val target = ang[0] + k * 2 * Math.PI / count
            val best = centres.indices.filter { it !in chosen }.minByOrNull { angleBetween(ang[it], target) } ?: break
            chosen += best
        }
        return chosen.sorted()
    }

    private fun angleBetween(a: Double, b: Double): Double {
        val d = abs(a - b) % (2 * Math.PI)
        return if (d > Math.PI) 2 * Math.PI - d else d
    }
}
