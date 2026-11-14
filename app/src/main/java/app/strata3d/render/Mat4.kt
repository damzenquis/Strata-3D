package app.strata3d.render

import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Minimal 4×4 matrix helpers in OpenGL's column-major layout, producing exactly the same values as
 * `android.opengl.Matrix` (setLookAtM, perspectiveM, orthoM, multiplyMM, multiplyMV, invertM).
 * Kept free of Android classes so the camera can be unit-tested on the JVM.
 */
internal object Mat4 {

    fun lookAt(out: FloatArray, eye: FloatArray, center: FloatArray, up: FloatArray) {
        var fx = center[0] - eye[0]; var fy = center[1] - eye[1]; var fz = center[2] - eye[2]
        val fl = 1f / len(fx, fy, fz)
        fx *= fl; fy *= fl; fz *= fl
        // s = f × up
        var sx = fy * up[2] - fz * up[1]
        var sy = fz * up[0] - fx * up[2]
        var sz = fx * up[1] - fy * up[0]
        val sl = 1f / len(sx, sy, sz)
        sx *= sl; sy *= sl; sz *= sl
        // u = s × f
        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        out[0] = sx; out[1] = ux; out[2] = -fx; out[3] = 0f
        out[4] = sy; out[5] = uy; out[6] = -fy; out[7] = 0f
        out[8] = sz; out[9] = uz; out[10] = -fz; out[11] = 0f
        out[12] = -(sx * eye[0] + sy * eye[1] + sz * eye[2])
        out[13] = -(ux * eye[0] + uy * eye[1] + uz * eye[2])
        out[14] = fx * eye[0] + fy * eye[1] + fz * eye[2]
        out[15] = 1f
    }

    fun perspective(out: FloatArray, fovYDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        val rangeReciprocal = 1f / (near - far)
        out.fill(0f)
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) * rangeReciprocal
        out[11] = -1f
        out[14] = 2f * far * near * rangeReciprocal
    }

    fun ortho(out: FloatArray, left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float) {
        val rw = 1f / (right - left)
        val rh = 1f / (top - bottom)
        val rd = 1f / (far - near)
        out.fill(0f)
        out[0] = 2f * rw
        out[5] = 2f * rh
        out[10] = -2f * rd
        out[12] = -(right + left) * rw
        out[13] = -(top + bottom) * rh
        out[14] = -(far + near) * rd
        out[15] = 1f
    }

    /** out = a × b (out may not alias a or b). */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
    }

    /** out = m × v for a 4-component vector. */
    fun multiplyVec(out: FloatArray, m: FloatArray, v: FloatArray) {
        for (row in 0 until 4) {
            out[row] = m[row] * v[0] + m[4 + row] * v[1] + m[8 + row] * v[2] + m[12 + row] * v[3]
        }
    }

    /** General 4×4 inverse; returns false when [m] is singular. */
    fun invert(out: FloatArray, m: FloatArray): Boolean {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (det == 0f || !det.isFinite()) return false
        val invDet = 1f / det
        for (i in 0 until 16) out[i] = inv[i] * invDet
        return true
    }

    private fun len(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
}
