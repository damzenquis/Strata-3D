package app.strata3d.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GltfParserTest {

    private val triangleVertices = listOf(
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(10f, 0f, 0f),
        floatArrayOf(0f, 10f, 0f),
    )
    private val triangleFaces = listOf(intArrayOf(0, 1, 2))

    private val cubeVertices = listOf(
        floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 0f, 0f), floatArrayOf(10f, 10f, 0f), floatArrayOf(0f, 10f, 0f),
        floatArrayOf(0f, 0f, 10f), floatArrayOf(10f, 0f, 10f), floatArrayOf(10f, 10f, 10f), floatArrayOf(0f, 10f, 10f),
    )
    private val cubeFaces = listOf(
        intArrayOf(0, 2, 1), intArrayOf(0, 3, 2),
        intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
        intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
        intArrayOf(2, 3, 7), intArrayOf(2, 7, 6),
        intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
        intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
    )

    private fun meshBin(vertices: List<FloatArray>, faces: List<IntArray>): ByteArray {
        val posBytes = vertices.size * 3 * 4
        val idxBytes = faces.size * 3 * 2
        val bb = ByteBuffer.allocate(posBytes + idxBytes).order(ByteOrder.LITTLE_ENDIAN)
        vertices.forEach { v -> v.forEach { bb.putFloat(it) } }
        faces.forEach { f -> f.forEach { bb.putShort(it.toShort()) } }
        return bb.array()
    }

    private fun meshJson(
        vertices: List<FloatArray>,
        faces: List<IntArray>,
        nodeJson: String = """{"mesh":0}""",
        uri: String? = null,
    ): String {
        val posBytes = vertices.size * 3 * 4
        val idxBytes = faces.size * 3 * 2
        val totalBytes = posBytes + idxBytes
        val buffer = if (uri == null) """{"byteLength":$totalBytes}""" else """{"byteLength":$totalBytes,"uri":"$uri"}"""
        return """
            {"asset":{"version":"2.0"},"scene":0,
             "scenes":[{"nodes":[0]}],
             "nodes":[$nodeJson],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":${vertices.size},"type":"VEC3"},
                          {"bufferView":1,"componentType":5123,"count":${faces.size * 3},"type":"SCALAR"}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posBytes},
                            {"buffer":0,"byteOffset":$posBytes,"byteLength":$idxBytes}],
             "buffers":[$buffer]}
        """.trimIndent()
    }

    private fun glb(json: String, bin: ByteArray): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        val binPad = (4 - bin.size % 4) % 4
        val total = 12 + 8 + jsonBytes.size + jsonPad + 8 + bin.size + binPad
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67) // "glTF"
        bb.putInt(2)          // version
        bb.putInt(total)      // length

        bb.putInt(jsonBytes.size); bb.putInt(0x4E4F534A); bb.put(jsonBytes)
        repeat(jsonPad) { bb.put(0) }
        bb.putInt(bin.size); bb.putInt(0x004E4942); bb.put(bin)
        repeat(binPad) { bb.put(0) }
        return bb.array()
    }

    private fun dataUri(bytes: ByteArray): String =
        "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes)

    private fun assertTriangle(mesh: Mesh) {
        assertEquals(1, mesh.triangleCount)
        assertEquals(10f, mesh.bounds.sizeX, 1e-3f)
        assertEquals(10f, mesh.bounds.sizeY, 1e-3f)
        assertEquals(0f, mesh.bounds.sizeZ, 1e-3f)
    }

    /** The same triangle as opened in the app: glTF's +Y up turned to +Z up, so its 10-unit height is along z. */
    private fun assertUprightTriangle(mesh: Mesh) {
        assertEquals(1, mesh.triangleCount)
        assertEquals(10f, mesh.bounds.sizeX, 1e-3f)
        assertEquals(0f, mesh.bounds.sizeY, 1e-3f)
        assertEquals(10f, mesh.bounds.sizeZ, 1e-3f)
        assertEquals(0f, mesh.bounds.minZ, 1e-3f)
    }

    private fun assertCube(mesh: Mesh) {
        assertEquals(12, mesh.triangleCount)
        assertEquals(10f, mesh.bounds.sizeX, 1e-3f)
        assertEquals(10f, mesh.bounds.sizeY, 1e-3f)
        assertEquals(10f, mesh.bounds.sizeZ, 1e-3f)
        assertEquals(1000.0, mesh.stats.volume, 1e-2)
        assertEquals(600.0, mesh.stats.surfaceArea, 1e-2)
    }

    @Test
    fun parsesGlbTriangle() {
        val json = meshJson(triangleVertices, triangleFaces)
        val mesh = GltfParser.parse(ByteArrayInputStream(glb(json, meshBin(triangleVertices, triangleFaces))), upright = false)
        assertTriangle(mesh)
    }

    @Test
    fun parsesGlbCube() {
        val json = meshJson(cubeVertices, cubeFaces)
        val mesh = GltfParser.parse(ByteArrayInputStream(glb(json, meshBin(cubeVertices, cubeFaces))))
        assertCube(mesh)
    }

    @Test
    fun parsesGltfDataUri() {
        val bin = meshBin(triangleVertices, triangleFaces)
        val json = meshJson(triangleVertices, triangleFaces, uri = dataUri(bin))
        val mesh = GltfParser.parse(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)), upright = false)
        assertTriangle(mesh)
    }

    @Test
    fun appliesNodeTranslation() {
        val json = meshJson(triangleVertices, triangleFaces, nodeJson = """{"mesh":0,"translation":[5,0,0]}""")
        val mesh = GltfParser.parse(ByteArrayInputStream(glb(json, meshBin(triangleVertices, triangleFaces))), upright = false)
        assertEquals(5f, mesh.bounds.minX, 1e-3f)
        assertEquals(10f, mesh.bounds.sizeX, 1e-3f)
    }

    @Test
    fun nestedTransformsAreAppliedBeforeMeasuring() {
        // Parent scales by 2, child translates by 5: the child's offset is scaled too (10 in world space).
        val nodes = """{"children":[1],"scale":[2,2,2]},{"mesh":0,"translation":[5,0,0]}"""
        val json = meshJson(triangleVertices, triangleFaces, nodeJson = nodes)
        val mesh = GltfParser.parse(ByteArrayInputStream(glb(json, meshBin(triangleVertices, triangleFaces))), upright = false)
        assertEquals(LengthUnit.METER, mesh.unit)
        assertEquals(10f, mesh.bounds.minX, 1e-4f)
        assertEquals(20f, mesh.bounds.sizeX, 1e-4f)
        assertEquals(20f, mesh.bounds.sizeY, 1e-4f)
        // A measurement between two picked corners uses the world (transformed) coordinates, in metres.
        val a = app.strata3d.render.Raycaster.intersect(mesh, app.strata3d.render.Ray(10.001f, 0.001f, 1f, 0f, 0f, -1f))!!
        val b = app.strata3d.render.Raycaster.intersect(mesh, app.strata3d.render.Ray(29.99f, 0.001f, 1f, 0f, 0f, -1f))!!
        val distance = b.point[0] - a.point[0]
        assertEquals(20f, distance, 0.02f)
        assertEquals(20_000.0, ModelScale.resolve(mesh.unit, null).toMm(distance.toDouble()), 20.0)
    }

    @Test
    fun appliesNodeRotation() {
        val s = 0.70710678f
        val json = meshJson(triangleVertices, triangleFaces, nodeJson = """{"mesh":0,"rotation":[0,0,$s,$s]}""")
        val mesh = GltfParser.parse(ByteArrayInputStream(glb(json, meshBin(triangleVertices, triangleFaces))), upright = false)
        // +90° about Z maps (10,0,0) -> (0,10,0) and (0,10,0) -> (-10,0,0)
        assertEquals(-10f, mesh.bounds.minX, 1e-2f)
        assertEquals(0f, mesh.bounds.maxX, 1e-2f)
        assertEquals(10f, mesh.bounds.maxY, 1e-2f)
    }

    @Test
    fun dispatchesByExtensionAndSniffing() {
        val bin = meshBin(triangleVertices, triangleFaces)
        val glbBytes = glb(meshJson(triangleVertices, triangleFaces), bin)
        val gltfBytes = meshJson(triangleVertices, triangleFaces, uri = dataUri(bin)).toByteArray(Charsets.UTF_8)
        // by extension
        assertUprightTriangle(ModelParser.parse(ByteArrayInputStream(glbBytes), "model.glb"))
        assertUprightTriangle(ModelParser.parse(ByteArrayInputStream(gltfBytes), "model.gltf"))
        // by sniffing
        assertUprightTriangle(ModelParser.parse(ByteArrayInputStream(glbBytes), "unknown"))
        assertUprightTriangle(ModelParser.parse(ByteArrayInputStream(gltfBytes), "unknown"))
    }

    @Test
    fun externalBufferThrows() {
        val json = meshJson(triangleVertices, triangleFaces, uri = "geometry.bin")
        assertThrows(GltfParseException::class.java) {
            GltfParser.parse(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun malformedJsonThrows() {
        assertThrows(GltfParseException::class.java) {
            GltfParser.parse(ByteArrayInputStream("{not json".toByteArray()))
        }
    }
}
