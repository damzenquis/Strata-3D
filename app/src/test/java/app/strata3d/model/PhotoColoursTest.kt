package app.strata3d.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhotoColoursTest {

    @get:Rule val tmp = TemporaryFolder()

    private val obj = "mtllib model.mtl\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n" +
        "usemtl a\nf 1/1 2/2 3/3\nusemtl b\nf 1/1 3/3 4/4\n"
    private val mtl = "newmtl a\nmap_Kd a.png\nnewmtl b\nmap_Kd b.png\n"

    private fun load(files: Map<String, ByteArray>): Mesh? {
        val f = File(tmp.newFolder(), "model.obj").apply { writeText(obj) }
        val plain = f.inputStream().use { ModelParser.parse(it, "model.obj", f.length()) }
        return PhotoColours.load(f, { name -> files[name]?.inputStream() }, plain)
    }

    @Test fun `colours follow the materials, corner by corner`() {
        val m = load(mapOf("model.mtl" to mtl.toByteArray(), "a.png" to byteArrayOf(1), "b.png" to byteArrayOf(2)))!!
        val c = assertNotNull(m.colours).let { m.colours!! }
        assertEquals(2, m.triangleCount)
        assertEquals(listOf(0 to 3, 3 to 3), c.parts.map { it.first to it.count })
        assertEquals(listOf(1.toByte(), 2.toByte()), c.parts.map { it.image[0] })
        val uv = FloatArray(12).also { c.uvs.duplicate().apply { position(0) }.get(it) }
        assertEquals(listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 0f, 1f), uv.toList())
    }

    @Test fun `no colours when an image or the materials are missing`() {
        assertNull(load(mapOf("model.mtl" to mtl.toByteArray(), "a.png" to byteArrayOf(1))))   // b.png missing
        assertNull(load(emptyMap()))
    }
}
