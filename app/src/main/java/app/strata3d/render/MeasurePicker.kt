package app.strata3d.render

import app.strata3d.model.Mesh
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A copy of the camera state at the moment of a tap, so picking can run off the UI thread while the camera
 * keeps moving. [viewProjection] is column-major and includes the lens shift, so projected points match the
 * pixels on screen.
 */
class PickFrame(
    val viewProjection: FloatArray,
    val width: Int,
    val height: Int,
    val orthographic: Boolean,
    /** Camera position (perspective rays start here). */
    val eye: FloatArray,
    /** Unit view direction (all orthographic rays share it). */
    val forward: FloatArray,
)

/** A picked measurement point: on a visible vertex ([snapped]) or a free point on the visible surface. */
class MeasurePick(val point: FloatArray, val snapped: Boolean)

/**
 * Picks measurement points (pure; unit-tested).
 *
 * 1. The tap ray finds the nearest visible surface point (exact, the free point).
 * 2. With snapping on, mesh vertices are projected to the screen and those within [snapRadiusPx] of the tap are
 *    candidates, nearest first. A candidate is accepted only if it is *visible*: a ray from the camera to it
 *    hits nothing in front of it. Vertices on the hidden back of the model, or behind another part, are never
 *    picked even though they project close to the tap.
 * 3. If no candidate is visible, the free surface point is used.
 *
 * With a section, everything on the removed side is ignored. A filled cut hides what is behind it but is never
 * picked itself: it is a picture of the cut, not geometry (no wall-thickness points are invented on it).
 *
 * Only the original mesh triangles are pickable.
 */
object MeasurePicker {

    /** Visibility checks are full ray casts, so only the nearest few distinct candidates are tried. */
    internal const val MAX_CANDIDATES = 4

    fun pick(
        mesh: Mesh, frame: PickFrame, ray: Ray, tapX: Float, tapY: Float, snapRadiusPx: Float, snap: Boolean,
        section: SectionPlane? = null, capped: Boolean = false,
    ): MeasurePick? {
        val surface = Raycaster.intersect(mesh, ray, section, capped)
        if (snap) {
            val candidates = nearestProjectedVertices(mesh, frame, tapX, tapY, snapRadiusPx, MAX_CANDIDATES, section)
            for (c in candidates) {
                if (isVisible(mesh, frame, c, section, capped)) return MeasurePick(c, snapped = true)
            }
        }
        return surface?.takeUnless { it.onCap }?.let { MeasurePick(it.point, snapped = false) }
    }

    /**
     * Distinct vertex positions whose screen projection lies within [radiusPx] of the tap, nearest first, at
     * most [max] of them. Vertices outside the depth range or behind the camera are skipped.
     */
    internal fun nearestProjectedVertices(
        mesh: Mesh, frame: PickFrame, tapX: Float, tapY: Float, radiusPx: Float, max: Int,
        section: SectionPlane? = null,
    ): List<FloatArray> {
        val plane = section?.equation()
        val m = frame.viewProjection
        val halfW = frame.width * 0.5f
        val halfH = frame.height * 0.5f
        val r2 = radiusPx * radiusPx
        // Sorted (by screen distance²) fixed-size candidate list.
        val bestD = FloatArray(max) { Float.POSITIVE_INFINITY }
        val bestP = arrayOfNulls<FloatArray>(max)
        var count = 0

        val buf = mesh.vertices.duplicate().apply { position(0) }
        val total = mesh.vertexCount * 3
        val chunk = FloatArray(min(total, 3 * 4096).coerceAtLeast(3))
        var read = 0
        while (read < total) {
            val n = min(chunk.size, total - read)
            buf.get(chunk, 0, n)
            read += n
            var i = 0
            while (i < n) {
                val x = chunk[i]; val y = chunk[i + 1]; val z = chunk[i + 2]
                i += 3
                val w = m[3] * x + m[7] * y + m[11] * z + m[15]
                if (w <= 1e-12f) continue
                val cz = m[2] * x + m[6] * y + m[10] * z + m[14]
                if (cz < -w || cz > w) continue
                val sx = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w * halfW + halfW
                val sy = halfH - (m[1] * x + m[5] * y + m[9] * z + m[13]) / w * halfH
                val dx = sx - tapX; val dy = sy - tapY
                val d2 = dx * dx + dy * dy
                if (d2 > r2 || (count == max && d2 >= bestD[max - 1])) continue
                if (plane != null && plane[0] * x + plane[1] * y + plane[2] * z + plane[3] < 0f) continue // cut away
                // A triangle soup repeats each position once per triangle: keep each position once.
                var dup = false
                for (k in 0 until count) {
                    val p = bestP[k]!!
                    if (p[0] == x && p[1] == y && p[2] == z) { dup = true; break }
                }
                if (dup) continue
                var pos = if (count < max) count else max - 1
                while (pos > 0 && bestD[pos - 1] > d2) {
                    bestD[pos] = bestD[pos - 1]; bestP[pos] = bestP[pos - 1]; pos--
                }
                bestD[pos] = d2; bestP[pos] = floatArrayOf(x, y, z)
                if (count < max) count++
            }
        }
        return List(count) { bestP[it]!! }
    }

    /** True when nothing in the mesh lies between the camera and [p] (tolerance relative to the model size). */
    internal fun isVisible(mesh: Mesh, frame: PickFrame, p: FloatArray, section: SectionPlane? = null, capped: Boolean = false): Boolean {
        val diag = mesh.bounds.let { b ->
            sqrt(b.sizeX * b.sizeX + b.sizeY * b.sizeY + b.sizeZ * b.sizeZ)
        }.takeIf { it > 0f && it.isFinite() } ?: 1f
        val ray: Ray
        val targetT: Float
        if (frame.orthographic) {
            val f = frame.forward
            val back = diag * 2f
            ray = Ray(p[0] - f[0] * back, p[1] - f[1] * back, p[2] - f[2] * back, f[0], f[1], f[2])
            targetT = back
        } else {
            val e = frame.eye
            val dx = p[0] - e[0]; val dy = p[1] - e[1]; val dz = p[2] - e[2]
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len <= 0f) return true
            ray = Ray(e[0], e[1], e[2], dx / len, dy / len, dz / len)
            targetT = len
        }
        val hit = Raycaster.intersect(mesh, ray, section, capped) ?: return true
        val tolerance = max(diag * 1e-4f, targetT * 1e-5f)
        return hit.distance >= targetT - tolerance
    }
}
