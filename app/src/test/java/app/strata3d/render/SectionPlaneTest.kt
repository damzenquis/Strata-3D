package app.strata3d.render

import app.strata3d.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionPlaneTest {

    private val box = BoundingBox(-10f, 0f, 5f, 30f, 20f, 25f)

    @Test
    fun positionFollowsTheModelExtentAlongEachAxis() {
        assertEquals(10f, SectionPlane.at(box, SectionAxis.X, 0.5f, false).position, 1e-5f)
        assertEquals(0f, SectionPlane.at(box, SectionAxis.Y, 0f, false).position, 1e-5f)
        assertEquals(25f, SectionPlane.at(box, SectionAxis.Z, 1f, false).position, 1e-5f)
        // Out-of-range fractions are clamped to the model.
        assertEquals(25f, SectionPlane.at(box, SectionAxis.Z, 7f, false).position, 1e-5f)
    }

    @Test
    fun eachAxisCutsAlongItsWorldAxisAndFlipKeepsTheOtherSide() {
        val x = SectionPlane(SectionAxis.X, 2f)
        assertTrue(x.keeps(1f, 100f, -100f)); assertFalse(x.keeps(3f, 0f, 0f))
        val y = SectionPlane(SectionAxis.Y, 2f)
        assertTrue(y.keeps(-100f, 3f, 100f)); assertFalse(y.keeps(0f, 1f, 0f))
        val z = SectionPlane(SectionAxis.Z, 2f)
        assertTrue(z.keeps(100f, 100f, 1f)); assertFalse(z.keeps(0f, 0f, 3f))
        for (p in listOf(x, y, z)) {
            val f = p.copy(flipped = true)
            for (pt in listOf(floatArrayOf(1f, 1f, 1f), floatArrayOf(3f, 3f, 3f))) {
                assertTrue(p.keeps(pt[0], pt[1], pt[2]) != f.keeps(pt[0], pt[1], pt[2]))
            }
        }
        // Points on the plane are kept from both sides (no gap at the cut).
        assertTrue(z.keeps(0f, 0f, 2f) && z.copy(flipped = true).keeps(0f, 0f, 2f))
    }

    @Test
    fun equationMatchesKeeps() {
        val p = SectionPlane(SectionAxis.Y, -3.5f, flipped = true)
        val e = p.equation()
        for (y in listOf(-10f, -3.5f, 0f, 4f)) {
            assertEquals(p.keeps(0f, y, 0f), e[1] * y + e[3] >= 0f)
        }
    }

    @Test
    fun defaultSideRemovesTheHalfFacingTheDefaultCamera() {
        // Same conventions as the camera presets: the default eye looks from the front-right, above the model.
        val cam = OrbitCamera { 0L }.apply { setViewport(1000, 1000); frame(box) }
        val eye = FloatArray(3)
        cam.computeMatrices(FloatArray(16), FloatArray(16), eye)
        for (axis in SectionAxis.entries) {
            val plane = SectionPlane.at(box, axis, 0.5f, flipped = false)
            assertFalse("$axis: the camera should look at the cut face", plane.keeps(eye[0], eye[1], eye[2]))
        }
    }
}
