package app.strata3d.diagnostics

import app.strata3d.model.Mesh

/*
 * Data model for mesh diagnostics (pure JVM).
 *
 * Diagnostics *inspect* geometry; they never change it. Every finding refers to the original render geometry by
 * index, so a future repair step (degenerate removal, normal correction, fragment removal, hole filling) can
 * locate the affected geometry from a report without re-running the analysis:
 *
 * - a **triangle** reference `t` is triangle `t` of [Mesh.vertices] (floats `9t .. 9t + 8`);
 * - an **edge** reference `3t + k` is the edge of triangle `t` from corner `k` to corner `(k + 1) % 3`.
 *
 * The analysis welds coincident corners into topological vertices; [TopologyMap] keeps that mapping.
 */

/** What kind of problem a finding describes. */
enum class IssueType {
    /** Triangles with zero area: two corners at the same position, or all three on one line. */
    DEGENERATE_TRIANGLES,

    /** Edges used by only one triangle: the surface is open there. Not necessarily a hole (it can be a gap or an intentional sheet). */
    BOUNDARY_EDGES,

    /** Edges shared by more than two triangles. */
    NON_MANIFOLD_EDGES,

    /** Triangles whose winding disagrees with their neighbours (normals point the wrong way). */
    FLIPPED_TRIANGLES,

    /** A connected part whose triangles cannot be oriented consistently at all (for example a Möbius strip). */
    NON_ORIENTABLE,

    /** A closed, consistently wound part whose normals all point inwards (negative volume). */
    INVERTED_SHELL,

    /** A closed part wound inwards that lies inside another part: usually an intentional hollow. */
    INTERNAL_CAVITY,
}

enum class Severity { ERROR, WARNING, INFO }

/** Whether a finding is certain from the geometry, or a likely interpretation that a person should check. */
enum class Certainty { CONFIRMED, UNCERTAIN }

/**
 * One kind of problem and every place it occurs.
 *
 * @property triangles affected triangle references (see the file comment), ascending.
 * @property edges affected edge references (`3t + k`), ascending.
 */
class Finding(
    val type: IssueType,
    val severity: Severity,
    val certainty: Certainty,
    val count: Int,
    val triangles: IntArray,
    val edges: IntArray,
    /** A plain-language explanation for people. */
    val explanation: String,
)

/** A connected part of the mesh (triangles connected through shared edges). */
class ShellInfo(
    val triangleCount: Int,
    /** No boundary and no non-manifold edges. */
    val closed: Boolean,
    /** All triangles wound consistently (no flipped triangles, orientable). */
    val consistent: Boolean,
    /**
     * Enclosed volume in cubic model units, signed by the winding (positive = normals point outwards), or null when
     * the volume is not meaningful (open, non-manifold, or inconsistently wound).
     */
    val signedVolume: Double?,
    /** min x, y, z, max x, y, z. */
    val bounds: FloatArray,
)

/**
 * Maps the analysis back to the original geometry.
 *
 * [cornerToVertex]: corner `3t + k` → welded vertex id. Corners that are duplicated for rendering (a triangle soup
 * repeats each shared position once per triangle, and files split vertices along UV seams or hard normals) map to
 * the same welded vertex when their positions agree within [DiagnosticsReport.weldTolerance].
 * [triangleShell]: triangle → index into [DiagnosticsReport.shells].
 */
class TopologyMap(val cornerToVertex: IntArray, val triangleShell: IntArray)

/** Identifies exact geometry: a report is only valid for the geometry it analyzed. */
data class MeshFingerprint(val triangleCount: Int, val hash: Long) {
    companion object {
        /** A 64-bit hash of every coordinate's bits (one fast pass; computed only when diagnostics are requested). */
        fun of(mesh: Mesh): MeshFingerprint {
            val buf = mesh.vertices.duplicate().apply { position(0) }
            val total = mesh.vertexCount * 3
            val chunk = FloatArray(minOf(total, 1 shl 15).coerceAtLeast(1))
            var h = -0x340d631b7bdddcdbL xor total.toLong()
            var read = 0
            while (read < total) {
                val n = minOf(chunk.size, total - read)
                buf.get(chunk, 0, n)
                for (i in 0 until n) {
                    h = (h xor java.lang.Float.floatToRawIntBits(chunk[i]).toLong()) * 0x100000001b3L
                    h = h xor (h ushr 29)
                }
                read += n
            }
            return MeshFingerprint(mesh.triangleCount, h)
        }
    }
}

/** The result of analyzing one geometry version. */
class DiagnosticsReport(
    val fingerprint: MeshFingerprint,
    /** Welding tolerance used, in model units. */
    val weldTolerance: Double,
    val triangleCount: Int,
    val vertexCount: Int,
    val edgeCount: Int,
    val findings: List<Finding>,
    val shells: List<ShellInfo>,
    val topology: TopologyMap,
    val elapsedMs: Long,
) {
    /** True when at least one warning or error was found (informational notes alone do not count). */
    val hasIssues: Boolean get() = findings.any { it.severity != Severity.INFO }

    fun finding(type: IssueType): Finding? = findings.firstOrNull { it.type == type }

    /** A report describes only the geometry it analyzed; any change to the coordinates makes it stale. */
    fun isValidFor(fingerprint: MeshFingerprint): Boolean = fingerprint == this.fingerprint
}
