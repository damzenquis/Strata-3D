package app.strata3d.capture.recon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ColmapExportTest {

    private fun meta(id: Int, r: String, t: String, focal: Double = 1.0) = """
        [camera]
        focal_length = $focal
        pixel_aspect = 1
        principal_point = 0.5 0.5
        radial_distortion = 0.1 0.2
        rotation = $r
        translation = $t

        [view]
        id = $id
        name = 0000$id
    """.trimIndent()

    @Test fun `view intrinsics come from meta ini and the undistorted size`() {
        val v = ColmapExport.parseView(meta(3, "1 0 0 0 1 0 0 0 1", "0 0 5", 0.8), "view_0003.mve.png", 2040, 1530)!!
        assertEquals(3, v.id)
        assertEquals(0.8 * 2040, v.fx, 1e-9)
        assertEquals(1020.0, v.cx, 1e-9)
        assertEquals(765.0, v.cy, 1e-9)
        assertNull(ColmapExport.parseView("[view]\nid = 4\nname = x\n", "x.png", 10, 10))   // not placed
    }

    @Test fun `quaternion of known rotations`() {
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0), ColmapExport.quaternion(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)), 1e-12)
        val a = Math.toRadians(90.0)   // about z
        val q = ColmapExport.quaternion(doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0))
        assertArrayEquals(doubleArrayOf(cos(a / 2), 0.0, 0.0, sin(a / 2)), q, 1e-12)
        val q180 = ColmapExport.quaternion(doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0))   // 180° about x
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 0.0, 0.0), q180, 1e-12)
    }

    @Test fun `projection, and points behind or outside are dropped`() {
        val v = ColmapExport.parseView(meta(0, "1 0 0 0 1 0 0 0 1", "0 0 5"), "a.png", 100, 100)!!   // f = 100 px
        val p = ColmapExport.project(v, listOf(0.5, -0.5, 0.0))!!
        assertEquals(60.0, p.first, 1e-9)    // 100 * 0.5 / 5 + 50
        assertEquals(40.0, p.second, 1e-9)
        assertNull(ColmapExport.project(v, listOf(0.0, 0.0, -6.0)))   // behind
        assertNull(ColmapExport.project(v, listOf(10.0, 0.0, 0.0)))   // outside the image
    }

    @Test fun `model files from a two-camera bundle`() {
        val a = ColmapExport.parseView(meta(0, "1 0 0 0 1 0 0 0 1", "0 0 5"), "view_0000.mve.png", 100, 100)!!
        val b = ColmapExport.parseView(meta(2, "1 0 0 0 1 0 0 0 1", "-1 0 5"), "view_0002.mve.png", 100, 100)!!
        // Header, 3 cameras (5 lines each; camera 1 unplaced), 2 points: one seen by both, one only by camera 1.
        val cam = "1 0 0\n1 0 0\n0 1 0\n0 0 1\n0 0 5\n"
        val synth = "drews 1.0\n3 2\n" + cam + "0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n" + cam +
            "0.5 0 0\n10 20 30\n2 0 7 0 2 9 0\n" +
            "0 0 0\n1 2 3\n2 1 1 0 0 2 0\n"
        val m = ColmapExport.build(listOf(a, b), synth)
        assertEquals(1, m.pointCount)   // the second point keeps only one observation (camera 1 isn't placed)
        assertEquals("1 PINHOLE 100 100 100.000000 100.000000 50.000000 50.000000\n3 PINHOLE 100 100 100.000000 100.000000 50.000000 50.000000\n", m.cameras)
        assertEquals("1 0.500000 0.000000 0.000000 10 20 30 0 1 0 3 0\n", m.points)
        val images = m.images.lines()
        assertEquals("1 1.0000000000 0.0000000000 0.0000000000 0.0000000000 0.0000000000 0.0000000000 5.0000000000 1 view_0000.mve.png", images[0])
        assertEquals("60.00 50.00 1", images[1])
        assertEquals("40.00 50.00 1", images[3])   // camera 3: x = 0.5 - 1 = -0.5 → 100 * -0.1 + 50
        assertNotNull(images[2])
    }
}
