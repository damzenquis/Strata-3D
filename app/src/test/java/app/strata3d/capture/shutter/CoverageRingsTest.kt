package app.strata3d.capture.shutter

import app.strata3d.capture.shutter.CoverageRings.Ring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRingsTest {

    @Test fun `a low circle fills the lower ring only`() {
        val c = CoverageRings()
        for (az in 0 until 360 step 15) c.add(Orient.looking(az.toDouble(), tiltDeg = 20.0))
        assertEquals(1.0, c.fraction(Ring.LOWER), 1e-9)
        assertEquals(0.0, c.fraction(Ring.UPPER), 1e-9)
    }

    @Test fun `looking down from above fills the upper ring`() {
        val c = CoverageRings()
        for (az in 0 until 180 step 15) c.add(Orient.looking(az.toDouble(), tiltDeg = 55.0))
        assertEquals(0.0, c.fraction(Ring.LOWER), 1e-9)
        // 0°..165° covered, plus one cell beyond each end: at 55° tilt, 15° of azimuth is only 8.6° of view, closer
        // than the shutter's 9°, so it would not take a photo there either.
        assertEquals(14.0 / 24, c.fraction(Ring.UPPER), 1e-9)
        assertEquals(55.0, c.centreTiltDeg(Ring.UPPER), 1e-9)
    }

    @Test fun `cells are relative to the first photo and ignore roll`() {
        val c = CoverageRings()
        c.add(Orient.looking(137.0, rollDeg = 30.0))
        c.add(Orient.looking(137.0 + 90.0, rollDeg = -50.0))
        val cells = c.cells(Ring.LOWER)
        assertTrue(cells[0])
        assertTrue(cells[6])            // 90° = 6 cells of 15°
        assertEquals(2, cells.count { it })
    }

    @Test fun `azimuth wraps around`() {
        val c = CoverageRings()
        c.add(Orient.looking(350.0))
        c.add(Orient.looking(5.0))      // 15° later, across 0°
        c.add(Orient.looking(330.0))    // 20° before the first: 5° from cell 23's centre, 10° (9.4° of view) from 22's
        val cells = c.cells(Ring.LOWER)
        assertTrue(cells[0]); assertTrue(cells[1]); assertTrue(cells[23]); assertFalse(cells[22])
        assertEquals(3, cells.count { it })
    }

    /** The case from the 2026-11-14 capture: the ring stalled at a cell the dial called missing. */
    @Test fun `a cell is missing exactly where the shutter can still take a photo`() {
        val nearly = CoverageRings()
        nearly.add(Orient.looking(0.0, tiltDeg = 16.0))
        nearly.add(Orient.looking(79.0, tiltDeg = 16.0))
        nearly.add(Orient.looking(98.0, tiltDeg = 16.0))
        assertTrue("19° apart: the 90° cell is covered", nearly.cells(Ring.LOWER)[6])

        val gap = CoverageRings()
        gap.add(Orient.looking(0.0, tiltDeg = 16.0))
        gap.add(Orient.looking(78.0, tiltDeg = 16.0))
        gap.add(Orient.looking(102.0, tiltDeg = 16.0))
        assertFalse("24° apart: the 90° cell is a real gap", gap.cells(Ring.LOWER)[6])
        // ...and there the shutter does fire: its centre is more than 9° from both photos.
        val shutter = SmartShutter()
        shutter.arm()
        for (az in listOf(0.0, 78.0, 102.0)) {
            assertEquals(SmartShutter.State.FIRE, fireAt(shutter, az)); shutter.onPhotoFinished(true)
        }
        assertEquals(SmartShutter.State.FIRE, fireAt(shutter, 90.0))
    }

    private var t = 0L
    private fun fireAt(s: SmartShutter, az: Double): SmartShutter.State {
        var last = SmartShutter.State.MOVING
        repeat(20) {
            t += 50_000_000
            last = s.onReading(t, Orient.looking(az, tiltDeg = 16.0), 0.0).state
            if (last == SmartShutter.State.FIRE) return last
        }
        return last
    }
}
