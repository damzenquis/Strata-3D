package app.strata3d.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThreeMfParserTest {

    private val cubeVertices = arrayOf(
        floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 0f, 0f), floatArrayOf(10f, 10f, 0f), floatArrayOf(0f, 10f, 0f),
        floatArrayOf(0f, 0f, 10f), floatArrayOf(10f, 0f, 10f), floatArrayOf(10f, 10f, 10f), floatArrayOf(0f, 10f, 10f),
    )

    private val cubeFaces = arrayOf(
        intArrayOf(0, 2, 1), intArrayOf(0, 3, 2),
        intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
        intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
        intArrayOf(2, 3, 7), intArrayOf(2, 7, 6),
        intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
        intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
    )

    private fun cubeModelXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
        append("  <resources>\n")
        append("    <object id=\"1\" type=\"model\">\n")
        append("      <mesh>\n        <vertices>\n")
        cubeVertices.forEach { append("          <vertex x=\"${it[0]}\" y=\"${it[1]}\" z=\"${it[2]}\"/>\n") }
        append("        </vertices>\n        <triangles>\n")
        cubeFaces.forEach { append("          <triangle v1=\"${it[0]}\" v2=\"${it[1]}\" v3=\"${it[2]}\"/>\n") }
        append("        </triangles>\n      </mesh>\n    </object>\n  </resources>\n")
        append("  <build>\n    <item objectid=\"1\"/>\n  </build>\n</model>")
    }

    private fun singleTriangleModelXml(transform: String? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
        append("  <resources>\n    <object id=\"1\" type=\"model\">\n      <mesh>\n        <vertices>\n")
        append("          <vertex x=\"0\" y=\"0\" z=\"0\"/>\n")
        append("          <vertex x=\"10\" y=\"0\" z=\"0\"/>\n")
        append("          <vertex x=\"0\" y=\"10\" z=\"0\"/>\n")
        append("        </vertices>\n        <triangles>\n          <triangle v1=\"0\" v2=\"1\" v3=\"2\"/>\n")
        append("        </triangles>\n      </mesh>\n    </object>\n  </resources>\n")
        append("  <build>\n    <item objectid=\"1\"")
        if (transform != null) append(" transform=\"$transform\"")
        append("/>\n  </build>\n</model>")
    }

    private fun threeMf(modelXml: String, stored: Boolean = false): ByteArray {
        val bytes = modelXml.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry("3D/3dmodel.model")
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray, fileName: String = "model.3mf") =
        ModelParser.parse(ByteArrayInputStream(bytes), fileName, bytes.size.toLong())

    private fun assertCube(mesh: Mesh) {
        assertEquals(12, mesh.triangleCount)
        assertEquals(10f, mesh.bounds.sizeX, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeY, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeZ, 1e-4f)
        assertEquals(1000.0, mesh.stats.volume, 1e-2)
        assertEquals(600.0, mesh.stats.surfaceArea, 1e-2)
    }

    @Test
    fun parsesSingleObjectCube() = assertCube(parse(threeMf(cubeModelXml())))

    @Test
    fun parsesStoredZipEntry() = assertCube(parse(threeMf(cubeModelXml(), stored = true)))

    @Test
    fun appliesTranslationTransform() {
        val mesh = parse(threeMf(singleTriangleModelXml("1 0 0 0 1 0 0 0 1 5 0 0")))
        assertEquals(1, mesh.triangleCount)
        assertEquals(5f, mesh.bounds.minX, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeX, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeY, 1e-4f)
    }

    @Test
    fun placesMultipleObjects() {
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
            append("  <resources>\n")
            append("    <object id=\"1\" type=\"model\"><mesh><vertices>")
            append("<vertex x=\"0\" y=\"0\" z=\"0\"/><vertex x=\"10\" y=\"0\" z=\"0\"/><vertex x=\"0\" y=\"10\" z=\"0\"/>")
            append("</vertices><triangles><triangle v1=\"0\" v2=\"1\" v3=\"2\"/></triangles></mesh></object>\n")
            append("    <object id=\"2\" type=\"model\"><mesh><vertices>")
            append("<vertex x=\"0\" y=\"0\" z=\"0\"/><vertex x=\"10\" y=\"0\" z=\"0\"/><vertex x=\"0\" y=\"10\" z=\"0\"/>")
            append("</vertices><triangles><triangle v1=\"0\" v2=\"1\" v3=\"2\"/></triangles></mesh></object>\n")
            append("  </resources>\n  <build>\n")
            append("    <item objectid=\"1\"/>\n")
            append("    <item objectid=\"2\" transform=\"1 0 0 0 1 0 0 0 1 100 0 0\"/>\n")
            append("  </build>\n</model>")
        }
        val mesh = parse(threeMf(xml))
        assertEquals(2, mesh.triangleCount)
        assertEquals(0f, mesh.bounds.minX, 1e-4f)
        assertEquals(110f, mesh.bounds.sizeX, 1e-4f)
    }

    @Test
    fun sniffsFormatWithoutExtension() {
        val mesh = ThreeMfParser.parse(ByteArrayInputStream(threeMf(cubeModelXml())))
        assertCube(mesh)
    }

    @Test
    fun missingModelPartThrows() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("3D/other.txt"))
            zip.write("not a model".toByteArray())
            zip.closeEntry()
        }
        assertThrows(ThreeMfParseException::class.java) { parse(out.toByteArray()) }
    }

    @Test
    fun invalidTransformThrows() {
        assertThrows(ThreeMfParseException::class.java) {
            parse(threeMf(singleTriangleModelXml("1 0 0 0 1 0 0 0 1 5")))
        }
    }

    private fun packageOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun cubeObjectXml(id: String): String = buildString {
        append("<object id=\"$id\" type=\"model\"><mesh><vertices>")
        cubeVertices.forEach { append("<vertex x=\"${it[0]}\" y=\"${it[1]}\" z=\"${it[2]}\"/>") }
        append("</vertices><triangles>")
        cubeFaces.forEach { append("<triangle v1=\"${it[0]}\" v2=\"${it[1]}\" v3=\"${it[2]}\"/>") }
        append("</triangles></mesh></object>")
    }

    private val core = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
    private val production = "http://schemas.microsoft.com/3dmanufacturing/production/2015/06"

    @Test
    fun resolvesComponentsWithComposedTransforms() {
        // Object 2 = cube 1 twice (second copy shifted +20 X); the build item lifts everything +5 Z.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="$core"><resources>${cubeObjectXml("1")}
            <object id="2" type="model"><components>
              <component objectid="1"/>
              <component objectid="1" transform="1 0 0 0 1 0 0 0 1 20 0 0"/>
            </components></object></resources>
            <build><item objectid="2" transform="1 0 0 0 1 0 0 0 1 0 0 5"/></build></model>"""
        val mesh = parse(threeMf(xml))
        assertEquals(24, mesh.triangleCount)
        assertEquals(0f, mesh.bounds.minX, 1e-4f)
        assertEquals(30f, mesh.bounds.maxX, 1e-4f)
        assertEquals(5f, mesh.bounds.minZ, 1e-4f)
        assertEquals(15f, mesh.bounds.maxZ, 1e-4f)
    }

    @Test
    fun resolvesProductionExtensionPartsInAnyZipOrder() {
        // Bambu Studio / Orca Slicer layout: the mesh lives in its own part, stored before the root part.
        val objectPart = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="$core"><resources>${cubeObjectXml("1")}</resources><build/></model>"""
        val rootPart = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="$core" xmlns:p="$production" requiredextensions="p"><resources>
            <object id="2" type="model"><components>
              <component p:path="/3D/Objects/object_1.model" objectid="1"/>
            </components></object></resources>
            <build><item objectid="2" transform="1 0 0 0 1 0 0 0 1 100 100 0"/></build></model>"""
        val rels = """<?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
            </Relationships>"""
        val mesh = parse(
            packageOf(
                "3D/Objects/object_1.model" to objectPart,
                "_rels/.rels" to rels,
                "3D/3dmodel.model" to rootPart,
            ),
        )
        assertCube(mesh)
        assertEquals(100f, mesh.bounds.minX, 1e-4f)
        assertEquals(110f, mesh.bounds.maxY, 1e-4f)
    }

    @Test
    fun composeAppliesFirstTransformThenSecond() {
        // first: rotate 90 deg about Z; second: translate +10 X. Point (1,0,0) -> (0,1,0) -> (10,1,0).
        val rotate = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        val translate = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 10f, 0f, 0f)
        val m = ThreeMfParser.compose(rotate, translate)
        val x = 1f; val y = 0f; val z = 0f
        assertEquals(10f, m[0] * x + m[3] * y + m[6] * z + m[9], 1e-6f)
        assertEquals(1f, m[1] * x + m[4] * y + m[7] * z + m[10], 1e-6f)
    }

    @Test
    fun selfReferencingComponentsThrow() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="$core"><resources>
            <object id="1" type="model"><components><component objectid="1"/></components></object>
            </resources><build><item objectid="1"/></build></model>"""
        assertThrows(ThreeMfParseException::class.java) { parse(threeMf(xml)) }
    }
}
