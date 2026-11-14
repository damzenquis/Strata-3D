package app.strata3d.capture.recon

import app.strata3d.capture.recon.ModelSize.Axis
import app.strata3d.capture.recon.ModelSize.Dimensions
import app.strata3d.capture.recon.ModelSize.Record
import app.strata3d.capture.recon.ModelSize.Request
import app.strata3d.capture.recon.ModelSize.SizeUnit
import app.strata3d.model.LengthUnit
import app.strata3d.model.ObjParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringWriter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class ModelSizeTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Points on the surface of an upright cylinder (radius r, height h), plus a few stray points far away. */
    private fun cylinder(r: Double, h: Double, strays: Int = 0): DoubleArray {
        val pts = ArrayList<Double>()
        for (k in 0..100) for (j in 0 until 72) {
            val a = Math.toRadians(j * 5.0)
            pts += listOf(r * cos(a) + 0.3, r * sin(a) - 0.2, h * k / 100)
        }
        repeat(strays) { pts += listOf(10 * r, 0.0, 3 * h) }
        return pts.toDoubleArray()
    }

    /** Points on the outline of a box (sides a ≥ b, height h) turned by [deg] about the vertical. */
    private fun box(a: Double, b: Double, h: Double, deg: Double): DoubleArray {
        val pts = ArrayList<Double>()
        val c = cos(Math.toRadians(deg)); val s = sin(Math.toRadians(deg))
        for (k in 0..20) for (t in 0 until 200) {
            val f = t / 50.0
            val (x, y) = when (t / 50) { 0 -> a * (f - 0) to 0.0; 1 -> a to b * (f - 1); 2 -> a * (3 - f) to b; else -> 0.0 to b * (4 - f) }
            pts += listOf(x * c - y * s, x * s + y * c, h * k / 20)
        }
        return pts.toDoubleArray()
    }

    @Test fun `a cylinder measures its height and diameter`() {
        val d = ModelSize.measure(cylinder(3.0, 24.0))
        assertEquals(24.0, d.height, 0.3)
        assertEquals(6.0, d.width, 0.1)
        assertEquals(6.0, d.depth, 0.1)
    }

    @Test fun `a few stray fragments do not count`() {
        val d = ModelSize.measure(cylinder(3.0, 24.0, strays = 10))   // 10 of about 7300 points
        assertEquals(24.0, d.height, 0.3)
        assertEquals(6.0, d.width, 0.1)
    }

    @Test fun `a turned box gives its sides, not its diagonal`() {
        val d = ModelSize.measure(box(4.0, 2.0, 10.0, 30.0))
        assertEquals(10.0, d.height, 0.01)
        assertEquals(4.0, d.width, 0.02)
        assertEquals(2.0, d.depth, 0.02)
    }

    @Test fun `factor makes the chosen dimension match, in millimetres`() {
        val model = Dimensions(height = 1.5, width = 0.4, depth = 0.38)
        val byHeight = ModelSize.factor(model, Request(Axis.HEIGHT, 23.5, SizeUnit.CM))
        assertEquals(235.0, model.height * byHeight, 1e-9)
        val byWidth = ModelSize.factor(model, Request(Axis.WIDTH, 2.5, SizeUnit.IN))
        assertEquals(63.5, model.width * byWidth, 1e-9)
    }

    @Test fun `entered numbers`() {
        assertEquals(25.0, ModelSize.parseNumber("25")!!, 0.0)
        assertEquals(12.5, ModelSize.parseNumber(" 12,5 ")!!, 0.0)
        assertEquals(12.5, ModelSize.parseNumber("12.5")!!, 0.0)
        assertNull(ModelSize.parseNumber(""))
        assertNull(ModelSize.parseNumber("0"))
        assertNull(ModelSize.parseNumber("-3"))
        assertNull(ModelSize.parseNumber("1.000,5"))
        assertNull(ModelSize.parseNumber("12 cm"))
        assertTrue(ModelSize.isValid(Request(Axis.HEIGHT, 0.5, SizeUnit.CM)))
        assertFalse(ModelSize.isValid(Request(Axis.HEIGHT, 0.4, SizeUnit.CM)))
        assertTrue(ModelSize.isValid(Request(Axis.HEIGHT, 500.0, SizeUnit.CM)))
        assertFalse(ModelSize.isValid(Request(Axis.HEIGHT, 200.0, SizeUnit.IN)))
    }

    @Test fun `dimensions shown as height x width x depth`() {
        val mm = Dimensions(235.0, 61.0, 60.4)
        assertEquals("23.5 × 6.1 × 6.0 cm", ModelSize.format(mm, SizeUnit.CM, Locale.US))
        assertEquals("23,5 × 6,1 × 6,0 cm", ModelSize.format(mm, SizeUnit.CM, Locale.GERMANY))
        assertEquals("9.3 × 2.4 × 2.4 in", ModelSize.format(mm, SizeUnit.IN, Locale.US))
        assertEquals("120 × 45.0 × 30.0 cm", ModelSize.format(Dimensions(1200.0, 450.0, 300.0), SizeUnit.CM, Locale.US))
        assertEquals("12.5", ModelSize.formatValue(12.5, Locale.US))
        assertEquals("25", ModelSize.formatValue(25.0, Locale.US))
    }

    @Test fun `record round trips`() {
        val r = Record(Request(Axis.WIDTH, 6.5, SizeUnit.IN))
        assertEquals(r, Record.decode(r.encode()))
        val d = r.copy(dimensionsMm = Dimensions(235.0, 61.0, 60.4))
        assertEquals(d, Record.decode(d.encode()))
    }

    @Test fun `scaled OBJ is marked as millimetres and keeps everything else`() {
        val obj = "mtllib model.mtl\nv 1 2 3\nvt 0.5 0.25\nvn 0 0 1\nv -0.5 0 1.5 0.9\nf 1/1/1 2/1/1 1/1/1\n"
        val out = StringWriter()
        ModelSize.scaleObj(obj.reader().buffered(), out, 10.0)
        assertEquals(
            "${ModelSize.UNIT_COMMENT}\nmtllib model.mtl\nv 10 20 30\nvt 0.5 0.25\nvn 0 0 1\nv -5 0 15 0.9\nf 1/1/1 2/1/1 1/1/1\n",
            out.toString(),
        )
        // Scaling again replaces the mark instead of adding a second one.
        val again = StringWriter()
        ModelSize.scaleObj(out.toString().reader().buffered(), again, 0.5)
        assertEquals(1, again.toString().lines().count { it == ModelSize.UNIT_COMMENT })
        assertTrue(again.toString().contains("v 5 10 15\n"))
    }

    @Test fun `the viewer reads a marked OBJ as millimetres, and a plain one as unitless`() {
        val faces = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
        assertEquals(LengthUnit.MILLIMETER, ObjParser.parse("${ObjParser.MILLIMETRE_COMMENT}\r\n$faces".byteInputStream()).unit)
        assertNull(ObjParser.parse(faces.byteInputStream()).unit)
        assertNull(ObjParser.parse("# made by texrecon\n$faces".byteInputStream()).unit)
    }

    @Test fun `store applies the request to a model and keeps it for later builds`() {
        val capture = tmp.newFolder("capture")
        val obj = File(capture, "model.obj")
        obj.writeText(String(cylinder(0.3, 1.5).toList().chunked(3).joinToString("") { (x, y, z) -> "v $x $y $z\n" }.toCharArray()))
        assertNull(SizeStore.apply(capture, obj))                                  // no request: unchanged
        SizeStore.write(capture, Record(Request(Axis.HEIGHT, 24.0, SizeUnit.CM)))
        val applied = SizeStore.apply(capture, obj)!!
        assertEquals(240.0, applied.dimensionsMm!!.height, 1.0)
        assertEquals(96.0, applied.dimensionsMm!!.width, 1.0)   // diameter 0.6 x (240 / 1.5)
        assertEquals(applied, SizeStore.read(capture))
        assertTrue(obj.readLines().first() == ModelSize.UNIT_COMMENT)
        // Changing the request rescales the already-sized model.
        SizeStore.write(capture, Record(Request(Axis.WIDTH, 3.0, SizeUnit.CM)))
        assertEquals(30.0, SizeStore.apply(capture, obj)!!.dimensionsMm!!.width, 0.5)
    }
}
