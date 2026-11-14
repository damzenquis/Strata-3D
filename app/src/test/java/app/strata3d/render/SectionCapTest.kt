package app.strata3d.render

import app.strata3d.diagnostics.MeshAnalyzer
import app.strata3d.model.BoundingBox
import app.strata3d.model.GltfParser
import app.strata3d.model.Mesh
import app.strata3d.model.MeshBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Validates section capping. The stencil pass itself needs a GPU, so [filled] is a CPU reference of exactly the
 * rule it implements: along the view ray through a point of the cap, kept front faces count +1 and kept back faces
 * -1 (at any depth), and the cap is drawn where the sum is not zero. The policy ([SectionCap.reliability]) is then
 * checked against the real mesh check for each kind of geometry.
 */
class SectionCapTest {

    // ---- geometry helpers -------------------------------------------------------------------------------------

    private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): List<FloatArray> {
        val c = arrayOf(
            floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
            floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1),
        )
        val faces = arrayOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        return faces.map { c[it[0]] + c[it[1]] + c[it[2]] }
    }

    private fun flip(t: FloatArray) = floatArrayOf(t[0], t[1], t[2], t[6], t[7], t[8], t[3], t[4], t[5])
    private fun mesh(t: List<FloatArray>): Mesh = MeshBuilder(t.size).apply { t.forEach { addTriangle(it) } }.build()

    /**
     * CPU reference of the stencil count at cap point [p] for a camera looking along [dir] (from the removed side):
     * the sum over kept triangles crossed by the line through [p] of +1 (front-facing) or -1 (back-facing).
     */
    private fun winding(m: Mesh, plane: SectionPlane, p: FloatArray, dir: FloatArray): Int {
        val v = FloatArray(m.vertexCount * 3).also { m.vertices.duplicate().apply { position(0) }.get(it) }
        val far = 1e4f
        val o = floatArrayOf(p[0] - dir[0] * far, p[1] - dir[1] * far, p[2] - dir[2] * far)
        var sum = 0
        for (t in 0 until m.triangleCount) {
            val a = 9 * t
            val e1 = FloatArray(3) { v[a + 3 + it] - v[a + it] }
            val e2 = FloatArray(3) { v[a + 6 + it] - v[a + it] }
            val pv = floatArrayOf(dir[1] * e2[2] - dir[2] * e2[1], dir[2] * e2[0] - dir[0] * e2[2], dir[0] * e2[1] - dir[1] * e2[0])
            val det = e1[0] * pv[0] + e1[1] * pv[1] + e1[2] * pv[2]
            if (det == 0f) continue
            val tv = FloatArray(3) { o[it] - v[a + it] }
            val u = (tv[0] * pv[0] + tv[1] * pv[1] + tv[2] * pv[2]) / det
            if (u < 0f || u > 1f) continue
            val q = floatArrayOf(tv[1] * e1[2] - tv[2] * e1[1], tv[2] * e1[0] - tv[0] * e1[2], tv[0] * e1[1] - tv[1] * e1[0])
            val w = (dir[0] * q[0] + dir[1] * q[1] + dir[2] * q[2]) / det
            if (w < 0f || u + w > 1f) continue
            val dist = (e2[0] * q[0] + e2[1] * q[1] + e2[2] * q[2]) / det
            val hit = FloatArray(3) { o[it] + dir[it] * dist }
            if (!plane.keeps(hit[0], hit[1], hit[2])) continue // clipped away: discarded before the stencil
            // Counter-clockwise on screen (GL front face) means the geometric normal points back at the viewer.
            val n = floatArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
            sum += if (n[0] * dir[0] + n[1] * dir[1] + n[2] * dir[2] < 0f) 1 else -1
        }
        return sum
    }

    /** Looking down -Z at a horizontal cut z = [z] (the default Z section removes the top). */
    private fun filled(m: Mesh, x: Float, y: Float, z: Float): Boolean {
        val plane = SectionPlane(SectionAxis.Z, z)
        return winding(m, plane, floatArrayOf(x, y, z), floatArrayOf(0f, 0f, -1f)) != 0
    }

    private fun reliability(m: Mesh) = SectionCap.reliability(hasStencil = true, report = MeshAnalyzer.analyze(m))

    // ---- validation -------------------------------------------------------------------------------------------

    @Test
    fun closedSolidIsFilledInsideOnly() {
        val m = mesh(box(0f, 0f, 0f, 10f, 10f, 10f))
        assertTrue(filled(m, 5f, 5f, 5f))
        assertFalse(filled(m, 12f, 5f, 5f))
        assertEquals(SectionCap.Reliability.RELIABLE, reliability(m))
    }

    @Test
    fun hollowSolidFillsOnlyTheWall() {
        val m = mesh(box(0f, 0f, 0f, 10f, 10f, 10f) + box(3f, 3f, 3f, 7f, 7f, 7f).map(::flip))
        assertTrue("wall", filled(m, 1.5f, 5f, 5f))
        assertFalse("cavity", filled(m, 5f, 5f, 5f))
        assertEquals(SectionCap.Reliability.RELIABLE, reliability(m)) // a cavity is not a defect
    }

    @Test
    fun overlappingShellsFillTheirUnion() {
        // Two closed cubes that overlap (winding 2 in the overlap); simple parity would leave the overlap empty.
        val m = mesh(box(0f, 0f, 0f, 10f, 10f, 10f) + box(5f, 5f, 0f, 15f, 15f, 10f))
        assertTrue("first only", filled(m, 2f, 2f, 5f))
        assertTrue("overlap", filled(m, 7f, 7f, 5f))
        assertTrue("second only", filled(m, 13f, 13f, 5f))
        assertFalse("outside both", filled(m, 13f, 2f, 5f))
        // Separate shells are equally fine.
        val apart = mesh(box(0f, 0f, 0f, 4f, 4f, 4f) + box(10f, 0f, 0f, 14f, 4f, 4f))
        assertTrue(filled(apart, 12f, 2f, 2f)); assertFalse(filled(apart, 7f, 2f, 2f))
    }

    @Test
    fun insideOutSolidIsStillFilled() {
        val m = mesh(box(0f, 0f, 0f, 10f, 10f, 10f).map(::flip))
        assertTrue(filled(m, 5f, 5f, 5f))
        assertEquals(SectionCap.Reliability.RELIABLE, reliability(m))
    }

    @Test
    fun transformedGltfIsCappedInWorldSpace() {
        // A 10-unit cube in a glTF node scaled by 2 and moved by +100 on X: the cut uses the transformed geometry.
        val cube = box(0f, 0f, 0f, 10f, 10f, 10f)
        val bin = ByteBuffer.allocate(cube.size * 36).order(ByteOrder.LITTLE_ENDIAN).apply { cube.forEach { t -> t.forEach { putFloat(it) } } }.array()
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
            "nodes":[{"children":[1],"translation":[100,0,0]},{"mesh":0,"scale":[2,2,2]}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":${cube.size * 3},"type":"VEC3"}],
            "bufferViews":[{"buffer":0,"byteLength":${bin.size}}],
            "buffers":[{"byteLength":${bin.size},"uri":"data:application/octet-stream;base64,${Base64.getEncoder().encodeToString(bin)}"}]}"""
        val m = GltfParser.parse(ByteArrayInputStream(json.toByteArray()), upright = false)
        assertEquals(100f, m.bounds.minX, 1e-3f); assertEquals(20f, m.bounds.sizeX, 1e-3f)
        assertTrue(filled(m, 115f, 15f, 10f))
        assertFalse(filled(m, 5f, 5f, 10f))   // where the cube would be without its transforms
        assertEquals(SectionCap.Reliability.RELIABLE, reliability(m))
    }

    @Test
    fun openAndNonManifoldGeometryFallsBackToUncapped() {
        val open = mesh(box(0f, 0f, 0f, 10f, 10f, 10f).drop(1))
        assertEquals(SectionCap.Reliability.UNSUITABLE_GEOMETRY, reliability(open))
        val fin = mesh(box(0f, 0f, 0f, 10f, 10f, 10f) + listOf(floatArrayOf(10f, 0f, 0f, 10f, 0f, 10f, 15f, -5f, 5f)))
        assertEquals(SectionCap.Reliability.UNSUITABLE_GEOMETRY, reliability(fin))
        val flipped = mesh(box(0f, 0f, 0f, 10f, 10f, 10f).toMutableList().also { it[4] = flip(it[4]) })
        assertEquals(SectionCap.Reliability.UNSUITABLE_GEOMETRY, reliability(flipped))
        // Without a mesh check, or without a stencil buffer, the cut is never filled.
        assertEquals(SectionCap.Reliability.UNKNOWN_GEOMETRY, SectionCap.reliability(true, null))
        assertEquals(SectionCap.Reliability.NO_STENCIL, SectionCap.reliability(false, MeshAnalyzer.analyze(open)))
    }

    @Test
    fun capQuadLiesOnThePlaneAndCoversTheModel() {
        val b = BoundingBox(-5f, 0f, 2f, 5f, 8f, 12f)
        for (axis in SectionAxis.entries) {
            val plane = SectionPlane.at(b, axis, 0.3f, false)
            val q = SectionCap.quad(b, plane)
            assertEquals(18, q.size)
            val a = axis.ordinal
            val lo = floatArrayOf(b.minX, b.minY, b.minZ); val hi = floatArrayOf(b.maxX, b.maxY, b.maxZ)
            for (k in 0 until 3) {
                val values = (0 until 6).map { q[it * 3 + k] }
                if (k == a) assertTrue(values.all { it == plane.position })
                else { assertTrue(values.min() < lo[k]); assertTrue(values.max() > hi[k]) }
            }
        }
    }

    @Test
    fun capColourContrastsWithTheModel() {
        val orangeModel = floatArrayOf(0.95f, 0.55f, 0.20f)
        val blueModel = floatArrayOf(0.36f, 0.60f, 0.90f)
        assertTrue(SectionCap.color(blueModel)[0] > 0.9f)      // orange cap on a blue model
        assertTrue(SectionCap.color(orangeModel)[2] > 0.9f)    // blue cap on an orange model
    }
}
