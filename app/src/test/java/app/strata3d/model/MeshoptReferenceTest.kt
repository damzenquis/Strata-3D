package app.strata3d.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Byte-for-byte check of [MeshoptDecoder] against meshoptimizer's own reference decoder: for every meshopt
 * buffer view in the test models, the SHA-256 of our decoded bytes must equal the hash the reference decoder
 * produced when `tools/generate_test_models.py` ran (stored in `expected.json`).
 */
class MeshoptReferenceTest {

    @Test
    fun decodedViewsMatchReferenceDecoderExactly() {
        val dir = File(System.getProperty("strata.testModelsDir") ?: "../test-models")
        val manifest = File(dir, "expected.json")
        assumeTrue("test-models/expected.json not found", manifest.exists())

        var checked = 0
        for (entry in JsonParser.parse(manifest.readText()).get("models").asList().orEmpty()) {
            val hashes = entry.get("meshopt_reference_sha256").asList() ?: continue
            val file = File(dir, entry.get("file").asString()!!)
            val (gltf, bin) = readGlb(file.readBytes())
            val views = gltf.get("bufferViews").asList()!!
            for (h in hashes) {
                val viewIdx = h.get("view").asInt()!!
                val ext = views[viewIdx].get("extensions").let { it.get("EXT_meshopt_compression") ?: it.get("KHR_meshopt_compression") }!!
                val offset = ext.get("byteOffset")?.asInt() ?: 0
                val decoded = MeshoptDecoder.decode(
                    bin.copyOfRange(offset, offset + ext.get("byteLength").asInt()!!),
                    ext.get("count").asInt()!!,
                    ext.get("byteStride").asInt()!!,
                    ext.get("mode").asString()!!,
                    ext.get("filter").asString(),
                )
                assertEquals("${file.name} view $viewIdx", h.get("sha256").asString(), sha256(decoded))
                checked++
            }
        }
        assertTrue("no meshopt views were checked", checked > 0)
    }

    private fun readGlb(bytes: ByteArray): Pair<JsonValue, ByteArray> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = bb.getInt(12)
        val json = String(bytes, 20, jsonLength, Charsets.UTF_8)
        val binStart = 20 + jsonLength + 8
        val binLength = bb.getInt(20 + jsonLength)
        return JsonParser.parse(json) to bytes.copyOfRange(binStart, binStart + binLength)
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
