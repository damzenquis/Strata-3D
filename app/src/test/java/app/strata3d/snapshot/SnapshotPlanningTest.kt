package app.strata3d.snapshot

import app.strata3d.model.BoundingBox
import app.strata3d.model.LengthUnit
import app.strata3d.model.ModelScale
import app.strata3d.model.UnitSource
import app.strata3d.render.GpuLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SnapshotPlanningTest {

    private val bigGpu = GpuLimits(16384, 16384, 16384, 4)
    private val smallGpu = GpuLimits(2048, 2048, 2048, 4)

    @Test
    fun sizesScaleTheVisibleAreaWithoutChangingAspect() {
        val screen = SnapshotPlanning.plan(SnapshotSize.SCREEN, 1080, 1700, SnapshotStyle.TRANSPARENT, false, bigGpu)
        assertEquals(1080, screen.outputWidth); assertEquals(1700, screen.outputHeight)
        val double = SnapshotPlanning.plan(SnapshotSize.DOUBLE, 1080, 1700, SnapshotStyle.TRANSPARENT, false, bigGpu)
        assertEquals(2160, double.outputWidth); assertEquals(3400, double.outputHeight)
        val uhd = SnapshotPlanning.plan(SnapshotSize.UHD, 1080, 1700, SnapshotStyle.TRANSPARENT, false, bigGpu)
        assertEquals("4K long edge", 3840, uhd.renderHeight)
        assertEquals(1080f / 1700f, uhd.renderWidth.toFloat() / uhd.renderHeight, 0.001f)
        // Landscape: the long edge is the width.
        val land = SnapshotPlanning.plan(SnapshotSize.UHD, 2000, 800, SnapshotStyle.TRANSPARENT, false, bigGpu)
        assertEquals(3840, land.renderWidth); assertEquals(1536, land.renderHeight)
    }

    @Test
    fun sizesBeyondTheGpuAreFlaggedNeverShrunk() {
        val uhd = SnapshotPlanning.plan(SnapshotSize.UHD, 1080, 1700, SnapshotStyle.TRANSPARENT, false, smallGpu)
        assertEquals("dimensions stay as requested", 3840, uhd.renderHeight)
        assertFalse(uhd.supported)
        assertTrue(SnapshotPlanning.plan(SnapshotSize.SCREEN, 1080, 1700, SnapshotStyle.TRANSPARENT, false, smallGpu).supported)
        assertFalse("2x needs 3400 px", SnapshotPlanning.plan(SnapshotSize.DOUBLE, 1080, 1700, SnapshotStyle.TRANSPARENT, false, smallGpu).supported)
        assertFalse("unknown limits are not assumed", SnapshotPlanning.plan(SnapshotSize.SCREEN, 10, 10, SnapshotStyle.TRANSPARENT, false, null).supported)
    }

    @Test
    fun captionBandOnlyForFramedWithCaption() {
        assertEquals(0, SnapshotPlanning.plan(SnapshotSize.SCREEN, 1000, 1000, SnapshotStyle.TRANSPARENT, true, bigGpu).captionHeight)
        assertEquals(0, SnapshotPlanning.plan(SnapshotSize.SCREEN, 1000, 1000, SnapshotStyle.FRAMED, false, bigGpu).captionHeight)
        val framed = SnapshotPlanning.plan(SnapshotSize.SCREEN, 1000, 1000, SnapshotStyle.FRAMED, true, bigGpu)
        assertEquals(120, framed.captionHeight)
        assertEquals(1120, framed.outputHeight)
        assertEquals("caption does not change the render", 1000, framed.renderHeight)
    }

    @Test
    fun flipRowsReversesRowOrder() {
        val w = 3; val h = 4
        val buf = ByteBuffer.allocate(w * h * 4)
        for (y in 0 until h) repeat(w * 4) { buf.put(y.toByte()) }
        SnapshotPlanning.flipRows(buf, w, h)
        for (y in 0 until h) assertEquals((h - 1 - y).toByte(), buf.get(y * w * 4))
        assertEquals(0, buf.position())
    }

    @Test
    fun dimensionsShowUnitsOnlyWhenKnown() {
        val b = BoundingBox(0f, 0f, 0f, 65f, 12.5f, 0.0123f)
        val mmFile = ModelScale(LengthUnit.MILLIMETER, UnitSource.FILE)
        val assumed = ModelScale.resolve(null, null)
        assertEquals("65 × 12.5 × 0.0123 mm", SnapshotPlanning.dimensionsText(b, mmFile))
        assertEquals("65 × 12.5 × 0.0123", SnapshotPlanning.dimensionsText(b, assumed))
        assertEquals("1234 × 0.5 × 100", SnapshotPlanning.dimensionsText(BoundingBox(0f, 0f, 0f, 1234.4f, 0.5f, 100f), assumed))
    }

    @Test
    fun dimensionsAreConvertedToMillimetres() {
        // A glTF part 65 x 12.5 x 30 mm is stored in metres.
        val gltf = BoundingBox(0f, 0f, 0f, 0.065f, 0.0125f, 0.03f)
        assertEquals("65 × 12.5 × 30 mm", SnapshotPlanning.dimensionsText(gltf, ModelScale.resolve(LengthUnit.METER, null)))
        // An STL the user says is in inches.
        val stl = BoundingBox(0f, 0f, 0f, 1f, 2f, 0.5f)
        assertEquals("25.4 × 50.8 × 12.7 mm", SnapshotPlanning.dimensionsText(stl, ModelScale.resolve(null, LengthUnit.INCH)))
        // Large scenes switch to metres.
        val room = BoundingBox(0f, 0f, 0f, 12f, 8f, 3f)
        assertEquals("12 × 8 × 3 m", SnapshotPlanning.dimensionsText(room, ModelScale.resolve(LengthUnit.METER, null)))
    }

    @Test
    fun unitsFrom3mf() {
        assertEquals(LengthUnit.MILLIMETER, LengthUnit.from3mf(null))
        assertEquals(LengthUnit.INCH, LengthUnit.from3mf("inch"))
        assertEquals(null, LengthUnit.from3mf("parsec"))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("gear_housing-framed-1080x1820.png", SnapshotPlanning.fileName("gear housing.3mf", SnapshotStyle.FRAMED, 1080, 1820))
        assertEquals("model-transparent-10x10.png", SnapshotPlanning.fileName("..!!.stl", SnapshotStyle.TRANSPARENT, 10, 10))
    }
}
