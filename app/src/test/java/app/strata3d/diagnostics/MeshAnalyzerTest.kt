package app.strata3d.diagnostics

import app.strata3d.model.JsonParser
import app.strata3d.model.Mesh
import app.strata3d.model.MeshBuilder
import app.strata3d.model.ModelParser
import app.strata3d.model.ModelResources
import app.strata3d.model.asList
import app.strata3d.model.asString
import app.strata3d.model.get
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MeshAnalyzerTest {

    // ---- generated meshes -------------------------------------------------------------------------------------

    /** The 12 triangles of an axis-aligned box, wound outwards. */
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

    private fun cube(size: Float = 10f) = box(0f, 0f, 0f, size, size, size)

    private fun flip(t: FloatArray) = floatArrayOf(t[0], t[1], t[2], t[6], t[7], t[8], t[3], t[4], t[5])

    private fun mesh(triangles: List<FloatArray>): Mesh =
        MeshBuilder(triangles.size).apply { triangles.forEach { addTriangle(it) } }.build()

    private fun analyze(triangles: List<FloatArray>) = MeshAnalyzer.analyze(mesh(triangles))

    // ---- valid ------------------------------------------------------------------------------------------------

    @Test
    fun closedCubeHasNoFindings() {
        val r = analyze(cube())
        assertTrue(r.findings.isEmpty())
        assertFalse(r.hasIssues)
        assertEquals(8, r.vertexCount)       // 36 render corners weld to 8 vertices
        assertEquals(18, r.edgeCount)
        assertEquals(1, r.shells.size)
        val shell = r.shells[0]
        assertTrue(shell.closed && shell.consistent)
        assertEquals(1000.0, shell.signedVolume!!, 1e-6)
    }

    @Test
    fun separateClosedPartsAreShellsNotIssues() {
        val r = analyze(cube() + box(20f, 0f, 0f, 25f, 5f, 5f))
        assertFalse(r.hasIssues)
        assertEquals(2, r.shells.size)
        assertEquals(setOf(1000.0, 125.0), r.shells.map { Math.round(it.signedVolume!!).toDouble() }.toSet())
        // Every triangle maps to its own shell.
        assertTrue(r.topology.triangleShell.take(12).all { it == r.topology.triangleShell[0] })
        assertTrue(r.topology.triangleShell.drop(12).all { it != r.topology.triangleShell[0] })
    }

    @Test
    fun nearlyCoincidentCornersWeldWithinTolerance() {
        // Float noise far below the tolerance (1e-6 of the diagonal), as left by transforms or exporters that split
        // vertices along UV seams: still one closed solid.
        val noisy = cube(10f).mapIndexed { i, t -> FloatArray(9) { k -> t[k] + if ((i + k) % 2 == 0) 1e-7f else 0f } }
        val r = analyze(noisy)
        assertTrue(r.findings.isEmpty())
        assertEquals(8, r.vertexCount)
    }

    @Test
    fun hollowSolidReportsACavityNotADefect() {
        // Outer box wound outwards, inner box wound inwards (a correctly modelled hollow).
        val r = analyze(cube(10f) + box(3f, 3f, 3f, 7f, 7f, 7f).map(::flip))
        assertFalse(r.hasIssues)
        val cavity = r.finding(IssueType.INTERNAL_CAVITY)!!
        assertEquals(Severity.INFO, cavity.severity)
        assertEquals(Certainty.UNCERTAIN, cavity.certainty)
        assertEquals(12, cavity.triangles.size)
        assertTrue(cavity.triangles.all { it >= 12 })
    }

    // ---- open ------------------------------------------------------------------------------------------------

    @Test
    fun missingTriangleGivesBoundaryEdgesReferencingTheGeometry() {
        val tris = cube().toMutableList().also { it.removeAt(3) }
        val m = mesh(tris)
        val r = MeshAnalyzer.analyze(m)
        val open = r.finding(IssueType.BOUNDARY_EDGES)!!
        assertEquals(3, open.count)
        assertEquals(Certainty.CONFIRMED, open.certainty)
        assertTrue(open.explanation.contains("1 open outline"))
        assertTrue(r.shells.single().signedVolume == null) // volume is not claimed for an open shell
        // Each edge reference names two corners of the original buffer that lie on the removed triangle.
        val removed = cube()[3]
        val removedPoints = (0 until 3).map { listOf(removed[it * 3], removed[it * 3 + 1], removed[it * 3 + 2]) }.toSet()
        for (slot in open.edges) {
            val tri = slot / 3; val k = slot % 3
            for (corner in listOf(tri * 3 + k, tri * 3 + (k + 1) % 3)) {
                val p = listOf(m.vertices.get(corner * 3), m.vertices.get(corner * 3 + 1), m.vertices.get(corner * 3 + 2))
                assertTrue("$p on removed triangle", p in removedPoints)
            }
        }
    }

    @Test
    fun singleTriangleIsOpen() {
        val r = analyze(listOf(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)))
        assertEquals(3, r.finding(IssueType.BOUNDARY_EDGES)!!.count)
        assertNull(r.finding(IssueType.INVERTED_SHELL))
    }

    // ---- non-manifold ----------------------------------------------------------------------------------------

    @Test
    fun finOnAnEdgeIsNonManifold() {
        // A third triangle hanging off the cube's edge (10,0,0)-(10,0,10).
        val fin = floatArrayOf(10f, 0f, 0f, 10f, 0f, 10f, 15f, -5f, 5f)
        val r = analyze(cube() + listOf(fin))
        val nm = r.finding(IssueType.NON_MANIFOLD_EDGES)!!
        assertEquals(1, nm.count)                    // one edge...
        assertEquals(3, nm.edges.size)               // ...used by three triangles
        assertEquals(Severity.ERROR, nm.severity)
        assertTrue(r.hasIssues)
        assertNull(r.shells.single().signedVolume)
    }

    @Test
    fun twoCubesSharingAnEdgeAreNonManifold() {
        val r = analyze(cube(10f) + box(10f, 10f, 0f, 20f, 20f, 10f))
        assertEquals(1, r.finding(IssueType.NON_MANIFOLD_EDGES)!!.count)
    }

    // ---- inverted and flipped --------------------------------------------------------------------------------

    @Test
    fun insideOutCubeIsInverted() {
        val r = analyze(cube().map(::flip))
        val inv = r.finding(IssueType.INVERTED_SHELL)!!
        assertEquals(Certainty.CONFIRMED, inv.certainty)
        assertEquals(12, inv.triangles.size)
        assertEquals(-1000.0, r.shells.single().signedVolume!!, 1e-6)
        assertNull(r.finding(IssueType.FLIPPED_TRIANGLES))
    }

    @Test
    fun oneFlippedTriangleOnAClosedSolidIsConfirmed() {
        val tris = cube().toMutableList().also { it[7] = flip(it[7]) }
        val r = analyze(tris)
        val f = r.finding(IssueType.FLIPPED_TRIANGLES)!!
        assertEquals(Certainty.CONFIRMED, f.certainty)
        assertArrayEquals(intArrayOf(7), f.triangles)
        assertEquals(6, f.edges.size) // its 3 edges, each seen from both triangles
        assertNull(r.finding(IssueType.BOUNDARY_EDGES))
    }

    @Test
    fun mostlyInvertedSolidMarksTheInwardTriangles() {
        // 11 of 12 triangles inward: the 11 are the flipped ones, decided by volume, not by majority.
        val tris = cube().map(::flip).toMutableList().also { it[0] = cube()[0] }
        val f = analyze(tris).finding(IssueType.FLIPPED_TRIANGLES)!!
        assertEquals(Certainty.CONFIRMED, f.certainty)
        assertEquals(11, f.triangles.size)
        assertFalse(0 in f.triangles)
    }

    @Test
    fun flipOnAnOpenSurfaceIsOnlyLikely() {
        // A 3x3 grid of quads (open sheet) with one triangle flipped.
        val tris = ArrayList<FloatArray>()
        for (i in 0 until 3) for (j in 0 until 3) {
            val x = i.toFloat(); val y = j.toFloat()
            tris += floatArrayOf(x, y, 0f, x + 1, y, 0f, x + 1, y + 1, 0f)
            tris += floatArrayOf(x, y, 0f, x + 1, y + 1, 0f, x, y + 1, 0f)
        }
        tris[8] = flip(tris[8])
        val f = analyze(tris).finding(IssueType.FLIPPED_TRIANGLES)!!
        assertEquals(Certainty.UNCERTAIN, f.certainty)
        assertArrayEquals(intArrayOf(8), f.triangles)
    }

    @Test
    fun moebiusStripIsNonOrientable() {
        val n = 24
        val top = ArrayList<FloatArray>(); val bot = ArrayList<FloatArray>()
        for (i in 0 until n) {
            val a = 2 * PI * i / n; val half = a / 2
            val r = 10.0; val w = 2.0
            fun p(s: Double) = floatArrayOf(
                ((r + s * cos(half)) * cos(a)).toFloat(), ((r + s * cos(half)) * sin(a)).toFloat(), (s * sin(half)).toFloat(),
            )
            top += p(w); bot += p(-w)
        }
        val tris = ArrayList<FloatArray>()
        for (i in 0 until n) {
            val t0 = top[i]; val b0 = bot[i]
            // The last segment joins back with the strip turned over.
            val t1 = if (i + 1 < n) top[i + 1] else bot[0]
            val b1 = if (i + 1 < n) bot[i + 1] else top[0]
            tris += t0 + b0 + b1
            tris += t0 + b1 + t1
        }
        val r = analyze(tris)
        val no = r.finding(IssueType.NON_ORIENTABLE)!!
        assertEquals(1, no.count)
        assertEquals(Severity.ERROR, no.severity)
        assertNull(r.finding(IssueType.INVERTED_SHELL))
    }

    // ---- degenerate ------------------------------------------------------------------------------------------

    @Test
    fun collapsedAndCollinearTrianglesAreDegenerate() {
        val collapsed = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 0f, 10f, 10f)  // two equal corners
        val collinear = floatArrayOf(0f, 0f, 10f, 5f, 5f, 10f, 10f, 10f, 10f)       // on the top face's diagonal
        val r = analyze(cube() + listOf(collapsed, collinear))
        val d = r.finding(IssueType.DEGENERATE_TRIANGLES)!!
        assertEquals(2, d.count)
        assertArrayEquals(intArrayOf(12, 13), d.triangles)
        // The collapsed triangle takes no part in the topology; the cube itself is still analyzed.
        assertEquals(-1, r.topology.triangleShell[12])
    }

    @Test
    fun tinyButValidTrianglesAreNotDegenerate() {
        // A 1 µm cube in metres: small absolute areas, perfectly valid shape.
        val r = analyze(cube(1e-6f))
        assertNull(r.finding(IssueType.DEGENERATE_TRIANGLES))
        assertTrue(r.findings.isEmpty())
    }

    // ---- versioning, cancellation and the original geometry --------------------------------------------------

    @Test
    fun reportIsOnlyValidForTheGeometryItAnalyzed() {
        val a = mesh(cube())
        val r = MeshAnalyzer.analyze(a)
        assertTrue(r.isValidFor(MeshFingerprint.of(mesh(cube()))))          // same coordinates, new object
        val moved = cube().toMutableList().also { it[5] = it[5].copyOf().also { t -> t[0] += 0.001f } }
        assertFalse(r.isValidFor(MeshFingerprint.of(mesh(moved))))
        assertNotEquals(MeshFingerprint.of(a), MeshFingerprint.of(mesh(cube().map(::flip))))
    }

    @Test
    fun cancellationStopsTheAnalysis() {
        var polls = 0
        assertThrows(CancellationException::class.java) {
            MeshAnalyzer.analyze(mesh(cube()), isCancelled = { ++polls > 1 })
        }
    }

    @Test
    fun analysisNeverChangesTheMesh() {
        val m = mesh(cube().toMutableList().also { it[2] = flip(it[2]); it.removeAt(5) })
        val before = FloatArray(m.vertexCount * 3).also { m.vertices.duplicate().apply { position(0) }.get(it) }
        val position = m.vertices.position()
        MeshAnalyzer.analyze(m)
        val after = FloatArray(m.vertexCount * 3).also { m.vertices.duplicate().apply { position(0) }.get(it) }
        assertArrayEquals(before, after, 0f)
        assertEquals(position, m.vertices.position())
    }

    @Test
    fun hashMapGivesDenseIdsAndGrows() {
        val map = LongIntMap(1)
        val keys = LongArray(100_000) { (it.toLong() * 7_919L) shl 21 or (it.toLong() and 0x1FFFFF) }
        for ((i, k) in keys.withIndex()) assertEquals(i, map.getOrAdd(k))
        for ((i, k) in keys.withIndex()) assertEquals(i, map.getOrAdd(k)) // stable after growing
        assertEquals(keys.size, map.size)
        assertEquals(keys.size, map.getOrAdd(0L shl 21 or 123_456L))      // a new key gets the next id
    }

    // ---- performance and the format suite --------------------------------------------------------------------

    /** A closed torus with [rings] x [sides] quads. */
    private fun torusMesh(rings: Int, sides: Int): Mesh {
        val b = MeshBuilder(rings * sides * 2)
        fun p(i: Int, j: Int): FloatArray {
            val u = 2 * PI * (i % rings) / rings; val v = 2 * PI * (j % sides) / sides
            val r = 30 + 10 * cos(v)
            return floatArrayOf((r * cos(u)).toFloat(), (r * sin(u)).toFloat(), (10 * sin(v)).toFloat())
        }
        for (i in 0 until rings) for (j in 0 until sides) {
            val a = p(i, j); val bb = p(i + 1, j); val c = p(i + 1, j + 1); val d = p(i, j + 1)
            b.addTriangle(a + bb + c); b.addTriangle(a + c + d)
        }
        return b.build()
    }

    @Test
    fun aMillionTriangleSolidIsAnalyzedAndClosed() {
        val m = torusMesh(1000, 500)
        assertEquals(1_000_000, m.triangleCount)
        MeshAnalyzer.analyze(torusMesh(100, 50)) // warm-up
        val rt = Runtime.getRuntime()
        fun used() = rt.totalMemory() - rt.freeMemory()
        System.gc(); Thread.sleep(100)
        val baseline = used()
        // Sample the heap while the analysis runs to estimate its peak working memory.
        val peak = java.util.concurrent.atomic.AtomicLong(baseline)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread { while (running.get()) { peak.accumulateAndGet(used(), ::maxOf); Thread.sleep(2) } }.apply { start() }
        val r = MeshAnalyzer.analyze(m)
        running.set(false); sampler.join()
        System.gc(); Thread.sleep(100)
        val retained = used() - baseline
        println("MeshAnalyzer (JVM): ${m.triangleCount} triangles in ${r.elapsedMs} ms; " +
            "peak heap growth ≈ ${(peak.get() - baseline) / (1 shl 20)} MB (includes garbage), " +
            "report retains ≈ ${retained / (1 shl 20)} MB")
        assertTrue(r.findings.isEmpty())
        assertEquals(1, r.shells.size)
        assertTrue(r.shells[0].signedVolume!! > 0)
    }

    /** Runs diagnostics on every test model that parses (format regression); prints a summary table. */
    @Test
    fun everyTestModelCanBeAnalyzed() {
        val dir = File(System.getProperty("strata.testModelsDir") ?: "../test-models")
        assumeTrue("test-models not generated", File(dir, "expected.json").exists())
        val entries = JsonParser.parse(File(dir, "expected.json").readText()).get("models").asList().orEmpty()
        // Expected diagnosis per fixture: fixture 35 is deliberately inside-out, strips/fans/lines and the strip
        // sequence are open surfaces by design, and the sparse accessor moves one vertex so its two triangles
        // disagree. Everything else (including every torus knot) must check clean.
        fun expected(name: String): Set<IssueType> = when {
            "35_inside_out" in name -> setOf(IssueType.INVERTED_SHELL)
            "19_strip_fan" in name || "33_meshopt_strip" in name -> setOf(IssueType.BOUNDARY_EDGES)
            "26_sparse" in name -> setOf(IssueType.FLIPPED_TRIANGLES)
            else -> emptySet()
        }
        var analyzed = 0
        for (e in entries) {
            val name = e.get("file").asString() ?: continue
            val file = File(dir, name)
            if (!file.isFile) continue
            val m = try {
                val resources = ModelResources { uri ->
                    File(file.parentFile, ModelResources.normalize(uri)).takeIf { it.isFile }?.inputStream()
                }
                file.inputStream().use { ModelParser.parse(it, file.name, file.length(), resources) }
            } catch (ex: Exception) { continue } // files that are meant to fail are covered by TestModelsTest
            val r = MeshAnalyzer.analyze(m)
            assertNotNull(r)
            assertEquals(m.triangleCount, r.triangleCount)
            assertEquals(m.triangleCount * 3, r.topology.cornerToVertex.size)
            for (f in r.findings) {
                assertTrue(f.triangles.all { it in 0 until m.triangleCount })
                assertTrue(f.edges.all { it in 0 until m.triangleCount * 3 })
            }
            assertEquals(name, expected(name), r.findings.map { it.type }.toSet())
            println("%-45s %8d tris %5d ms  %s".format(name, m.triangleCount, r.elapsedMs,
                r.findings.joinToString { "${it.type}=${it.count}" }.ifEmpty { "no issues" }))
            analyzed++
        }
        assertTrue(analyzed > 20)
    }
}
