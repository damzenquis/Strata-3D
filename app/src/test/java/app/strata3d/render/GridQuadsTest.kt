package app.strata3d.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GridQuadsTest {

    private val identity = FloatArray(16).also { for (i in 0 until 4) it[i * 5] = 1f }

    @Test
    fun segmentBecomesAQuadOfTheRequestedPixelWidth() {
        // Horizontal segment across the middle of a 1000 × 500 target, 3 px wide.
        val lines = floatArrayOf(-0.5f, 0f, 0f, 0.5f, 0f, 0f)
        val q = ModelRenderer.buildGridQuads(lines, 0, 2, identity, 1000, 500, 1.5f)
        assertEquals(6 * 4, q.size)
        val ys = (0 until 6).map { q[it * 4 + 1] / q[it * 4 + 3] }
        val heightPx = (ys.max() - ys.min()) * 500 / 2f
        assertEquals(3f, heightPx, 1e-3f)
        val xs = (0 until 6).map { q[it * 4] / q[it * 4 + 3] }
        assertEquals(-0.5f, xs.min(), 1e-6f)
        assertEquals(0.5f, xs.max(), 1e-6f)
    }

    @Test
    fun segmentsBehindTheCameraAreClippedOrDropped() {
        val view = FloatArray(16); val proj = FloatArray(16); val mvp = FloatArray(16)
        Mat4.lookAt(view, floatArrayOf(0f, -10f, 1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, 1f))
        Mat4.perspective(proj, 40f, 1f, 0.1f, 100f)
        Mat4.multiply(mvp, proj, view)
        // From in front of the camera to well behind it: must be clipped to the near plane, not inverted.
        val crossing = floatArrayOf(0f, 5f, 0f, 0f, -50f, 0f)
        val q = ModelRenderer.buildGridQuads(crossing, 0, 2, mvp, 800, 800, 0.5f)
        assertEquals(24, q.size)
        for (v in 0 until 6) {
            val w = q[v * 4 + 3]
            assertTrue("w must stay positive after clipping (w=$w)", w > 0f)
            assertTrue("z inside the near plane", q[v * 4 + 2] + w >= -1e-4f)
        }
        // Entirely behind the camera: nothing is emitted.
        val behind = floatArrayOf(0f, -20f, 0f, 1f, -30f, 0f)
        assertEquals(0, ModelRenderer.buildGridQuads(behind, 0, 2, mvp, 800, 800, 0.5f).size)
    }

    @Test
    fun onlyTheRequestedRangeIsExpanded() {
        val lines = floatArrayOf(
            -0.5f, 0f, 0f, 0.5f, 0f, 0f,
            0f, -0.5f, 0f, 0f, 0.5f, 0f,
        )
        assertEquals(24, ModelRenderer.buildGridQuads(lines, 2, 2, identity, 100, 100, 1f).size)
        val q = ModelRenderer.buildGridQuads(lines, 2, 2, identity, 100, 100, 1f)
        assertTrue("second (vertical) segment", (0 until 6).all { abs(q[it * 4]) < 0.05f })
    }
}
