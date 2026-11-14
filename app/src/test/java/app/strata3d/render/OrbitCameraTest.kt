package app.strata3d.render

import app.strata3d.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OrbitCameraTest {

    private var now = 0L
    private fun camera(width: Int = 1080, height: Int = 2000) =
        OrbitCamera { now }.apply { setViewport(width, height) }

    private fun box(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) =
        BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)

    private fun finish(cam: OrbitCamera) {
        now += OrbitCamera.TRANSITION_NANOS + 1
        cam.advance()
    }

    /** Clip-space position of a world point. */
    private fun clip(cam: OrbitCamera, x: Float, y: Float, z: Float, aspect: Float? = null): FloatArray {
        val view = FloatArray(16); val proj = FloatArray(16); val eye = FloatArray(3); val vp = FloatArray(16)
        if (aspect == null) cam.computeMatrices(view, proj, eye) else cam.computeMatrices(view, proj, eye, aspect)
        Mat4.multiply(vp, proj, view)
        val out = FloatArray(4)
        Mat4.multiplyVec(out, vp, floatArrayOf(x, y, z, 1f))
        return out
    }

    private fun eye(cam: OrbitCamera): FloatArray {
        val e = FloatArray(3)
        cam.computeMatrices(FloatArray(16), FloatArray(16), e)
        return e
    }

    /** Every corner of [b] lies inside the view volume (x, y, z all within NDC ±1). */
    private fun assertAllCornersVisible(cam: OrbitCamera, b: BoundingBox, label: String) {
        for (i in 0 until 8) {
            val c = clip(cam,
                if (i and 1 == 0) b.minX else b.maxX,
                if (i and 2 == 0) b.minY else b.maxY,
                if (i and 4 == 0) b.minZ else b.maxZ)
            assertTrue("$label: corner $i behind camera", c[3] > 0f)
            for (axis in 0..2) {
                val ndc = c[axis] / c[3]
                assertTrue("$label: corner $i axis $axis outside view (ndc=$ndc)", ndc > -1f && ndc < 1f)
            }
        }
    }

    @Test
    fun switchingProjectionPreservesApparentScaleAtTarget() {
        val b = box(-10f, -10f, -10f, 10f, 10f, 10f)
        val cam = camera().apply { frame(b) }
        // Points on the plane through the target, facing the camera, must land on the same pixels.
        cam.applyPreset(ViewPreset.FRONT); finish(cam)
        val perspective = clip(cam, 7f, 0f, 4f)
        cam.projection = Projection.ORTHOGRAPHIC
        val ortho = clip(cam, 7f, 0f, 4f)
        assertEquals(perspective[0] / perspective[3], ortho[0] / ortho[3], 1e-4f)
        assertEquals(perspective[1] / perspective[3], ortho[1] / ortho[3], 1e-4f)
    }

    @Test
    fun presetsFollowTheDocumentedConvention() {
        val b = box(-5f, -5f, -5f, 5f, 5f, 5f)
        val cam = camera().apply { frame(b) }

        cam.applyPreset(ViewPreset.FRONT); finish(cam)
        var e = eye(cam)
        assertTrue("front looks from -Y", e[1] < -1f && abs(e[0]) < 1e-3f && abs(e[2]) < 1e-3f)
        assertTrue("front: +Z is screen up", clip(cam, 0f, 0f, 4f)[1] > 0f)
        assertTrue("front: +X is screen right", clip(cam, 4f, 0f, 0f)[0] > 0f)

        cam.applyPreset(ViewPreset.SIDE); finish(cam)
        e = eye(cam)
        assertTrue("side looks from +X", e[0] > 1f && abs(e[1]) < 1e-3f && abs(e[2]) < 1e-3f)
        assertTrue("side: +Y is screen right", clip(cam, 0f, 4f, 0f)[0] > 0f)

        cam.applyPreset(ViewPreset.TOP); finish(cam)
        e = eye(cam)
        assertTrue("top looks from +Z", e[2] > 1f && abs(e[0]) < 1e-3f && abs(e[1]) < 1e-3f)
        assertTrue("top: +Y is screen up", clip(cam, 0f, 4f, 0f)[1] > 0f)
        assertTrue("top: +X is screen right", clip(cam, 4f, 0f, 0f)[0] > 0f)

        cam.applyPreset(ViewPreset.ISO); finish(cam)
        e = eye(cam)
        assertTrue("iso from +X, -Y, +Z", e[0] > 0f && e[1] < 0f && e[2] > 0f)
        assertEquals("iso: equal X and Y offsets", e[0], -e[1], 1e-3f)
    }

    @Test
    fun fitShowsWholeBoundsForEveryPresetProjectionAndShape() {
        val shapes = listOf(
            box(-1f, -1f, -1f, 1f, 1f, 1f),
            box(-200f, -3f, -0.5f, 200f, 3f, 0.5f),        // long thin bar
            box(0f, 0f, 0f, 0.001f, 0.002f, 0.0005f),       // tiny (metres-scale glTF)
            box(1.0e5f, 2.0e5f, -50f, 1.0e5f + 30f, 2.0e5f + 10f, 400f), // far from origin, tall
            box(-50f, -50f, 0f, 50f, 50f, 0.2f),            // flat plate
        )
        for ((w, h) in listOf(1080 to 2000, 2000 to 1080, 720 to 720)) {
            for (b in shapes) for (proj in Projection.entries) for (preset in ViewPreset.entries) {
                val cam = camera(w, h).apply { frame(b); projection = proj }
                cam.applyPreset(preset); finish(cam)
                assertAllCornersVisible(cam, b, "$w×$h $proj $preset $b")
                cam.rotate(37f, -21f, 2.75f) // arbitrary orbit, then Fit
                cam.fit(); finish(cam)
                assertAllCornersVisible(cam, b, "$w×$h $proj fit-after-orbit $b")
            }
        }
    }

    @Test
    fun framingKeepsTheModelOutOfObstructedAreas() {
        val (w, h) = 1080 to 2000
        val right = 180f; val bottom = 260f
        // Unobstructed rectangle in NDC.
        val maxNdcX = 1f - 2f * right / w
        val minNdcY = -1f + 2f * bottom / h
        val b = box(-200f, -3f, -0.5f, 200f, 3f, 40f)
        for (proj in Projection.entries) for (preset in ViewPreset.entries) {
            val cam = camera(w, h).apply { frame(b); projection = proj; setObstructedInsets(0f, 0f, right, bottom) }
            cam.applyPreset(preset); finish(cam)
            for (i in 0 until 8) {
                val c = clip(cam,
                    if (i and 1 == 0) b.minX else b.maxX,
                    if (i and 2 == 0) b.minY else b.maxY,
                    if (i and 4 == 0) b.minZ else b.maxZ)
                val x = c[0] / c[3]; val y = c[1] / c[3]
                assertTrue("$proj $preset corner $i x=$x", x > -1f && x < maxNdcX)
                assertTrue("$proj $preset corner $i y=$y", y > minNdcY && y < 1f)
            }
        }
    }

    @Test
    fun transitionsAreSmoothAndInterruptible() {
        val cam = camera().apply { frame(box(-1f, -1f, -1f, 1f, 1f, 1f)) }
        val before = eye(cam)
        cam.applyPreset(ViewPreset.TOP)
        assertTrue(cam.isAnimating)
        now += OrbitCamera.TRANSITION_NANOS / 2
        assertTrue("still running half way", cam.advance())
        val mid = eye(cam)
        assertTrue("moved part of the way", mid[2] > before[2])

        cam.rotate(1f, 0f, 1f) // a gesture interrupts in place
        assertFalse(cam.isAnimating)
        val afterInterrupt = eye(cam)
        now += OrbitCamera.TRANSITION_NANOS
        assertFalse(cam.advance())
        assertEquals("no jump after interruption", afterInterrupt[2], eye(cam)[2], 1e-5f)
    }

    @Test
    fun resetKeepsTheDefaultViewAndSphereFit() {
        val b = box(-10f, -20f, 0f, 10f, 20f, 5f)
        val cam = camera().apply { frame(b) }
        val initial = eye(cam)
        cam.applyPreset(ViewPreset.TOP); finish(cam)
        cam.reset(animated = true); finish(cam)
        val e = eye(cam)
        for (i in 0..2) assertEquals(initial[i], e[i], 1e-3f)
    }

    @Test
    fun orthographicRaysShareTheViewDirectionAndHitTheirPixel() {
        val b = box(-10f, -10f, -10f, 10f, 10f, 10f)
        val cam = camera().apply { frame(b); projection = Projection.ORTHOGRAPHIC }
        cam.applyPreset(ViewPreset.FRONT); finish(cam)
        val a = cam.unprojectRay(100f, 300f)
        val c = cam.unprojectRay(900f, 1700f)
        assertEquals(a.dx, c.dx, 1e-5f); assertEquals(a.dy, c.dy, 1e-5f); assertEquals(a.dz, c.dz, 1e-5f)
        assertEquals("front view looks along +Y", 1f, a.dy, 1e-4f)
        // Follow the ray to y = 0 and project back: it must land on the tapped pixel.
        val t = -a.oy / a.dy
        val p = cam.projectToScreen(a.ox + a.dx * t, 0f, a.oz + a.dz * t)!!
        assertEquals(100f, p[0], 0.5f)
        assertEquals(300f, p[1], 0.5f)
    }

    @Test
    fun mat4MatchesReferenceValues() {
        val p = FloatArray(16)
        Mat4.perspective(p, 90f, 2f, 1f, 3f)
        assertEquals(0.5f, p[0], 1e-6f); assertEquals(1f, p[5], 1e-6f)
        assertEquals(-2f, p[10], 1e-6f); assertEquals(-1f, p[11], 1e-6f); assertEquals(-3f, p[14], 1e-6f)
        val inv = FloatArray(16); val id = FloatArray(16)
        assertTrue(Mat4.invert(inv, p))
        Mat4.multiply(id, p, inv)
        for (i in 0 until 16) assertEquals(if (i % 5 == 0) 1f else 0f, id[i], 1e-5f)
    }
}
