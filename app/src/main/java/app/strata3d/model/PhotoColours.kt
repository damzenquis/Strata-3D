package app.strata3d.model

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photo colours for an OBJ that has them (`mtllib` with `map_Kd` colour images and `vt` texture coordinates), such
 * as the models the app builds. The geometry is re-read with [TexturedObj] so every corner keeps its texture
 * coordinate, and every colour image must be available through [ModelResources]; otherwise there are no colours and
 * the model shows in its plain colour as before. Pure JVM; the renderer decodes the images.
 */
object PhotoColours {

    /** A coloured version of [plain] (same triangles, same unit), or null when [obj] has no usable colours. */
    fun load(obj: File, resources: ModelResources, plain: Mesh): Mesh? {
        val mtlName = obj.bufferedReader().useLines { lines ->
            lines.take(200).map { it.trim() }.firstOrNull { it.startsWith("mtllib ") }?.substring(7)?.trim()
        } ?: return null
        val textures = resources.open(mtlName)?.bufferedReader()?.use { TexturedObj.parseMtl(it) } ?: return null
        if (textures.isEmpty()) return null
        val model = obj.bufferedReader().use { TexturedObj.parse(it, textures) }
        if (model.triangleCount != plain.triangleCount || model.parts.any { it.texture == null }) return null
        val images = model.parts.mapNotNull { it.texture }.distinct().associateWith { name ->
            resources.open(name)?.use { it.readBytes() } ?: return null
        }
        val builder = MeshBuilder(model.triangleCount)
        val uvs = ByteBuffer.allocateDirect(model.triangleCount * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val parts = ArrayList<MeshColours.ColourPart>()
        val tri = FloatArray(9)
        var first = 0
        for (part in model.parts) {
            for (t in 0 until part.triangleCount) {
                System.arraycopy(part.positions, 9 * t, tri, 0, 9)
                val before = builder.triangleCount
                builder.addTriangle(tri)
                if (builder.triangleCount == before) return null   // a skipped triangle would misalign the colours
                uvs.put(part.uvs, 6 * t, 6)
            }
            parts += MeshColours.ColourPart(first, part.triangleCount * 3, images.getValue(part.texture!!))
            first += part.triangleCount * 3
        }
        uvs.flip()
        return builder.build(plain.unit, MeshColours(uvs, parts))
    }
}
