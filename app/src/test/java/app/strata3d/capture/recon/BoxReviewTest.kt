package app.strata3d.capture.recon

import app.strata3d.capture.recon.BoxReview.Camera
import app.strata3d.capture.recon.BoxReview.Handle
import app.strata3d.capture.recon.BoxReview.ObjectBox
import app.strata3d.capture.recon.BoxReview.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class BoxReviewTest {

    /**
     * A camera at [eye] looking horizontally along +y (world +z up): camera x = world x, camera y (down) = world -z,
     * camera z (forward) = world +y.
     */
    private fun cameraLookingAlongY(eye: V3, focal: Double = 0.8): Camera {
        val r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0)
        val t = doubleArrayOf(
            -(r[0] * eye.x + r[1] * eye.y + r[2] * eye.z),
            -(r[3] * eye.x + r[4] * eye.y + r[5] * eye.z),
            -(r[6] * eye.x + r[7] * eye.y + r[8] * eye.z),
        )
        return Camera(focal, r, t)
    }

    @Test fun `box file round trips and only centred boxes are reviewable`() {
        val b = ObjectBox(0.2747, 0.0061, 1.8416)
        assertEquals("-0.2747,-0.2747,0.0061,0.2747,0.2747,1.8416", b.encode())
        assertEquals(b, ObjectBox.parse(b.encode()))
        assertNull(ObjectBox.parse("-1,-1,-1,2,1,1"))          // not centred: a legacy box
        assertNull(ObjectBox.parse("1,2,3"))
        assertEquals(8, b.corners().size)
        assertEquals(12, b.edges.size)
    }

    @Test fun `meta ini parses and the camera centre is recovered`() {
        val ini = """
            [camera]
            focal_length = 0.8022771478
            pixel_aspect = 1
            principal_point = 0.5 0.5
            rotation = 1 0 0 0 0 -1 0 1 0
            translation = 0 0.5 5

            [view]
            id = 0
            name = 000001
        """.trimIndent()
        val c = Camera.parseMetaIni(ini)!!
        assertEquals(0.8022771478, c.focal, 1e-12)
        val ctr = c.centre
        assertEquals(0.0, ctr.x, 1e-12); assertEquals(-5.0, ctr.y, 1e-12); assertEquals(0.5, ctr.z, 1e-12)
        assertNull(Camera.parseMetaIni(ini.replace("focal_length = 0.8022771478", "focal_length = 0")))
    }

    @Test fun `projection puts the axis at the centre and lower points lower`() {
        val c = cameraLookingAlongY(V3(0.0, -5.0, 0.5))
        val w = 2066; val h = 1549
        val (u0, v0) = BoxReview.project(c, w, h, V3(0.0, 0.0, 0.5))!!
        assertEquals(w / 2.0, u0, 1e-9); assertEquals(h / 2.0, v0, 1e-9)
        val (_, vLow) = BoxReview.project(c, w, h, V3(0.0, 0.0, 0.0))!!
        assertEquals(h / 2.0 + 0.8 * w * 0.5 / 5.0, vLow, 1e-9)    // f = 0.8 x larger side; 0.5 below, 5 away
        assertNull(BoxReview.project(c, w, h, V3(0.0, -6.0, 0.5)))  // behind the camera
    }

    @Test fun `upright rotation maps the stored corners`() {
        // Stored 4 x 3, shown upright after 90 degrees clockwise (3 x 4): stored top-left goes to upright top-right.
        assertEquals(3.0 to 0.0, BoxReview.toUpright(0.0, 0.0, 4, 3, 90))
        assertEquals(0.0 to 4.0, BoxReview.toUpright(4.0, 3.0, 4, 3, 90))
        assertEquals(1.0 to 2.0, BoxReview.toUpright(1.0, 2.0, 4, 3, 0))
        assertEquals(3 to 4, BoxReview.uprightSize(4, 3, 90))
    }

    @Test fun `dragging the top handle up raises the top by the matching amount`() {
        val c = cameraLookingAlongY(V3(0.0, -5.0, 0.5))
        val w = 2066; val h = 1549
        val box = ObjectBox(0.3, 0.0, 1.0)
        val proj: (V3) -> Pair<Double, Double>? = { BoxReview.project(c, w, h, it) }
        // Raising the top by 0.1 moves its handle up by f * 0.1 / 5 pixels.
        val px = 0.8 * w * 0.1 / 5.0
        assertEquals(0.1, BoxReview.dragDelta(box, Handle.TOP, 0.0, -px, proj), 1e-6)
        assertEquals(-0.1, BoxReview.dragDelta(box, Handle.TOP, 0.0, px, proj), 1e-6)
        // A sideways drag does not change the top.
        assertEquals(0.0, BoxReview.dragDelta(box, Handle.TOP, 50.0, 0.0, proj), 1e-6)
        // The +x side handle: dragging right widens the box.
        assertTrue(BoxReview.dragDelta(box, Handle.SIDE_POS_X, 40.0, 0.0, proj) > 0)
        // The -y side faces the camera: its motion is along the line of sight, so it is not draggable here.
        assertEquals(0.0, BoxReview.dragDelta(box, Handle.SIDE_NEG_Y, 40.0, 40.0, proj), 1e-9)
    }

    @Test fun `adjust keeps the box valid`() {
        val b = ObjectBox(0.3, 0.0, 1.0)
        assertEquals(1.2, BoxReview.adjust(b, Handle.TOP, 0.2, 0.05).top, 1e-12)
        assertEquals(0.05, BoxReview.adjust(b, Handle.TOP, -5.0, 0.05).top, 1e-12)
        assertEquals(0.95, BoxReview.adjust(b, Handle.BOTTOM, 5.0, 0.05).bottom, 1e-12)
        assertEquals(0.025, BoxReview.adjust(b, Handle.SIDE_POS_X, -5.0, 0.05).radius, 1e-12)
    }

    @Test fun `review photos are spread around the object`() {
        val centres = (0 until 36).map { k -> val a = Math.toRadians(k * 10.0); V3(cos(a), sin(a), 0.5) }
        val picked = BoxReview.pickViews(centres, 3)
        assertEquals(listOf(0, 12, 24), picked)
        assertEquals(listOf(0), BoxReview.pickViews(centres.take(1), 3))
        assertEquals(emptyList<Int>(), BoxReview.pickViews(emptyList(), 3))
    }
}
