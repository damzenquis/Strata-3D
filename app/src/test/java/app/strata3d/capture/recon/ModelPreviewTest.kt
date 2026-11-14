package app.strata3d.capture.recon

import app.strata3d.model.TexturedObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPreviewTest {

    /** An upright square (1 × 1, in the x-z plane) textured red on top, blue at the bottom. */
    private val obj = """
        # Strata:3D length unit: mm
        mtllib model.mtl
        v -0.5 0 0
        v 0.5 0 0
        v 0.5 0 1
        v -0.5 0 1
        vt 0 0
        vt 1 0
        vt 1 1
        vt 0 1
        usemtl m
        f 1/1 2/2 3/3
        f 1/1 3/3 4/4
    """.trimIndent()
    private val texture = ModelPreview.Texture(1, 2, intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))   // row 0 = top (v = 1)

    @Test fun `textured model parses with its material and units`() {
        val m = TexturedObj.parse(obj.reader().buffered(), mapOf("m" to "m.png"))
        assertTrue(m.millimetres)
        assertEquals(2, m.triangleCount)
        assertEquals("m.png", m.parts.single().texture)
        assertEquals(TexturedObj.parseMtl("newmtl a\nmap_Kd a.png\nnewmtl b\nKd 1 1 1\n".reader().buffered()), mapOf("a" to "a.png"))
    }

    @Test fun `preview is upright, centred, coloured from the texture, transparent around`() {
        val m = TexturedObj.parse(obj.reader().buffered(), mapOf("m" to "m.png"))
        val w = 100; val h = 100
        val img = ModelPreview.render(m, mapOf("m.png" to texture), w, h, azimuth = 0.0, elevation = 0.0)
        fun at(x: Int, y: Int) = img[y * w + x]
        assertEquals(0, at(2, 50))                         // background stays transparent
        assertEquals(0xFF, at(50, 25) ushr 24)              // the model is drawn in the middle
        assertTrue("top is red", (at(50, 25) shr 16 and 0xFF) > 150 && (at(50, 25) and 0xFF) < 50)
        assertTrue("bottom is blue", (at(50, 75) and 0xFF) > 150 && (at(50, 75) shr 16 and 0xFF) < 50)
    }
}
