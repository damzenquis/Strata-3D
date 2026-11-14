package app.strata3d.model

import app.strata3d.render.ModelRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wrong or damaged input must fail with a clear message and never crash or exhaust memory. */
class RobustnessTest {

    private fun parse(bytes: ByteArray, name: String) =
        ModelParser.parse(ByteArrayInputStream(bytes), name, bytes.size.toLong())

    @Test
    fun nonModelFilesAreRejectedClearly() {
        val samples = mapOf(
            "PNG image" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(400),
            "JPEG image" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(400),
            "PDF document" to "%PDF-1.7\n".toByteArray() + ByteArray(400),
        )
        for ((kind, bytes) in samples) {
            // No model extension (e.g. a snapshot saved next to models), so the content decides.
            val e = assertThrows(ModelParseException::class.java) { parse(bytes, "thing.png") }
            assertTrue("$kind: ${e.message}", e.message!!.contains(kind))
        }
    }

    @Test
    fun overflowingCoordinatesAreRejected() {
        // A binary STL whose two triangles span ±3e38: the size overflows to infinity.
        val buf = ByteBuffer.allocate(84 + 2 * 50).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80); buf.putInt(2)
        for (sign in listOf(1f, -1f)) {
            repeat(3) { buf.putFloat(0f) } // normal
            repeat(3) { buf.putFloat(3e38f * sign); buf.putFloat(0f); buf.putFloat(0f) }
            buf.putShort(0)
        }
        val e = assertThrows(ModelParseException::class.java) { parse(buf.array(), "bad.stl") }
        assertTrue(e.message!!.contains("too large"))
    }

    @Test
    fun gridStaysBoundedForExtremeBounds() {
        val (grid, _) = ModelRenderer.buildGrid(BoundingBox(-1e30f, -1e-30f, 0f, 1e30f, 1e-30f, 1f))
        assertTrue("grid vertices: ${grid.size}", grid.size <= 4 * (2 * 64 + 1) * 3)
        val (none, minor) = ModelRenderer.buildGrid(BoundingBox(Float.NEGATIVE_INFINITY, 0f, 0f, Float.POSITIVE_INFINITY, 0f, 0f))
        assertEquals(0, none.size)
        assertEquals(0, minor)
    }
}
