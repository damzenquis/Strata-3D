package app.strata3d.diagnostics

import app.strata3d.model.Mesh

/**
 * Geometry for highlighting findings in the 3D view (pure; built off the UI thread). Positions are copied from the
 * original mesh through the findings' references, so the highlight lies exactly on the rendered geometry.
 *
 * Each array holds `x, y, z` per vertex: [boundaryLines] and [nonManifoldLines] two vertices per edge,
 * [flippedTriangles] three per triangle. Each category is capped at [MAX_ELEMENTS] so a badly broken mesh (for
 * example a soup where every edge is open) cannot exhaust GPU memory; [truncated] says when that happened.
 */
class DiagnosticOverlay(
    val boundaryLines: FloatArray,
    val nonManifoldLines: FloatArray,
    val flippedTriangles: FloatArray,
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = boundaryLines.isEmpty() && nonManifoldLines.isEmpty() && flippedTriangles.isEmpty()

    companion object {
        const val MAX_ELEMENTS = 250_000

        fun build(mesh: Mesh, report: DiagnosticsReport): DiagnosticOverlay {
            // The caller passes the mesh the report was made from (checked by fingerprint before display).
            require(mesh.triangleCount == report.triangleCount) { "Report does not belong to this mesh" }
            val v = mesh.vertices
            var truncated = false

            fun lines(edges: IntArray?): FloatArray {
                if (edges == null) return FloatArray(0)
                val n = minOf(edges.size, MAX_ELEMENTS)
                if (n < edges.size) truncated = true
                val out = FloatArray(n * 6)
                for (i in 0 until n) {
                    val slot = edges[i]; val tri = slot / 3; val k = slot - tri * 3
                    val a = slot * 3; val b = (tri * 3 + (k + 1) % 3) * 3
                    for (q in 0 until 3) { out[i * 6 + q] = v.get(a + q); out[i * 6 + 3 + q] = v.get(b + q) }
                }
                return out
            }

            val flipped = report.finding(IssueType.FLIPPED_TRIANGLES)?.triangles ?: IntArray(0)
            val n = minOf(flipped.size, MAX_ELEMENTS)
            if (n < flipped.size) truncated = true
            val tris = FloatArray(n * 9)
            for (i in 0 until n) { val base = flipped[i] * 9; for (q in 0 until 9) tris[i * 9 + q] = v.get(base + q) }

            return DiagnosticOverlay(
                boundaryLines = lines(report.finding(IssueType.BOUNDARY_EDGES)?.edges),
                nonManifoldLines = lines(report.finding(IssueType.NON_MANIFOLD_EDGES)?.edges),
                flippedTriangles = tris,
                truncated = truncated,
            )
        }
    }
}
