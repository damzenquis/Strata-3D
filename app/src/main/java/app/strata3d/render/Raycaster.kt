package app.strata3d.render

import app.strata3d.model.BoundingBox
import app.strata3d.model.Mesh
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Ray(
    val ox: Float, val oy: Float, val oz: Float,
    val dx: Float, val dy: Float, val dz: Float,
)

/** The nearest surface point a ray hits (exact, never snapped: snapping is done in screen space by [MeasurePicker]). */
data class PickResult(
    /** World coordinates of the hit point (x, y, z). */
    val point: FloatArray,
    /** Distance along the ray from its origin to the hit point. */
    val distance: Float,
    /**
     * True when the ray first reaches the filled cut of a section ([point] is on the cut plane). The fill is only a
     * picture of the cut, not geometry, so callers treat it as an occluder and never as a pickable surface.
     */
    val onCap: Boolean = false,
)

object Raycaster {

    /**
     * Intersects [ray] with the axis-aligned [box].
     */
    fun intersectsBox(box: BoundingBox, ray: Ray): Boolean {
        var tmin = Float.NEGATIVE_INFINITY
        var tmax = Float.POSITIVE_INFINITY

        if (abs(ray.dx) > 1e-7f) {
            val t1 = (box.minX - ray.ox) / ray.dx
            val t2 = (box.maxX - ray.ox) / ray.dx
            tmin = max(tmin, min(t1, t2))
            tmax = min(tmax, max(t1, t2))
        } else if (ray.ox < box.minX || ray.ox > box.maxX) {
            return false
        }

        if (abs(ray.dy) > 1e-7f) {
            val t1 = (box.minY - ray.oy) / ray.dy
            val t2 = (box.maxY - ray.oy) / ray.dy
            tmin = max(tmin, min(t1, t2))
            tmax = min(tmax, max(t1, t2))
        } else if (ray.oy < box.minY || ray.oy > box.maxY) {
            return false
        }

        if (abs(ray.dz) > 1e-7f) {
            val t1 = (box.minZ - ray.oz) / ray.dz
            val t2 = (box.maxZ - ray.oz) / ray.dz
            tmin = max(tmin, min(t1, t2))
            tmax = min(tmax, max(t1, t2))
        } else if (ray.oz < box.minZ || ray.oz > box.maxZ) {
            return false
        }

        return tmax >= max(tmin, 0f)
    }

    /**
     * Casts [ray] through [mesh] using Möller–Trumbore ray-triangle intersection and returns the closest hit,
     * or null if the ray misses the mesh. Both faces of a triangle are hit (models are not always closed or
     * consistently wound).
     *
     * With a [section], hits on the removed side are ignored, as they are not drawn. With [capped] (the cut is
     * filled on screen), the same winding count as the renderer's stencil pass decides whether the ray meets the
     * fill first; the result is then marked [PickResult.onCap].
     */
    fun intersect(mesh: Mesh, ray: Ray, section: SectionPlane? = null, capped: Boolean = false): PickResult? {
        if (!intersectsBox(mesh.bounds, ray)) return null

        val buf = mesh.vertices.duplicate().apply { position(0) }
        val triangleCount = mesh.triangleCount
        if (triangleCount == 0) return null

        var closestT = Float.POSITIVE_INFINITY
        val plane = section?.equation()
        var winding = 0

        // Buffer in chunks of up to 1000 triangles (9000 floats) to eliminate per-float JNI overhead.
        val chunkSize = min(triangleCount * 9, 9000)
        val chunk = FloatArray(chunkSize)

        var trianglesLeft = triangleCount
        while (trianglesLeft > 0) {
            val toRead = min(trianglesLeft * 9, chunkSize)
            buf.get(chunk, 0, toRead)
            val numTrianglesInChunk = toRead / 9
            trianglesLeft -= numTrianglesInChunk

            var idx = 0
            for (t in 0 until numTrianglesInChunk) {
                val v0x = chunk[idx]; val v0y = chunk[idx + 1]; val v0z = chunk[idx + 2]
                val v1x = chunk[idx + 3]; val v1y = chunk[idx + 4]; val v1z = chunk[idx + 5]
                val v2x = chunk[idx + 6]; val v2y = chunk[idx + 7]; val v2z = chunk[idx + 8]
                idx += 9

                val edge1X = v1x - v0x; val edge1Y = v1y - v0y; val edge1Z = v1z - v0z
                val edge2X = v2x - v0x; val edge2Y = v2y - v0y; val edge2Z = v2z - v0z

                // pvec = dir x edge2
                val pvecX = ray.dy * edge2Z - ray.dz * edge2Y
                val pvecY = ray.dz * edge2X - ray.dx * edge2Z
                val pvecZ = ray.dx * edge2Y - ray.dy * edge2X

                val det = edge1X * pvecX + edge1Y * pvecY + edge1Z * pvecZ
                // Ray parallel to the triangle (or a degenerate triangle). The test is relative to the edge
                // lengths, so it behaves the same for models in metres, millimetres or microns.
                val e1Sq = edge1X * edge1X + edge1Y * edge1Y + edge1Z * edge1Z
                val e2Sq = edge2X * edge2X + edge2Y * edge2Y + edge2Z * edge2Z
                if (det == 0f || det * det <= 1e-12f * e1Sq * e2Sq) continue

                val invDet = 1.0f / det
                val tvecX = ray.ox - v0x; val tvecY = ray.oy - v0y; val tvecZ = ray.oz - v0z
                val u = (tvecX * pvecX + tvecY * pvecY + tvecZ * pvecZ) * invDet
                if (u < 0f || u > 1f) continue

                // qvec = tvec x edge1
                val qvecX = tvecY * edge1Z - tvecZ * edge1Y
                val qvecY = tvecZ * edge1X - tvecX * edge1Z
                val qvecZ = tvecX * edge1Y - tvecY * edge1X

                val v = (ray.dx * qvecX + ray.dy * qvecY + ray.dz * qvecZ) * invDet
                if (v < 0f || u + v > 1f) continue

                val hitDist = (edge2X * qvecX + edge2Y * qvecY + edge2Z * qvecZ) * invDet
                if (hitDist <= 1e-5f) continue
                if (plane != null) {
                    val hx = ray.ox + hitDist * ray.dx; val hy = ray.oy + hitDist * ray.dy; val hz = ray.oz + hitDist * ray.dz
                    if (plane[0] * hx + plane[1] * hy + plane[2] * hz + plane[3] < 0f) continue // cut away
                    // Front face (counter-clockwise towards the viewer: det > 0) +1, back face -1, as in the stencil.
                    if (capped) winding += if (det > 0f) 1 else -1
                }
                if (hitDist < closestT) closestT = hitDist
            }
        }

        if (plane != null && capped && winding != 0) {
            // Where the ray crosses the cut plane, the fill is drawn; it hides anything farther away.
            val denom = plane[0] * ray.dx + plane[1] * ray.dy + plane[2] * ray.dz
            if (denom != 0f) {
                val tPlane = -(plane[0] * ray.ox + plane[1] * ray.oy + plane[2] * ray.oz + plane[3]) / denom
                if (tPlane > 1e-5f && tPlane < closestT) {
                    return PickResult(
                        floatArrayOf(ray.ox + tPlane * ray.dx, ray.oy + tPlane * ray.dy, ray.oz + tPlane * ray.dz),
                        tPlane,
                        onCap = true,
                    )
                }
            }
        }
        if (closestT.isInfinite()) return null
        return PickResult(
            floatArrayOf(ray.ox + closestT * ray.dx, ray.oy + closestT * ray.dy, ray.oz + closestT * ray.dz),
            closestT,
        )
    }
}
