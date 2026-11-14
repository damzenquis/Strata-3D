package app.strata3d.capture.recon

import app.strata3d.model.GltfParser
import app.strata3d.model.LengthUnit
import app.strata3d.model.TexturedObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class ModelExportTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A 10 mm wide, 20 mm tall upright square (+z up), textured. */
    private val obj = "# Strata:3D length unit: mm\nmtllib model.mtl\nv -5 0 0\nv 5 0 0\nv 5 0 20\nv -5 0 20\n" +
        "vt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nusemtl m\nf 1/1 2/2 3/3\nf 1/1 3/3 4/4\n"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @Test fun `GLB is valid, y-up, and in metres for a sized model`() {
        val model = TexturedObj.parse(obj.reader().buffered(), mapOf("m" to "m.png"))
        val glb = ModelExport.glb(model, mapOf("m.png" to png))
        val head = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, head.getInt(0))            // "glTF"
        assertEquals(2, head.getInt(4))
        assertEquals(glb.size, head.getInt(8))
        assertEquals(0, head.getInt(12) % 4)                 // JSON chunk padded
        val mesh = GltfParser.parse(glb.inputStream(), glb.size.toLong())
        assertEquals(2, mesh.triangleCount)
        assertEquals(LengthUnit.METER, mesh.unit)
        assertEquals(0.010f, mesh.bounds.sizeX, 1e-6f)       // x stays x
        assertEquals(0.020f, mesh.bounds.sizeZ, 1e-6f)       // exported +y up, read back upright: height on +z again
        assertEquals(0f, mesh.bounds.sizeY, 1e-6f)
        assertEquals(0f, mesh.bounds.minZ, 1e-6f)
        val raw = GltfParser.parse(glb.inputStream(), glb.size.toLong(), upright = false)
        assertEquals(0.020f, raw.bounds.sizeY, 1e-6f)        // in the file itself: +y up, as glTF requires
        val json = String(glb, 20, head.getInt(12))
        assertTrue(json.contains("\"mimeType\":\"image/png\""))
        assertTrue(json.contains("KHR_materials_unlit"))
    }

    @Test fun `OBJ zip holds the model, materials and colour images`() {
        val dir = tmp.newFolder("model")
        File(dir, "model.obj").writeText(obj)
        File(dir, "model.mtl").writeText("newmtl m\nmap_Kd m.png\n")
        File(dir, "m.png").writeBytes(png)
        File(dir, "preview.png").writeBytes(png)              // not part of the export
        val out = ByteArrayOutputStream()
        var last = 0
        ModelExport.write(dir, ModelExport.Format.OBJ_ZIP, out) { last = it; true }
        val names = ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("model.obj", "model.mtl", "m.png"), names)
        assertEquals(100, last)
    }

    @Test(expected = ModelExport.Cancelled::class) fun `cancel stops the export`() {
        val dir = tmp.newFolder("model2")
        File(dir, "model.obj").writeText(obj)
        File(dir, "model.mtl").writeText("newmtl m\nmap_Kd m.png\n")
        File(dir, "m.png").writeBytes(png)
        ModelExport.write(dir, ModelExport.Format.GLB, ByteArrayOutputStream()) { false }
    }
}
