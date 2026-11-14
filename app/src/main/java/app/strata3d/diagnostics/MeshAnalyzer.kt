package app.strata3d.diagnostics

import app.strata3d.model.Mesh
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Topology analysis of a triangle soup (pure JVM; unit-tested). Reads [Mesh.vertices] and never modifies them.
 *
 * 1. **Weld:** each corner is quantized to a grid of cell size `tolerance` (1e-6 of the bounding-box diagonal). The
 *    three cell indices pack into a 63-bit key, and an open-addressing hash table gives each distinct key a vertex id. Corners whose positions differ by less than about the tolerance weld together.
 *    Rarely, two such corners fall either side of a cell border; their shared edge then counts as two boundary edges.
 *    This is why boundary edges are worded as "open edges", never asserted as holes.
 * 2. **Degenerate triangles:** a triangle is degenerate if two of its corners weld to the same vertex, or if it has
 *    zero area relative to its edge lengths (all three corners on one line).
 * 3. **Edges:** every edge of a non-collapsed triangle gets an undirected key `(min id, max id)`, numbered through
 *    the same kind of hash table. An edge used once is a boundary edge, used more than twice is non-manifold, and used twice in
 *    the *same* direction means the two triangles are wound inconsistently.
 * 4. **Shells and orientation:** union-find joins triangles across shared edges into parts (shells). A second
 *    union-find with parity joins them only across two-triangle edges and records whether each triangle agrees with
 *    the shell's reference winding. A parity contradiction means the shell is non-orientable.
 * 5. **Volume:** volume is computed only for shells that are closed and orientable. If such a shell contains flipped
 *    triangles, the sign of the parity-corrected volume shows which triangles are flipped (confirmed). On open
 *    shells, the minority winding is only *likely* flipped (uncertain). A closed, consistent shell with negative volume
 *    is inside-out, unless it lies inside another shell, in which case it is probably an intentional cavity.
 *
 * Memory at 1M triangles (besides the mesh itself): the report keeps the [TopologyMap] (16 bytes per triangle);
 * the working arrays peak at about 100 bytes per triangle during the edge stage and are freed afterwards.
 */
object MeshAnalyzer {

    /** Weld tolerance relative to the bounding-box diagonal. */
    const val RELATIVE_WELD_TOLERANCE = 1e-6

    /** sin² of the smallest corner angle below which a triangle counts as having zero area. */
    private const val DEGENERATE_SIN2 = 1e-12

    private const val CELL_BITS = 21
    private const val CELL_MAX = (1L shl CELL_BITS) - 1
    private const val CHUNK_TRIANGLES = 4096

    /**
     * Analyzes [mesh]. [isCancelled] is polled between chunks of work; when it returns true the analysis stops with a
     * [CancellationException]. [fingerprint] may be passed if already computed.
     */
    fun analyze(
        mesh: Mesh,
        fingerprint: MeshFingerprint = MeshFingerprint.of(mesh),
        isCancelled: () -> Boolean = { false },
    ): DiagnosticsReport {
        val start = System.nanoTime()
        fun checkpoint() { if (isCancelled()) throw CancellationException("Mesh analysis cancelled") }

        val t = mesh.triangleCount
        val c = t * 3
        val b = mesh.bounds
        val diag = sqrt(b.sizeX.toDouble() * b.sizeX + b.sizeY.toDouble() * b.sizeY + b.sizeZ.toDouble() * b.sizeZ)
        val tol = if (diag > 0.0 && diag.isFinite()) diag * RELATIVE_WELD_TOLERANCE else 1.0
        val reader = Reader(mesh)

        // ---- 1. Weld (its key array is released when weld() returns) ---------------------------------------
        val (cornerToVertex, vertexCount) = weld(reader, b, tol, c, ::checkpoint)

        // ---- 2. Degenerate triangles ---------------------------------------------------------------------------
        val degenerate = IntList()
        val collapsed = BooleanArray(t)
        reader.forEachChunk { first, n, f ->
            for (j in 0 until n) {
                val tri = first + j
                val a = cornerToVertex[tri * 3]; val bb = cornerToVertex[tri * 3 + 1]; val cc = cornerToVertex[tri * 3 + 2]
                if (a == bb || bb == cc || a == cc) {
                    collapsed[tri] = true; degenerate.add(tri); continue
                }
                val o = j * 9
                val e1x = (f[o + 3] - f[o]).toDouble(); val e1y = (f[o + 4] - f[o + 1]).toDouble(); val e1z = (f[o + 5] - f[o + 2]).toDouble()
                val e2x = (f[o + 6] - f[o]).toDouble(); val e2y = (f[o + 7] - f[o + 1]).toDouble(); val e2z = (f[o + 8] - f[o + 2]).toDouble()
                val cx = e1y * e2z - e1z * e2y; val cy = e1z * e2x - e1x * e2z; val cz = e1x * e2y - e1y * e2x
                val cross2 = cx * cx + cy * cy + cz * cz
                val l1 = e1x * e1x + e1y * e1y + e1z * e1z; val l2 = e2x * e2x + e2y * e2y + e2z * e2z
                if (cross2 <= DEGENERATE_SIN2 * l1 * l2) degenerate.add(tri)
            }
            checkpoint()
        }

        // ---- 3. Edges ------------------------------------------------------------------------------------------
        fun forward(slot: Int): Boolean {
            val tri = slot / 3; val k = slot - tri * 3
            return cornerToVertex[slot] < cornerToVertex[tri * 3 + (k + 1) % 3]
        }
        val e = edges(t, cornerToVertex, collapsed, ::checkpoint)
        val edgeCount = e.count; val slotEdge = e.slotEdge; val useCount = e.useCount
        val firstSlot = e.firstSlot; val secondSlot = e.secondSlot

        // ---- 4. Shells and orientation ------------------------------------------------------------------------
        val parts = UnionFind(t)
        val orient = ParityUnionFind(t)
        for (id in 0 until edgeCount) {
            val s1 = firstSlot[id]
            if (useCount[id] == 2) {
                val s2 = secondSlot[id]
                parts.union(s1 / 3, s2 / 3)
                // Same direction along the shared edge = opposite winding.
                orient.union(s1 / 3, s2 / 3, if (forward(s1) == forward(s2)) 1 else 0)
            }
        }
        val boundary = IntList(); val nonManifold = IntList(); val sameDirection = IntList()
        for (slot in 0 until c) {
            val id = slotEdge[slot]
            if (id < 0) continue
            when (useCount[id]) {
                1 -> boundary.add(slot)
                2 -> if (slot == secondSlot[id] && forward(firstSlot[id]) == forward(slot)) {
                    sameDirection.add(firstSlot[id]); sameDirection.add(slot)
                }
                else -> {
                    nonManifold.add(slot)
                    parts.union(firstSlot[id] / 3, slot / 3)
                }
            }
        }
        checkpoint()

        // Shell index per triangle (collapsed triangles join whatever shares their vertices only through others,
        // so they form their own tiny shells: exclude them from shell statistics).
        val rootToShell = IntArray(t) { -1 }
        val triangleShell = IntArray(t) { -1 }
        var shellCount = 0
        for (tri in 0 until t) {
            if (collapsed[tri]) continue
            val root = parts.find(tri)
            if (rootToShell[root] < 0) rootToShell[root] = shellCount++
            triangleShell[tri] = rootToShell[root]
        }
        val shellTris = IntArray(shellCount)
        val shellOpen = BooleanArray(shellCount)
        val shellInconsistent = BooleanArray(shellCount)
        val shellNonOrientable = BooleanArray(shellCount)
        val shellNonManifold = BooleanArray(shellCount)
        val shellBounds = Array(shellCount) { floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE) }
        for (tri in 0 until t) if (triangleShell[tri] >= 0) shellTris[triangleShell[tri]]++
        for (i in 0 until boundary.size) shellOpen[triangleShell[boundary[i] / 3]] = true
        for (i in 0 until nonManifold.size) {
            val sh = triangleShell[nonManifold[i] / 3]
            shellOpen[sh] = true; shellNonManifold[sh] = true
        }
        for (i in 0 until sameDirection.size) shellInconsistent[triangleShell[sameDirection[i] / 3]] = true
        for (root in orient.conflictRoots()) {
            // A parity conflict lives in one orientation group, which lies within one shell.
            shellNonOrientable[triangleShell[root]] = true
        }

        // ---- 5. Volume (relative to the bounds centre, for precision) ------------------------------------------
        val ox = (b.minX + b.maxX) * 0.5; val oy = (b.minY + b.maxY) * 0.5; val oz = (b.minZ + b.maxZ) * 0.5
        val rawVolume = DoubleArray(shellCount)       // as wound
        val correctedVolume = DoubleArray(shellCount) // with parity-1 triangles reversed
        reader.forEachChunk { first, n, f ->
            for (j in 0 until n) {
                val tri = first + j
                val s = triangleShell[tri]
                if (s < 0) continue
                val o = j * 9
                val ax = f[o] - ox; val ay = f[o + 1] - oy; val az = f[o + 2] - oz
                val bx = f[o + 3] - ox; val by = f[o + 4] - oy; val bz = f[o + 5] - oz
                val cx = f[o + 6] - ox; val cy = f[o + 7] - oy; val cz = f[o + 8] - oz
                val v = (ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)) / 6.0
                rawVolume[s] += v
                correctedVolume[s] += if (orient.parity(tri) == 0) v else -v
                val bd = shellBounds[s]
                for (q in 0 until 3) {
                    val x = f[o + q * 3]; val y = f[o + q * 3 + 1]; val z = f[o + q * 3 + 2]
                    if (x < bd[0]) bd[0] = x; if (y < bd[1]) bd[1] = y; if (z < bd[2]) bd[2] = z
                    if (x > bd[3]) bd[3] = x; if (y > bd[4]) bd[4] = y; if (z > bd[5]) bd[5] = z
                }
            }
            checkpoint()
        }

        // Flipped triangles per shell: confirmed on closed orientable shells (decided by volume sign), likely
        // (minority winding) on open orientable shells. A shell joined through non-manifold edges holds several
        // orientation groups whose parities can't be compared, so only its inconsistent edges are reported.
        val flippedParity = IntArray(shellCount) { -1 }   // which parity class is flipped
        val flipConfirmed = BooleanArray(shellCount)
        val parityCount = Array(shellCount) { IntArray(2) }
        for (tri in 0 until t) {
            val s = triangleShell[tri]
            if (s >= 0 && shellInconsistent[s]) parityCount[s][orient.parity(tri)]++
        }
        for (s in 0 until shellCount) {
            if (!shellInconsistent[s] || shellNonOrientable[s] || shellNonManifold[s]) continue
            if (!shellOpen[s] && correctedVolume[s] != 0.0) {
                flippedParity[s] = if (correctedVolume[s] > 0) 1 else 0
                flipConfirmed[s] = true
            } else {
                flippedParity[s] = if (parityCount[s][1] <= parityCount[s][0]) 1 else 0
            }
        }

        val shells = List(shellCount) { s ->
            val closed = !shellOpen[s]
            val consistent = !shellInconsistent[s] && !shellNonOrientable[s]
            ShellInfo(
                triangleCount = shellTris[s],
                closed = closed,
                consistent = consistent,
                signedVolume = if (closed && consistent) rawVolume[s] else null,
                bounds = shellBounds[s],
            )
        }

        // ---- Findings ------------------------------------------------------------------------------------------
        val findings = ArrayList<Finding>()
        if (degenerate.size > 0) findings += Finding(
            IssueType.DEGENERATE_TRIANGLES, Severity.WARNING, Certainty.CONFIRMED, degenerate.size,
            degenerate.toSortedArray(), IntArray(0),
            "${plural(degenerate.size, "triangle has", "triangles have")} zero area: two corners at the same point, " +
                "or all three on one line. They add nothing to the surface and can confuse slicers.",
        )
        if (boundary.size > 0) {
            val openings = countLoops(boundary, cornerToVertex, vertexCount)
            findings += Finding(
                IssueType.BOUNDARY_EDGES, Severity.WARNING, Certainty.CONFIRMED, boundary.size,
                IntArray(0), boundary.toSortedArray(),
                "${plural(boundary.size, "edge belongs", "edges belong")} to only one triangle, forming " +
                    "${plural(openings, "open outline", "open outlines")}. The surface is not closed there: a hole, a " +
                    "gap between parts, or an intentionally open surface. Open models may not 3D print correctly.",
            )
        }
        if (nonManifold.size > 0) findings += Finding(
            IssueType.NON_MANIFOLD_EDGES, Severity.ERROR, Certainty.CONFIRMED, edgeCountOf(nonManifold, slotEdge),
            IntArray(0), nonManifold.toSortedArray(),
            "${plural(edgeCountOf(nonManifold, slotEdge), "edge is", "edges are")} shared by more than two triangles, " +
                "so the surface has no single inside and outside there. Slicers often fail on these.",
        )
        val flipped = IntList(); var anyUncertainFlip = false
        for (tri in 0 until t) {
            val s = triangleShell[tri]
            if (s >= 0 && flippedParity[s] >= 0 && orient.parity(tri) == flippedParity[s]) {
                flipped.add(tri); if (!flipConfirmed[s]) anyUncertainFlip = true
            }
        }
        val flippedEdges = IntList(); var undetermined = false
        for (i in 0 until sameDirection.size) {
            val sh = triangleShell[sameDirection[i] / 3]
            if (shellNonOrientable[sh]) continue
            flippedEdges.add(sameDirection[i])
            if (flippedParity[sh] < 0) undetermined = true
        }
        if (flipped.size > 0 || flippedEdges.size > 0) {
            val certainty = if (anyUncertainFlip || undetermined) Certainty.UNCERTAIN else Certainty.CONFIRMED
            val n = if (flipped.size > 0) flipped.size else flippedEdges.size / 2
            val text = if (flipped.size > 0) {
                "${plural(n, "triangle is", "triangles are")} wound the opposite way to " +
                    "${if (n == 1) "its" else "their"} neighbours, so ${if (n == 1) "its normal points" else "their normals point"} the wrong way."
            } else {
                "${plural(n, "pair", "pairs")} of neighbouring triangles ${if (n == 1) "is" else "are"} wound in opposite directions."
            }
            findings += Finding(
                IssueType.FLIPPED_TRIANGLES, Severity.WARNING, certainty, n,
                flipped.toSortedArray(), flippedEdges.toSortedArray(),
                text + if (certainty == Certainty.UNCERTAIN) " Which side is correct can't be proven on open or non-manifold parts; the smaller group is marked." else "",
            )
        }
        val nonOrientEdges = IntList(); var nonOrientShells = 0
        for (s in 0 until shellCount) if (shellNonOrientable[s]) nonOrientShells++
        if (nonOrientShells > 0) {
            for (i in 0 until sameDirection.size) if (shellNonOrientable[triangleShell[sameDirection[i] / 3]]) nonOrientEdges.add(sameDirection[i])
            findings += Finding(
                IssueType.NON_ORIENTABLE, Severity.ERROR, Certainty.CONFIRMED, nonOrientShells,
                trianglesOfShells(triangleShell) { shellNonOrientable[it] }, nonOrientEdges.toSortedArray(),
                "${plural(nonOrientShells, "part", "parts")} can't be wound consistently at all (like a Möbius strip), " +
                    "so ${if (nonOrientShells == 1) "it has" else "they have"} no inside or outside.",
            )
        }
        val inverted = BooleanArray(shellCount); val cavity = BooleanArray(shellCount)
        for (s in 0 until shellCount) {
            val v = shells[s].signedVolume ?: continue
            if (v >= 0.0) continue
            val inside = (0 until shellCount).any { o ->
                o != s && (shells[o].signedVolume ?: 0.0) > abs(v) && contains(shells[o].bounds, shells[s].bounds)
            }
            if (inside) cavity[s] = true else inverted[s] = true
        }
        val invertedCount = inverted.count { it }
        if (invertedCount > 0) findings += Finding(
            IssueType.INVERTED_SHELL, Severity.WARNING, Certainty.CONFIRMED, invertedCount,
            trianglesOfShells(triangleShell) { inverted[it] }, IntArray(0),
            "${plural(invertedCount, "closed part is", "closed parts are")} inside-out: every normal points inwards.",
        )
        val cavityCount = cavity.count { it }
        if (cavityCount > 0) findings += Finding(
            IssueType.INTERNAL_CAVITY, Severity.INFO, Certainty.UNCERTAIN, cavityCount,
            trianglesOfShells(triangleShell) { cavity[it] }, IntArray(0),
            "${plural(cavityCount, "inward-facing part lies", "inward-facing parts lie")} inside another part. This is " +
                "usually an intentional hollow, not a defect.",
        )

        return DiagnosticsReport(
            fingerprint = fingerprint,
            weldTolerance = tol,
            triangleCount = t,
            vertexCount = vertexCount,
            edgeCount = edgeCount,
            findings = findings,
            shells = shells,
            topology = TopologyMap(cornerToVertex, triangleShell),
            elapsedMs = (System.nanoTime() - start) / 1_000_000,
        )
    }

    /** Step 1: corner → welded vertex id (hashing each corner's grid cell; ids in order of first appearance). */
    private fun weld(reader: Reader, b: app.strata3d.model.BoundingBox, tol: Double, c: Int, checkpoint: () -> Unit): Pair<IntArray, Int> {
        val inv = 1.0 / tol
        val minX = b.minX.toDouble(); val minY = b.minY.toDouble(); val minZ = b.minZ.toDouble()
        fun cell(v: Float, min: Double): Long = floor((v - min) * inv + 0.5).toLong().coerceIn(0L, CELL_MAX)

        // Closed meshes have about one vertex per six corners; the table grows if there are more.
        val ids = LongIntMap(c / 6)
        val cornerToVertex = IntArray(c)
        reader.forEachChunk { first, n, f ->
            for (i in 0 until n * 3) {
                val key = (cell(f[i * 3], minX) shl (2 * CELL_BITS)) or (cell(f[i * 3 + 1], minY) shl CELL_BITS) or cell(f[i * 3 + 2], minZ)
                cornerToVertex[first * 3 + i] = ids.getOrAdd(key)
            }
            checkpoint()
        }
        return cornerToVertex to ids.size
    }

    /** Step 3 result: per corner slot its edge id (-1 for collapsed triangles), and per edge its uses. */
    private class Edges(val count: Int, val slotEdge: IntArray, val useCount: IntArray, val firstSlot: IntArray, val secondSlot: IntArray)

    /** Step 3: undirected edges, numbered in order of first appearance through a hash of `(min id, max id)`. */
    private fun edges(t: Int, cornerToVertex: IntArray, collapsed: BooleanArray, checkpoint: () -> Unit): Edges {
        val c = t * 3
        // A closed mesh has 1.5 edges per triangle.
        val ids = LongIntMap(t + t / 2)
        val slotEdge = IntArray(c) { -1 }
        for (tri in 0 until t) {
            if (collapsed[tri]) continue
            for (k in 0 until 3) {
                val slot = tri * 3 + k
                val a = cornerToVertex[slot]; val bb = cornerToVertex[tri * 3 + (k + 1) % 3]
                val key = if (a < bb) (a.toLong() shl 32) or bb.toLong() else (bb.toLong() shl 32) or a.toLong()
                slotEdge[slot] = ids.getOrAdd(key)
            }
            if (tri and 0xFFFF == 0) checkpoint()
        }
        val edgeCount = ids.size
        val useCount = IntArray(edgeCount)
        val firstSlot = IntArray(edgeCount) { -1 }
        val secondSlot = IntArray(edgeCount) { -1 }
        for (slot in 0 until c) {
            val id = slotEdge[slot]
            if (id < 0) continue
            when (useCount[id]++) {
                0 -> firstSlot[id] = slot
                1 -> secondSlot[id] = slot
            }
        }
        return Edges(edgeCount, slotEdge, useCount, firstSlot, secondSlot)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun edgeCountOf(slots: IntList, slotEdge: IntArray): Int {
        val ids = IntArray(slots.size) { slotEdge[slots[it]] }
        ids.sort()
        var n = 0
        for (i in ids.indices) if (i == 0 || ids[i] != ids[i - 1]) n++
        return n
    }

    /** Number of connected outlines formed by the boundary edges (union-find over their vertices). */
    private fun countLoops(boundary: IntList, cornerToVertex: IntArray, vertexCount: Int): Int {
        val uf = UnionFind(vertexCount)
        val touched = BooleanArray(vertexCount)
        for (i in 0 until boundary.size) {
            val slot = boundary[i]; val tri = slot / 3; val k = slot - tri * 3
            val a = cornerToVertex[slot]; val bb = cornerToVertex[tri * 3 + (k + 1) % 3]
            touched[a] = true; touched[bb] = true
            uf.union(a, bb)
        }
        var loops = 0
        for (v in 0 until vertexCount) if (touched[v] && uf.find(v) == v) loops++
        return loops
    }

    private inline fun trianglesOfShells(triangleShell: IntArray, pick: (Int) -> Boolean): IntArray {
        val out = IntList()
        for (tri in triangleShell.indices) { val s = triangleShell[tri]; if (s >= 0 && pick(s)) out.add(tri) }
        return out.toSortedArray()
    }

    private fun contains(outer: FloatArray, inner: FloatArray): Boolean =
        outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] <= inner[2] &&
            outer[3] >= inner[3] && outer[4] >= inner[4] && outer[5] >= inner[5]

    private fun plural(n: Int, one: String, many: String): String =
        "%,d %s".format(n, if (n == 1) one else many)

    /** Streams triangles in chunks from the direct buffer (no full copy of the geometry). */
    private class Reader(private val mesh: Mesh) {
        private val chunk = FloatArray(minOf(mesh.triangleCount, CHUNK_TRIANGLES).coerceAtLeast(1) * 9)
        inline fun forEachChunk(block: (firstTriangle: Int, count: Int, floats: FloatArray) -> Unit) {
            val buf = mesh.vertices.duplicate().apply { position(0) }
            var first = 0
            while (first < mesh.triangleCount) {
                val n = minOf(CHUNK_TRIANGLES, mesh.triangleCount - first)
                buf.get(chunk, 0, n * 9)
                block(first, n, chunk)
                first += n
            }
        }
    }
}

/** Plain union-find with path halving and union by size. */
internal class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    private val size = IntArray(n) { 1 }
    fun find(x: Int): Int {
        var a = x
        while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }
        return a
    }
    fun union(a: Int, b: Int) {
        var ra = find(a); var rb = find(b)
        if (ra == rb) return
        if (size[ra] < size[rb]) { val tmp = ra; ra = rb; rb = tmp }
        parent[rb] = ra; size[ra] += size[rb]
    }
}

/**
 * Union-find that also tracks a parity (0 = same winding as the root, 1 = reversed) for each element.
 * Joining two elements with a relation that contradicts the existing parities marks the group as conflicting.
 */
internal class ParityUnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    private val rel = ByteArray(n)          // parity relative to parent
    private val size = IntArray(n) { 1 }
    private val conflict = BooleanArray(n)  // valid at roots

    private fun findWithParity(x: Int): Long {
        // Iterative with full path compression: first walk to the root, then rewrite the path.
        var root = x; var p = 0
        while (parent[root] != root) { p = p xor rel[root].toInt(); root = parent[root] }
        var a = x; var pa = p
        while (parent[a] != a) {
            val next = parent[a]; val r = rel[a].toInt()
            parent[a] = root; rel[a] = pa.toByte()
            pa = pa xor r; a = next
        }
        return (root.toLong() shl 1) or p.toLong()
    }

    fun find(x: Int): Int = (findWithParity(x) ushr 1).toInt()

    /** Winding of [x] relative to its group's root. */
    fun parity(x: Int): Int = (findWithParity(x) and 1L).toInt()

    /** Records that [a] and [b] have relative winding [relation] (0 = same, 1 = opposite). */
    fun union(a: Int, b: Int, relation: Int) {
        val fa = findWithParity(a); val fb = findWithParity(b)
        var ra = (fa ushr 1).toInt(); var rb = (fb ushr 1).toInt()
        val pa = (fa and 1L).toInt(); val pb = (fb and 1L).toInt()
        if (ra == rb) {
            if ((pa xor pb) != relation) conflict[ra] = true
            return
        }
        if (size[ra] < size[rb]) { val tmp = ra; ra = rb; rb = tmp }
        parent[rb] = ra
        rel[rb] = (pa xor pb xor relation).toByte()
        size[ra] += size[rb]
        conflict[ra] = conflict[ra] || conflict[rb]
    }

    /** One representative triangle of each conflicting group. */
    fun conflictRoots(): List<Int> = parent.indices.filter { parent[it] == it && conflict[it] }
}

/** A growable int list without boxing. */
internal class IntList(capacity: Int = 16) {
    private var data = IntArray(capacity)
    var size = 0
        private set
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    operator fun get(i: Int): Int = data[i]
    fun toSortedArray(): IntArray = data.copyOf(size).also { it.sort() }
}

/**
 * Open-addressing hash map from non-negative Long keys to dense int ids `0, 1, 2, ...` in order of insertion.
 * Linear probing, power-of-two capacity, grows at 70% load. No boxing.
 */
internal class LongIntMap(expected: Int) {
    private var keys: LongArray
    private var values: IntArray
    private var mask: Int
    var size = 0
        private set

    init {
        var cap = 16
        while (cap * 7 / 10 < expected && cap < (1 shl 30)) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }
        values = IntArray(cap)
        mask = cap - 1
    }

    /** The id of [key], adding it with the next id if new. */
    fun getOrAdd(key: Long): Int {
        var i = mix(key) and mask
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) {
                keys[i] = key; values[i] = size
                if (++size * 10 > keys.size * 7) grow()
                return size - 1
            }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val oldKeys = keys; val oldValues = values
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        values = IntArray(oldKeys.size * 2)
        mask = keys.size - 1
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == EMPTY) continue
            var i = mix(k) and mask
            while (keys[i] != EMPTY) i = (i + 1) and mask
            keys[i] = k; values[i] = oldValues[j]
        }
    }

    private companion object {
        const val EMPTY = -1L
        fun mix(key: Long): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 32)
            return h.toInt()
        }
    }
}
