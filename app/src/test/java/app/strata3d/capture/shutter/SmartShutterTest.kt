package app.strata3d.capture.shutter

import app.strata3d.capture.shutter.SmartShutter.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Orientation helpers shared by the shutter and coverage tests. */
internal object Orient {
    /**
     * A phone held in portrait whose back camera looks toward azimuth [azDeg] (counter-clockwise from world +x, seen
     * from above), tilted [tiltDeg] below the horizon, and rolled [rollDeg] about the lens axis.
     */
    fun looking(azDeg: Double, tiltDeg: Double = 20.0, rollDeg: Double = 0.0): DeviceOrientation {
        val a = Math.toRadians(azDeg); val t = Math.toRadians(tiltDeg); val r = Math.toRadians(rollDeg)
        val f = doubleArrayOf(cos(t) * cos(a), cos(t) * sin(a), -sin(t))
        val up = doubleArrayOf(0.0, 0.0, 1.0)
        val right0 = cross(f, up).normalized()
        val back = doubleArrayOf(-f[0], -f[1], -f[2])
        val devUp0 = cross(back, right0)
        // roll about the lens axis
        val right = DoubleArray(3) { right0[it] * cos(r) + devUp0[it] * sin(r) }
        val devUp = DoubleArray(3) { -right0[it] * sin(r) + devUp0[it] * cos(r) }
        // columns: device x, y, z in world
        return fromMatrix(
            right[0], devUp[0], back[0],
            right[1], devUp[1], back[1],
            right[2], devUp[2], back[2],
        )
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun fromMatrix(m00: Double, m01: Double, m02: Double, m10: Double, m11: Double, m12: Double,
                           m20: Double, m21: Double, m22: Double): DeviceOrientation {
        val tr = m00 + m11 + m22
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            DeviceOrientation.of((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2
            DeviceOrientation.of(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2
            DeviceOrientation.of((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2
            DeviceOrientation.of((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
        }
    }
}

class SmartShutterTest {

    private val ms = 1_000_000L
    private val still = 0.02          // rad/s: hand-held but steady
    private val moving = 0.8          // rad/s: walking around

    /** Feeds steady readings at [o] every 20 ms for [durationMs] from [t0]; returns the time after and every output. */
    private fun hold(s: SmartShutter, o: DeviceOrientation, t0: Long, durationMs: Long, speed: Double = still):
        Pair<Long, List<SmartShutter.Output>> {
        val out = ArrayList<SmartShutter.Output>()
        var t = t0
        while (t < t0 + durationMs * ms) { out += s.onReading(t, o, speed); t += 20 * ms }
        return t to out
    }

    @Test fun `identity orientation looks along minus z`() {
        val f = DeviceOrientation.of(0.0, 0.0, 0.0, 1.0).cameraForward()
        assertEquals(0.0, f[0], 1e-12); assertEquals(0.0, f[1], 1e-12); assertEquals(-1.0, f[2], 1e-12)
        val g = Orient.looking(90.0, 30.0, 25.0).cameraForward()
        assertEquals(0.0, g[0], 1e-9); assertEquals(cos(Math.toRadians(30.0)), g[1], 1e-9)
        assertEquals(-sin(Math.toRadians(30.0)), g[2], 1e-9)
    }

    @Test fun `locked until the object is marked`() {
        val s = SmartShutter()
        val (_, out) = hold(s, Orient.looking(0.0), 0, 1000)
        assertTrue(out.all { it.state == State.LOCKED })
        assertFalse(s.tryManual(0, Orient.looking(0.0)))
    }

    @Test fun `first photo fires once steady, then waits while saving`() {
        val s = SmartShutter().apply { arm() }
        val (t, out) = hold(s, Orient.looking(0.0), 0, 400)
        assertEquals(1, out.count { it.state == State.FIRE })
        val fireAt = out.indexOfFirst { it.state == State.FIRE }
        assertTrue("needs 150 ms steady first", fireAt >= 7)
        assertTrue(out.drop(fireAt + 1).all { it.state == State.SAVING })
        assertFalse("manual refused while a photo is in flight", s.tryManual(t, Orient.looking(0.0)))
    }

    @Test fun `shaking never fires`() {
        val s = SmartShutter().apply { arm() }
        val rnd = Random(3)
        var t = 0L
        repeat(500) {
            val speed = if (it % 2 == 0) 0.5 + rnd.nextDouble() else 0.25
            assertTrue(s.onReading(t, Orient.looking(0.0), speed).state != State.FIRE)
            t += 20 * ms
        }
    }

    @Test fun `next photo needs the step, then steadiness`() {
        val s = SmartShutter().apply { arm() }
        var (t, _) = hold(s, Orient.looking(0.0), 0, 300)
        s.onPhotoFinished(saved = true)
        assertEquals(1, s.photoCount)

        // At 20° tilt a 4.5° step around the object turns the camera by 4.5° x cos(20°) = 4.23°: progress 0.47.
        val half = s.onReading(t, Orient.looking(4.5), still); t += 20 * ms
        assertEquals(State.MOVING, half.state)
        assertEquals(4.5 * cos(Math.toRadians(20.0)) / 9.0, half.progress, 0.005)

        val fast = s.onReading(t, Orient.looking(10.0), moving); t += 20 * ms
        assertEquals(State.HOLD_STEADY, fast.state)

        val (_, out) = hold(s, Orient.looking(10.0), t, 600)
        assertEquals(1, out.count { it.state == State.FIRE })
    }

    @Test fun `twisting the phone about the lens does not count as moving`() {
        val s = SmartShutter().apply { arm() }
        val (t, _) = hold(s, Orient.looking(0.0), 0, 300)
        s.onPhotoFinished(saved = true)
        val (_, out) = hold(s, Orient.looking(0.0, rollDeg = 40.0), t, 500)
        assertTrue(out.all { it.state == State.MOVING && it.progress < 0.01 })
    }

    @Test fun `walking back to a covered direction does not fire again`() {
        val s = SmartShutter().apply { arm() }
        var t = 0L
        for (az in listOf(0.0, 10.0, 20.0)) {
            val (t2, out) = hold(s, Orient.looking(az), t, 500)   // longer than the 400 ms minimum interval
            assertEquals("photo at $az", 1, out.count { it.state == State.FIRE })
            s.onPhotoFinished(saved = true); t = t2
        }
        val (_, back) = hold(s, Orient.looking(10.0), t, 1000)
        assertTrue(back.none { it.state == State.FIRE })
    }

    @Test fun `a failed save leaves the viewpoint open`() {
        val s = SmartShutter().apply { arm() }
        val (t, _) = hold(s, Orient.looking(0.0), 0, 300)
        s.onPhotoFinished(saved = false)
        assertEquals(0, s.photoCount)
        val (_, out) = hold(s, Orient.looking(0.0), t, 600)
        assertEquals(1, out.count { it.state == State.FIRE })
    }

    @Test fun `minimum interval between automatic photos`() {
        val s = SmartShutter(SmartShutter.Settings(minIntervalMs = 1000)).apply { arm() }
        val (t, out) = hold(s, Orient.looking(0.0), 0, 300)
        val firstFire = out.indexOfFirst { it.state == State.FIRE } * 20L
        s.onPhotoFinished(saved = true)
        val (_, out2) = hold(s, Orient.looking(15.0), t, 1500)
        val second = t / ms + out2.indexOfFirst { it.state == State.FIRE } * 20L
        assertTrue("second photo ${second - firstFire} ms after the first", second - firstFire >= 1000)
    }

    @Test fun `paused shutter does not fire, manual still works`() {
        val s = SmartShutter().apply { arm(); setPaused(true) }
        val (t, out) = hold(s, Orient.looking(0.0), 0, 500)
        assertTrue(out.all { it.state == State.PAUSED })
        assertTrue(s.tryManual(t, Orient.looking(0.0)))
        s.onPhotoFinished(saved = true)
        assertEquals(1, s.photoCount)
    }

    @Test fun `manual photo without sensors is allowed and gated`() {
        val s = SmartShutter().apply { arm() }
        assertTrue(s.tryManual(0, null))
        assertFalse(s.tryManual(1, null))
        s.onPhotoFinished(saved = true)
        assertTrue(s.tryManual(2, null))
    }

    @Test fun `walking a full circle gives evenly spaced photos`() {
        val s = SmartShutter().apply { arm() }
        val shots = ArrayList<Double>()
        var t = 0L
        var az = 0.0
        // Walk at 40°/s, stopping for 250 ms every 3°: like a user stepping and briefly holding still.
        while (az < 360.0) {
            for (k in 0 until 4) {
                val o = s.onReading(t, Orient.looking(az), 0.7)
                if (o.state == State.FIRE) { shots += az; s.onPhotoFinished(true) }
                az += 0.75; t += 20 * ms
            }
            val (t2, out) = hold(s, Orient.looking(az), t, 250)
            if (out.any { it.state == State.FIRE }) { shots += az; s.onPhotoFinished(true) }
            t = t2
        }
        // A 9° change of viewing direction at 20° tilt is 9.58° around the object. Stopping every 3°, a photo comes
        // at the first stop past 9.58°, i.e. every 12°: 30 photos for the circle.
        val minAz = 9.0 / cos(Math.toRadians(20.0))
        assertTrue("got ${shots.size} photos", shots.size in 29..38)
        val gaps = shots.zipWithNext { a, b -> b - a }
        assertTrue("gaps $gaps", gaps.all { it >= minAz - 1e-6 && it <= minAz + 3.0 })
    }
}
