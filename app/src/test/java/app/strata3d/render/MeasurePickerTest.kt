package app.strata3d.render

import app.strata3d.model.Mesh
import app.strata3d.model.MeshBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurePickerTest {

    private var now = 0L
    private val radiusPx = 40f

    /** Adds an axis-aligned box as 12 triangles. */
    private fun MeshBuilder.box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        val c = arrayOf(
            floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
            floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1),
        )
        val faces = arrayOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        for (f in faces) addTriangle(c[f[0]] + c[f[1]] + c[f[2]])
    }

    private fun mesh(block: MeshBuilder.() -> Unit): Mesh = MeshBuilder(12).apply(block).build()

    private fun camera(mesh: Mesh, preset: ViewPreset? = null, ortho: Boolean = false): OrbitCamera =
        OrbitCamera { now }.apply {
            setViewport(1000, 1000)
            frame(mesh.bounds)
            if (ortho) projection = Projection.ORTHOGRAPHIC
            if (preset != null) {
                applyPreset(preset)
                now += OrbitCamera.TRANSITION_NANOS + 1 // finish the transition
                advance()
            }
        }

    private fun pick(mesh: Mesh, cam: OrbitCamera, x: Float, y: Float, snap: Boolean = true) =
        MeasurePicker.pick(mesh, cam.pickFrame(), cam.unprojectRay(x, y), x, y, radiusPx, snap)

    private fun screen(cam: OrbitCamera, p: FloatArray): FloatArray = cam.projectToScreen(p[0], p[1], p[2])!!

    @Test
    fun snapsToAVisibleVertexNearTheTap() {
        val m = mesh { box(0f, 0f, 0f, 10f, 10f, 10f) }
        val cam = camera(m)
        val corner = floatArrayOf(10f, 0f, 10f) // front-right top corner, visible from the default view
        val s = screen(cam, corner)
        val hit = pick(m, cam, s[0] + 6f, s[1] + 8f)
        assertNotNull(hit)
        assertTrue(hit!!.snapped)
        assertArrayEquals(corner, hit.point, 1e-5f)
    }

    @Test
    fun hiddenBackVertexIsNeverPickedEvenWhenItProjectsOnTheTap() {
        // Orthographic front view: each back corner projects exactly onto the front corner in front of it.
        val m = mesh { box(0f, 0f, 0f, 10f, 10f, 10f) }
        val cam = camera(m, ViewPreset.FRONT, ortho = true)
        val front = floatArrayOf(10f, 0f, 10f)
        val s = screen(cam, front)
        val hit = pick(m, cam, s[0] - 5f, s[1] + 5f)
        assertNotNull(hit)
        assertTrue(hit!!.snapped)
        // The front view looks along +Y, so the visible face is y = 0.
        assertEquals(0f, hit.point[1], 1e-5f)
    }

    @Test
    fun vertexBehindAnotherPartIsRejected() {
        // A small box hidden behind a large one (front view, looking along +Y).
        val m = mesh {
            box(0f, 0f, 0f, 20f, 2f, 20f)        // wall in front
            box(8f, 10f, 8f, 12f, 14f, 12f)      // small box behind the wall
        }
        val cam = camera(m, ViewPreset.FRONT, ortho = true)
        val hidden = floatArrayOf(12f, 10f, 12f)
        val s = screen(cam, hidden)
        val hit = pick(m, cam, s[0], s[1])
        assertNotNull(hit)
        // Not the hidden vertex: the tap lands on the wall's front face (y = 0) as a free point.
        assertFalse(hit!!.snapped)
        assertEquals(0f, hit.point[1], 1e-4f)
    }

    @Test
    fun freeSurfacePointAwayFromVerticesAndWithSnapOff() {
        val m = mesh { box(0f, 0f, 0f, 100f, 100f, 100f) }
        val cam = camera(m, ViewPreset.TOP, ortho = true)
        val center = screen(cam, floatArrayOf(50f, 50f, 100f))
        val free = pick(m, cam, center[0], center[1])!!
        assertFalse(free.snapped)
        assertArrayEquals(floatArrayOf(50f, 50f, 100f), free.point, 1e-3f)

        // Right next to a corner, snap off keeps the exact surface point.
        val corner = screen(cam, floatArrayOf(100f, 100f, 100f))
        val exact = pick(m, cam, corner[0] - 10f, corner[1] + 10f, snap = false)!!
        assertFalse(exact.snapped)
        assertTrue(exact.point[0] < 100f && exact.point[1] < 100f)
        assertEquals(100f, exact.point[2], 1e-3f)
    }

    @Test
    fun missingTheModelPicksNothing() {
        val m = mesh { box(0f, 0f, 0f, 10f, 10f, 10f) }
        val cam = camera(m, ViewPreset.TOP, ortho = true)
        assertNull(pick(m, cam, 2f, 2f))
    }

    @Test
    fun tinyMetreScaleModelsArePickable() {
        // A 2 mm part stored in metres with 0.1 mm features: the ray test must not depend on absolute size.
        val m = mesh {
            box(0f, 0f, 0f, 0.002f, 0.002f, 0.002f)
            box(0.0009f, 0.0009f, 0.002f, 0.0010f, 0.0010f, 0.0021f)
        }
        val cam = camera(m, ViewPreset.TOP, ortho = true)
        val p = screen(cam, floatArrayOf(0.0005f, 0.0005f, 0.002f))
        val hit = pick(m, cam, p[0], p[1], snap = false)
        assertNotNull(hit)
        assertEquals(0.002f, hit!!.point[2], 1e-7f)
    }

    @Test
    fun candidatesAreDistinctAndNearestFirst() {
        val m = mesh { box(0f, 0f, 0f, 10f, 10f, 10f) }
        val cam = camera(m)
        val corner = floatArrayOf(10f, 0f, 10f)
        val s = screen(cam, corner)
        val list = MeasurePicker.nearestProjectedVertices(m, cam.pickFrame(), s[0], s[1], 2000f, 8)
        assertEquals(8, list.size) // 8 distinct corners although each appears in several triangles
        assertArrayEquals(corner, list[0], 0f)
    }

    // ---- section integration -----------------------------------------------------------------------------

    private val cutAt5 = SectionPlane(SectionAxis.Z, 5f) // keeps z <= 5, the top half is cut away

    @Test
    fun uncappedCutShowsAndPicksTheInnerSurface() {
        val m = mesh { box(0f, 0f, 0f, 10f, 10f, 10f) }
        val down = Ray(5f, 5f, 50f, 0f, 0f, -1f)
        val hit = Raycaster.intersect(m, down, cutAt5, capped = false)!!
        assertFalse(hit.onCap)
        assertEquals(0f, hit.point[2], 1e-4f) // the floor, seen from inside through the open cut
        assertEquals(10f, Raycaster.intersect(m, down)!!.point[2], 1e-4f) // without a section: the top face
    }

    @Test
    fun filledCutHidesWhatIsBehindItAndIsNeverPicked() {
        val m = mesh { box(0f, 0f, 0f, 10f, 10f, 10f) }
        val hit = Raycaster.intersect(m, Ray(5f, 5f, 50f, 0f, 0f, -1f), cutAt5, capped = true)!!
        assertTrue(hit.onCap)
        assertEquals(5f, hit.point[2], 1e-4f)
        val cam = camera(m, ViewPreset.TOP, ortho = true)
        val c = screen(cam, floatArrayOf(5f, 5f, 5f))
        val frame = cam.pickFrame()
        assertNull(MeasurePicker.pick(m, frame, cam.unprojectRay(c[0], c[1]), c[0], c[1], radiusPx, true, cutAt5, capped = true))
        assertNull(MeasurePicker.pick(m, frame, cam.unprojectRay(c[0], c[1]), c[0], c[1], radiusPx, false, cutAt5, capped = true))
    }

    @Test
    fun hollowCavityStaysOpenToPicking() {
        val outer = MeshBuilder(24).apply { box(0f, 0f, 0f, 30f, 30f, 30f) }
        // Inner box wound inwards (a modelled cavity): reverse each triangle of a normal box.
        val inner = MeshBuilder(12).apply { box(10f, 10f, 10f, 20f, 20f, 20f) }.build()
        val v = FloatArray(inner.vertexCount * 3).also { inner.vertices.duplicate().apply { position(0) }.get(it) }
        for (t in 0 until inner.triangleCount) {
            val o = t * 9
            outer.addTriangle(floatArrayOf(v[o], v[o + 1], v[o + 2], v[o + 6], v[o + 7], v[o + 8], v[o + 3], v[o + 4], v[o + 5]))
        }
        val m = outer.build()
        val cut = SectionPlane(SectionAxis.Z, 15f)
        // Over the wall: the fill. Over the cavity: the cavity floor (z = 10), as drawn.
        assertTrue(Raycaster.intersect(m, Ray(5f, 5f, 99f, 0f, 0f, -1f), cut, capped = true)!!.onCap)
        val floor = Raycaster.intersect(m, Ray(15f, 15f, 99f, 0f, 0f, -1f), cut, capped = true)!!
        assertFalse(floor.onCap)
        assertEquals(10f, floor.point[2], 1e-4f)
    }

    @Test
    fun keptWallInFrontOfTheCutIsPickedNormally() {
        val m = mesh { box(0f, 0f, 0f, 10f, 10f, 10f) }
        // From the side, slightly upwards: meets the x = 0 wall at z = 4 (kept) before the cut plane.
        val ray = Ray(-10f, 5f, 3f, 0.995037f, 0f, 0.0995037f)
        val hit = Raycaster.intersect(m, ray, cutAt5, capped = true)!!
        assertFalse(hit.onCap)
        assertEquals(0f, hit.point[0], 1e-3f)
        assertEquals(4f, hit.point[2], 1e-2f)
    }

    @Test
    fun cutAwayAndHiddenVerticesAreNeverSnapped() {
        val m = mesh { box(0f, 0f, 0f, 10f, 10f, 10f) }
        val cam = camera(m)
        val frame = cam.pickFrame()
        val top = floatArrayOf(10f, 0f, 10f)
        val s = screen(cam, top)
        val all = MeasurePicker.nearestProjectedVertices(m, frame, s[0], s[1], 5000f, 8, cutAt5)
        assertTrue(all.isNotEmpty() && all.all { it[2] <= 5f }) // the top corners are cut away
        // A bottom corner is visible past the open cut; with the fill on, a corner behind the fill is rejected.
        val bottomBack = floatArrayOf(0f, 10f, 0f) // far corner, seen through the cut from the default view
        assertTrue(MeasurePicker.isVisible(m, frame, bottomBack, cutAt5, capped = false))
        assertFalse(MeasurePicker.isVisible(m, frame, bottomBack, cutAt5, capped = true))
    }

    /**
     * Performance on a 1M-triangle mesh (JVM; Android numbers are measured on the emulator and reported
     * separately). Prints the timing; the bound is generous so slow CI machines do not fail.
     */
    @Test
    fun pickingAMillionTrianglesStaysInteractive() {
        val n = 1_000_000
        val b = MeshBuilder(n)
        val side = 708 // ~ sqrt(n/2) cells
        val cell = 100f / side
        var count = 0
        outer@ for (i in 0 until side) for (j in 0 until side) {
            val x0 = i * cell; val y0 = j * cell; val x1 = x0 + cell; val y1 = y0 + cell
            val z = ((i * 7 + j * 13) % 5) * 0.01f
            b.addTriangle(floatArrayOf(x0, y0, z, x1, y0, z, x1, y1, z)); count++
            if (count == n) break@outer
            b.addTriangle(floatArrayOf(x0, y0, z, x1, y1, z, x0, y1, z)); count++
            if (count == n) break@outer
        }
        val m = b.build()
        val cam = camera(m)
        val target = screen(cam, floatArrayOf(50f, 50f, 0f))
        pick(m, cam, target[0], target[1]) // warm-up
        val runs = 5
        val start = System.nanoTime()
        repeat(runs) { assertNotNull(pick(m, cam, target[0], target[1])) }
        val ms = (System.nanoTime() - start) / 1e6 / runs
        println("MeasurePicker: ${m.triangleCount} triangles, snap on: %.1f ms per pick".format(ms))
        assertTrue("pick took $ms ms", ms < 3000)
    }
}
