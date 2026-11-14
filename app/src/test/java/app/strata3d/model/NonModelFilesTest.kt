package app.strata3d.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Files that aren't 3D models must be refused, never misread as one (found with an .xml file opened on the A17). */
class NonModelFilesTest {

    private val xml = ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<hierarchy rotation=\"0\">" +
        "<node index=\"0\" text=\"\" resource-id=\"\" class=\"android.widget.FrameLayout\" bounds=\"[0,0][1080,2340]\">" +
        "</node>".repeat(200) + "</hierarchy>\n").toByteArray()

    private fun binaryStl(vararg coords: Float): ByteArray {
        val triangles = coords.size / 9
        val b = ByteBuffer.allocate(84 + 50 * triangles).order(ByteOrder.LITTLE_ENDIAN)
        b.put(ByteArray(80)).putInt(triangles)
        for (t in 0 until triangles) {
            b.putFloat(0f).putFloat(0f).putFloat(1f)
            for (k in 0 until 9) b.putFloat(coords[9 * t + k])
            b.putShort(0)
        }
        return b.array()
    }

    private fun parse(bytes: ByteArray, name: String) = ModelParser.parse(bytes.inputStream(), name, bytes.size.toLong())

    @Test fun `unsupported extensions are refused by name`() {
        assertNotNull(ModelParser.unsupportedExtension("ui.xml"))
        assertTrue(ModelParser.unsupportedExtension("ui.xml")!!.startsWith(".xml files aren't 3D models"))
        assertNotNull(ModelParser.unsupportedExtension("photo.jpg"))
        assertNotNull(ModelParser.unsupportedExtension("model.stl.txt"))
        assertNull(ModelParser.unsupportedExtension("model.STL"))
        assertNull(ModelParser.unsupportedExtension("scene.glb"))
        assertNull(ModelParser.unsupportedExtension("pack.zip"))
        assertNull(ModelParser.unsupportedExtension("document:190634"))   // no extension: identified by content
        assertNull(ModelParser.unsupportedExtension(""))
    }

    @Test fun `the xml file from the phone is refused`() {
        val e = assertThrows(ModelParseException::class.java) { parse(xml, "ui.xml") }
        assertTrue(e.message!!.contains(".xml"))
    }

    @Test fun `without a name, text that is no model format is refused`() {
        assertThrows(ModelParseException::class.java) { parse(xml, "") }
        assertThrows(ModelParseException::class.java) { parse("just some notes\nand more".repeat(20).toByteArray(), "") }
    }

    @Test fun `without a name, binary data is read as STL only when its size matches`() {
        val stl = binaryStl(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(1, parse(stl, "").triangleCount)
        val padded = stl + ByteArray(7) { 0x55 }   // size no longer 84 + 50 n
        assertThrows(ModelParseException::class.java) { parse(padded, "") }
        val random = ByteArray(4000) { (it * 37 + 11).toByte() }
        assertThrows(ModelParseException::class.java) { parse(random, "") }
    }

    @Test fun `a text file named stl that isn't ASCII STL is refused`() {
        assertThrows(ModelParseException::class.java) { parse(xml, "renamed.stl") }
    }

    @Test fun `coordinates no real model has are refused`() {
        val huge = binaryStl(0f, 0f, 0f, 1e20f, 0f, 0f, 0f, 1e20f, 0f)
        val e = assertThrows(ModelParseException::class.java) { parse(huge, "huge.stl") }
        assertTrue(e.message!!.contains("far beyond"))
        // A large but real model (a 100 m building in mm) still opens.
        assertEquals(1, parse(binaryStl(0f, 0f, 0f, 1e5f, 0f, 0f, 0f, 1e5f, 0f), "building.stl").triangleCount)
    }
}
