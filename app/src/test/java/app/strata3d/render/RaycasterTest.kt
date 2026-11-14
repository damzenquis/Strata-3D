package app.strata3d.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.strata3d.model.BoundingBox
import app.strata3d.model.MeshBuilder

class RaycasterTest {

    @Test
    fun testBoxIntersection() {
        val box = BoundingBox(minX = -10f, minY = -10f, minZ = -10f, maxX = 10f, maxY = 10f, maxZ = 10f)

        // Ray pointing straight at center
        val hitRay = Ray(ox = 0f, oy = 0f, oz = 30f, dx = 0f, dy = 0f, dz = -1f)
        assertTrue(Raycaster.intersectsBox(box, hitRay))

        // Ray pointing away
        val missRay = Ray(ox = 0f, oy = 0f, oz = 30f, dx = 0f, dy = 0f, dz = 1f)
        assertFalse(Raycaster.intersectsBox(box, missRay))

        // Ray off to the side
        val sideRay = Ray(ox = 30f, oy = 30f, oz = 0f, dx = 0f, dy = 1f, dz = 0f)
        assertFalse(Raycaster.intersectsBox(box, sideRay))
    }

    @Test
    fun testTriangleIntersectionExactCenter() {
        val builder = MeshBuilder(1)
        // Triangle on Z=0 plane: (0, 0), (10, 0), (0, 10)
        builder.addTriangle(floatArrayOf(
            0f, 0f, 0f,
            10f, 0f, 0f,
            0f, 10f, 0f,
        ))
        val mesh = builder.build()

        // Ray from (2, 2, 10) pointing down along -Z
        val ray = Ray(ox = 2f, oy = 2f, oz = 10f, dx = 0f, dy = 0f, dz = -1f)
        val hit = Raycaster.intersect(mesh, ray)

        assertNotNull(hit)
        assertEquals(2f, hit!!.point[0], 1e-4f)
        assertEquals(2f, hit.point[1], 1e-4f)
        assertEquals(0f, hit.point[2], 1e-4f)
        assertEquals(10f, hit.distance, 1e-4f)
    }

    @Test
    fun hitsNearACornerAreExactNotSnapped() {
        // Snapping is done in screen space by MeasurePicker; the raycaster always returns the exact point.
        val builder = MeshBuilder(1)
        builder.addTriangle(floatArrayOf(
            0f, 0f, 0f,
            10f, 0f, 0f,
            0f, 10f, 0f,
        ))
        val mesh = builder.build()
        val ray = Ray(ox = 0.05f, oy = 0.05f, oz = 5f, dx = 0f, dy = 0f, dz = -1f)
        val hit = Raycaster.intersect(mesh, ray)
        assertNotNull(hit)
        assertEquals(0.05f, hit!!.point[0], 1e-5f)
        assertEquals(0.05f, hit.point[1], 1e-5f)
        assertEquals(0f, hit.point[2], 1e-5f)
    }

    @Test
    fun parallelTestDoesNotDependOnModelScale() {
        for (size in floatArrayOf(1e-4f, 1f, 1e4f)) {
            val mesh = MeshBuilder(1).apply { addTriangle(floatArrayOf(0f, 0f, 0f, size, 0f, 0f, 0f, size, 0f)) }.build()
            val hit = Raycaster.intersect(mesh, Ray(size * 0.2f, size * 0.2f, size, 0f, 0f, -1f))
            assertNotNull("size $size", hit)
            // A ray in the triangle's plane never hits it.
            assertNull(Raycaster.intersect(mesh, Ray(-size, size * 0.2f, 0f, 1f, 0f, 0f)))
        }
    }

    @Test
    fun testRayMiss() {
        val builder = MeshBuilder(1)
        builder.addTriangle(floatArrayOf(
            0f, 0f, 0f,
            10f, 0f, 0f,
            0f, 10f, 0f,
        ))
        val mesh = builder.build()

        // Ray outside triangle
        val ray = Ray(ox = 8f, oy = 8f, oz = 5f, dx = 0f, dy = 0f, dz = -1f)
        val hit = Raycaster.intersect(mesh, ray)
        assertNull(hit)
    }
}
