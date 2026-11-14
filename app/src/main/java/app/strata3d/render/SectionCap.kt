package app.strata3d.render

import app.strata3d.diagnostics.DiagnosticsReport
import app.strata3d.diagnostics.IssueType
import app.strata3d.model.BoundingBox

/**
 * Filling ("capping") the cut face of a section (pure; unit-tested).
 *
 * The renderer draws the clipped model a second time into the stencil buffer only, with depth testing off.
 * Front faces add 1 and back faces subtract 1, so each pixel holds the winding number of the kept surfaces along its
 * view ray. A quad on the plane ([quad]) is then drawn where that number is not zero.
 *
 * - **Solid:** a point inside a closed solid has winding ±1, so it is filled.
 * - **Cavity:** a correctly modelled cavity (inner shell wound inwards) cancels to 0, so the hollow stays open.
 * - **Overlapping parts:** parts that overlap give ±2, so their union is filled (simple parity would leave the
 *   overlap open).
 *
 * The count is only meaningful for closed, consistently wound geometry. On an open or non-manifold mesh it
 * produces streaks, so [reliability] allows capping only when the mesh check has confirmed the geometry.
 */
object SectionCap {

    /** Whether the cut can be filled, and if not, why. */
    enum class Reliability {
        /** Closed, consistently wound geometry on a device with a stencil buffer. */
        RELIABLE,

        /** The device has no stencil buffer. */
        NO_STENCIL,

        /** The mesh check has not run (or is still running), so closedness is unknown. */
        UNKNOWN_GEOMETRY,

        /** Open, non-manifold, flipped or non-orientable geometry: a fill would be wrong in places. */
        UNSUITABLE_GEOMETRY,
    }

    /** Findings that make the winding count unreliable. Zero-area triangles and inside-out parts are harmless. */
    private val BLOCKING = setOf(
        IssueType.BOUNDARY_EDGES, IssueType.NON_MANIFOLD_EDGES, IssueType.FLIPPED_TRIANGLES, IssueType.NON_ORIENTABLE,
    )

    fun reliability(hasStencil: Boolean, report: DiagnosticsReport?): Reliability = when {
        !hasStencil -> Reliability.NO_STENCIL
        report == null -> Reliability.UNKNOWN_GEOMETRY
        report.findings.any { it.type in BLOCKING } -> Reliability.UNSUITABLE_GEOMETRY
        else -> Reliability.RELIABLE
    }

    /**
     * Two triangles (6 vertices, `x, y, z` each) on [plane], covering the model's [bounds] on the other two axes
     * with a 2% margin, so the cap is never smaller than any cross-section of the model.
     */
    fun quad(bounds: BoundingBox, plane: SectionPlane): FloatArray {
        val mx = bounds.sizeX * 0.02f + 1e-6f
        val my = bounds.sizeY * 0.02f + 1e-6f
        val mz = bounds.sizeZ * 0.02f + 1e-6f
        val p = plane.position
        val corners: Array<FloatArray> = when (plane.axis) {
            SectionAxis.X -> arrayOf(
                floatArrayOf(p, bounds.minY - my, bounds.minZ - mz), floatArrayOf(p, bounds.maxY + my, bounds.minZ - mz),
                floatArrayOf(p, bounds.maxY + my, bounds.maxZ + mz), floatArrayOf(p, bounds.minY - my, bounds.maxZ + mz),
            )
            SectionAxis.Y -> arrayOf(
                floatArrayOf(bounds.minX - mx, p, bounds.minZ - mz), floatArrayOf(bounds.maxX + mx, p, bounds.minZ - mz),
                floatArrayOf(bounds.maxX + mx, p, bounds.maxZ + mz), floatArrayOf(bounds.minX - mx, p, bounds.maxZ + mz),
            )
            SectionAxis.Z -> arrayOf(
                floatArrayOf(bounds.minX - mx, bounds.minY - my, p), floatArrayOf(bounds.maxX + mx, bounds.minY - my, p),
                floatArrayOf(bounds.maxX + mx, bounds.maxY + my, p), floatArrayOf(bounds.minX - mx, bounds.maxY + my, p),
            )
        }
        val out = FloatArray(18)
        var i = 0
        for (k in intArrayOf(0, 1, 2, 0, 2, 3)) for (c in 0 until 3) out[i++] = corners[k][c]
        return out
    }

    /** Cap colour: orange, or blue on an orange model so the cut always stands out. */
    fun color(modelColor: FloatArray): FloatArray {
        val orange = floatArrayOf(0.95f, 0.55f, 0.2f)
        val isOrangeModel = modelColor[0] > 0.8f && modelColor[1] in 0.4f..0.7f && modelColor[2] < 0.35f
        return if (isOrangeModel) floatArrayOf(0.3f, 0.6f, 0.95f) else orange
    }
}
