package app.strata3d.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ModelScaleTest {

    @Test
    fun userChoiceThenFileThenAssumedMillimetres() {
        assertEquals(ModelScale(LengthUnit.INCH, UnitSource.USER), ModelScale.resolve(LengthUnit.METER, LengthUnit.INCH))
        assertEquals(ModelScale(LengthUnit.METER, UnitSource.FILE), ModelScale.resolve(LengthUnit.METER, null))
        val assumed = ModelScale.resolve(null, null)
        assertEquals(ModelScale(LengthUnit.MILLIMETER, UnitSource.ASSUMED), assumed)
        assertFalse(assumed.isKnown)
        assertTrue(ModelScale.resolve(null, LengthUnit.MILLIMETER).isKnown)
    }

    @Test
    fun conversionChangesInterpretationNotGeometry() {
        // The same coordinates read as metres (glTF), inches (user choice for an STL) and microns (3MF).
        assertEquals(120.0, ModelScale.resolve(LengthUnit.METER, null).toMm(0.12), 1e-9)
        assertEquals(50.8, ModelScale.resolve(null, LengthUnit.INCH).toMm(2.0), 1e-9)
        assertEquals(0.5, ModelScale.resolve(LengthUnit.MICRON, null).toMm(500.0), 1e-9)
        assertEquals(304.8, ModelScale.resolve(null, LengthUnit.FOOT).toMm(1.0), 1e-9)
        assertEquals(10.0, ModelScale.resolve(LengthUnit.CENTIMETER, null).toMm(1.0), 1e-9)
        // Areas and volumes scale by the square and the cube.
        val m = ModelScale.resolve(LengthUnit.METER, null)
        assertEquals(1e6, m.toMm2(1.0), 1e-3)
        assertEquals(1e9, m.toMm3(1.0), 1e-1)
    }

    @Test
    fun threeMfUnitsMapToTheirSizes() {
        assertEquals(LengthUnit.MILLIMETER, LengthUnit.from3mf(null))
        assertEquals(25.4, LengthUnit.from3mf("inch")!!.mmPerUnit, 0.0)
        assertEquals(0.001, LengthUnit.from3mf("micron")!!.mmPerUnit, 0.0)
        assertNull(LengthUnit.from3mf("furlong"))
    }

    @Test
    fun storedNamesRoundTrip() {
        for (u in LengthUnit.entries) assertEquals(u, LengthUnit.fromName(u.name))
        assertNull(LengthUnit.fromName("PARSEC"))
        assertNull(LengthUnit.fromName(null))
    }

    @Test
    fun lengthsAreFormattedReadably() {
        val us = Locale.US
        assertEquals("12.40 mm", LengthFormat.length(12.4, us))
        assertEquals("0.125 mm", LengthFormat.length(0.125, us))
        assertEquals("0.00 mm", LengthFormat.length(0.0, us))
        assertEquals("0.00 mm", LengthFormat.length(2e-7, us))
        assertEquals("9999.00 mm", LengthFormat.length(9999.0, us))
        assertEquals("12.40 m", LengthFormat.length(12_400.0, us))
        assertEquals("–", LengthFormat.length(Double.NaN, us))
        // Decimal separator follows the locale.
        assertEquals("12,40 mm", LengthFormat.length(12.4, Locale.GERMANY))
    }

    @Test
    fun sizesAreasAndVolumes() {
        val us = Locale.US
        assertEquals("65.00 × 12.50 × 30.00 mm", LengthFormat.size(65.0, 12.5, 30.0, us))
        assertEquals("12.00 × 8.00 × 3.00 m", LengthFormat.size(12_000.0, 8_000.0, 3_000.0, us))
        assertEquals("6.00 cm²", LengthFormat.area(600.0, us))
        assertEquals("1.00 cm³", LengthFormat.volume(1000.0, us))
    }
}
