package app.strata3d.capture.format

import kotlin.math.sqrt

/**
 * Rotation and pose conversions between ARCore's convention and the ones reconstruction tools use.
 *
 * - **ARCore / OpenGL** (what captures store): camera-to-world; camera +x right, +y up, looking down −z.
 * - **OpenCV / COLMAP**: camera +x right, +y down, looking down +z. COLMAP stores **world-to-camera**.
 * - **nerfstudio**: camera-to-world 4×4 in the OpenGL camera convention (same as ARCore).
 *
 * Matrices are 3×3 row-major `DoubleArray(9)`.
 */
object PoseMath {

    /** Rotation matrix of a quaternion (normalised first). */
    fun quatToMatrix(qx: Double, qy: Double, qz: Double, qw: Double): DoubleArray {
        val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).takeIf { it > 1e-12 } ?: 1.0
        val x = qx / n; val y = qy / n; val z = qz / n; val w = qw / n
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }

    /** Unit quaternion (x, y, z, w) of a rotation matrix, with w ≥ 0. */
    fun matrixToQuat(m: DoubleArray): DoubleArray {
        val trace = m[0] + m[4] + m[8]
        val q: DoubleArray = if (trace > 0) {
            val s = sqrt(trace + 1.0) * 2
            doubleArrayOf((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, 0.25 * s)
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2
            doubleArrayOf(0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
        } else if (m[4] > m[8]) {
            val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2
            doubleArrayOf((m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
        } else {
            val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2
            doubleArrayOf((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s, (m[3] - m[1]) / s)
        }
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        val sign = if (q[3] < 0) -1.0 else 1.0
        return DoubleArray(4) { q[it] / n * sign }
    }

    fun transpose(m: DoubleArray) = doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun apply(m: DoubleArray, v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    /** Camera-to-world rotation of an ARCore pose. */
    fun rotation(p: Pose) = quatToMatrix(p.qx, p.qy, p.qz, p.qw)

    /** The direction an ARCore camera looks in, in world space (its −z axis). */
    fun forward(p: Pose): Vec3 { val r = rotation(p); return Vec3(-r[2], -r[5], -r[8]) }

    /**
     * World-to-camera rotation (row-major) and translation in the **OpenCV/COLMAP** camera convention, from an
     * ARCore camera-to-world pose. A world point X maps to camera coordinates `R·X + t`.
     */
    fun worldToCameraOpenCv(p: Pose): Pair<DoubleArray, Vec3> {
        val rGl = rotation(p)
        // Flip camera y and z: R_cv(c2w) = R_gl(c2w) · diag(1, −1, −1).
        val rCvC2w = doubleArrayOf(rGl[0], -rGl[1], -rGl[2], rGl[3], -rGl[4], -rGl[5], rGl[6], -rGl[7], -rGl[8])
        val r = transpose(rCvC2w)
        val c = apply(r, p.position)
        return r to Vec3(-c.x, -c.y, -c.z)
    }

    /** Camera-to-world 4×4 (row-major) in the OpenGL camera convention, as nerfstudio's `transform_matrix` expects. */
    fun cameraToWorldGl(p: Pose): DoubleArray {
        val r = rotation(p)
        return doubleArrayOf(
            r[0], r[1], r[2], p.tx,
            r[3], r[4], r[5], p.ty,
            r[6], r[7], r[8], p.tz,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    /**
     * Projects world point [x] into a camera at ARCore pose [p] with [k]. Returns pixel (u, v) or null when the
     * point is behind the camera. Used by tests and coverage checks.
     */
    fun project(p: Pose, k: Intrinsics, x: Vec3): Pair<Double, Double>? {
        val (r, t) = worldToCameraOpenCv(p)
        val c = apply(r, x) + t
        if (c.z <= 1e-9) return null
        return (k.fx * c.x / c.z + k.cx) to (k.fy * c.y / c.z + k.cy)
    }

    /**
     * Pose between [a] (t = 0) and [b] (t = 1): linear in position, spherical-linear in rotation. Used when ARCore
     * skips a camera frame and a photo's pose lies between two reported frames.
     */
    fun interpolate(a: Pose, b: Pose, t: Double): Pose {
        val u = t.coerceIn(0.0, 1.0)
        var bx = b.qx; var by = b.qy; var bz = b.qz; var bw = b.qw
        var dot = a.qx * bx + a.qy * by + a.qz * bz + a.qw * bw
        if (dot < 0) { bx = -bx; by = -by; bz = -bz; bw = -bw; dot = -dot } // shortest arc
        val (wa, wb) = if (dot > 0.9995) {
            (1 - u) to u
        } else {
            val theta = kotlin.math.acos(dot.coerceIn(-1.0, 1.0))
            val sinT = kotlin.math.sin(theta)
            (kotlin.math.sin((1 - u) * theta) / sinT) to (kotlin.math.sin(u * theta) / sinT)
        }
        var qx = wa * a.qx + wb * bx; var qy = wa * a.qy + wb * by; var qz = wa * a.qz + wb * bz; var qw = wa * a.qw + wb * bw
        val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        qx /= n; qy /= n; qz /= n; qw /= n
        return Pose(a.tx + (b.tx - a.tx) * u, a.ty + (b.ty - a.ty) * u, a.tz + (b.tz - a.tz) * u, qx, qy, qz, qw)
    }

    /** Angle in radians between two unit-ish vectors. */
    fun angleBetween(a: Vec3, b: Vec3): Double {
        val d = (a.normalized().dot(b.normalized())).coerceIn(-1.0, 1.0)
        return kotlin.math.acos(d)
    }
}
