package app.strata3d.model

import java.io.BufferedReader

/**
 * A textured OBJ as the app's own reconstruction writes it (texrecon): positions, texture coordinates, triangles
 * grouped by material, and each material's colour image. Pure parsing, unit-tested; shared by the model preview,
 * the export and the viewer's photo colours.
 *
 * Every triangle corner is expanded (position + uv), so a part can be drawn or written without index juggling.
 */
class TexturedObj(
    /** x, y, z per corner, three corners per triangle, per material in [parts] order. */
    val parts: List<Part>,
    /** True when the first line marks the coordinates as millimetres (a sized model). */
    val millimetres: Boolean,
) {
    /** The triangles of one material: [positions] (3 floats per corner), [uvs] (2 per corner), its [texture] file. */
    class Part(val material: String, val texture: String?, val positions: FloatArray, val uvs: FloatArray) {
        val triangleCount get() = positions.size / 9
    }

    val triangleCount get() = parts.sumOf { it.triangleCount }

    companion object {
        /** map_Kd texture file per material name, from an MTL file. */
        fun parseMtl(mtl: BufferedReader): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var current: String? = null
            mtl.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("newmtl ") -> current = line.substring(7).trim()
                    line.startsWith("map_Kd ") -> current?.let { out[it] = line.substring(7).trim() }
                }
            }
            return out
        }

        /** Parses [obj]; [textures] maps material names to their image file (from [parseMtl]). */
        fun parse(obj: BufferedReader, textures: Map<String, String>): TexturedObj {
            val v = GrowFloats(); val vt = GrowFloats()
            val corners = LinkedHashMap<String, Pair<GrowFloats, GrowFloats>>()
            var material = ""
            var mm = false
            var first = true
            obj.lineSequence().forEach { raw ->
                if (first) { mm = raw == ObjParser.MILLIMETRE_COMMENT; first = false }
                val line = raw.trim()
                when {
                    line.startsWith("v ") -> line.split(WS).let { p -> v.add(p[1].toFloat()); v.add(p[2].toFloat()); v.add(p[3].toFloat()) }
                    line.startsWith("vt ") -> line.split(WS).let { p -> vt.add(p[1].toFloat()); vt.add(p[2].toFloat()) }
                    line.startsWith("usemtl ") -> material = line.substring(7).trim()
                    line.startsWith("f ") -> {
                        val refs = line.split(WS).drop(1)
                        val (pos, uv) = corners.getOrPut(material) { GrowFloats() to GrowFloats() }
                        // Fan-triangulate polygons; texrecon writes triangles.
                        for (k in 1 until refs.size - 1) for (r in listOf(refs[0], refs[k], refs[k + 1])) {
                            val idx = r.split('/')
                            val vi = resolve(idx[0].toInt(), v.size / 3)
                            pos.add(v[3 * vi]); pos.add(v[3 * vi + 1]); pos.add(v[3 * vi + 2])
                            val ti = idx.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { resolve(it.toInt(), vt.size / 2) }
                            if (ti != null) { uv.add(vt[2 * ti]); uv.add(vt[2 * ti + 1]) } else { uv.add(0f); uv.add(0f) }
                        }
                    }
                }
            }
            val parts = corners.map { (name, pu) -> Part(name, textures[name], pu.first.toArray(), pu.second.toArray()) }
            return TexturedObj(parts, mm)
        }

        private fun resolve(i: Int, count: Int) = if (i < 0) count + i else i - 1
        private val WS = Regex("\\s+")
    }
}

/** A growable float array without boxing. */
private class GrowFloats {
    private var a = FloatArray(1024)
    var size = 0
        private set
    fun add(f: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = f }
    operator fun get(i: Int) = a[i]
    fun toArray(): FloatArray = a.copyOf(size)
}
