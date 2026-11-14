package app.strata3d.capture.camera

import app.strata3d.capture.format.IntrinsicsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraGeometryTest {

    @Test fun fitCenterLetterboxesAPortraitImageOnATallScreen() {
        // 3:4 upright photo on a 1080 x 2340 screen: full width, bars above and below.
        val r = CameraGeometry.fitCenter(1080f, 2340f, 3060, 4080)
        assertEquals(0f, r.left, 1e-3f)
        assertEquals(1080f, r.width, 1e-3f)
        assertEquals(1440f, r.height, 1e-3f)
        assertEquals(450f, r.top, 1e-3f)
    }

    @Test fun tapsMapIntoTheImageAndBarsAreRejected() {
        val r = CameraGeometry.fitCenter(1080f, 2340f, 3060, 4080)
        val (u, v) = CameraGeometry.tapToImage(540f, 450f + 720f, r)!!
        assertEquals(0.5, u, 1e-6); assertEquals(0.5, v, 1e-6)
        val (u2, v2) = CameraGeometry.tapToImage(0f, 450f, r)!!
        assertEquals(0.0, u2, 1e-6); assertEquals(0.0, v2, 1e-6)
        assertNull(CameraGeometry.tapToImage(540f, 100f, r))     // on the top bar
        assertNull(CameraGeometry.tapToImage(540f, 2000f, r))    // on the bottom bar
    }

    @Test fun uprightSizeSwapsForQuarterTurns() {
        assertEquals(3060 to 4080, CameraGeometry.uprightSize(4080, 3060, 90))
        assertEquals(3060 to 4080, CameraGeometry.uprightSize(4080, 3060, 270))
        assertEquals(4080 to 3060, CameraGeometry.uprightSize(4080, 3060, 0))
    }

    // Galaxy A17 main camera as Camera2 reports it: 4.0 mm lens, 5.2224 mm wide sensor, 4080-pixel array.
    private val a17 = arrayOf(4080, 3060, 4080)

    @Test fun focalLengthIntrinsicsMatchTheSensorGeometry() {
        val (k, src) = CameraGeometry.intrinsics(4080, 3060, a17[0], a17[1], a17[2], 4.0, 5.2224, null)!!
        assertEquals(IntrinsicsSource.FOCAL_LENGTH, src)
        assertEquals(3125.0, k.fx, 1e-6)               // 4.0 mm / (5.2224 mm / 4080 px)
        assertEquals(k.fx, k.fy, 1e-12)
        assertEquals(2039.5, k.cx, 1e-6); assertEquals(1529.5, k.cy, 1e-6)
        // Same field of view at a smaller 4:3 size scales uniformly.
        val (half, _) = CameraGeometry.intrinsics(2040, 1530, a17[0], a17[1], a17[2], 4.0, 5.2224, null)!!
        assertEquals(1562.5, half.fx, 1e-6)
    }

    @Test fun portraitStoredPixelsSwapAxes() {
        val (k, _) = CameraGeometry.intrinsics(3060, 4080, a17[0], a17[1], a17[2], 4.0, 5.2224, null)!!
        assertEquals(3060, k.width); assertEquals(4080, k.height)
        assertEquals(3125.0, k.fx, 1e-6)
        assertEquals(1529.5, k.cx, 1e-6); assertEquals(2039.5, k.cy, 1e-6)
    }

    @Test fun sixteenByNineIsACentreCropOfTheArray() {
        // 4080 x 2296 from a 4080 x 3060 array: same width, cropped top and bottom; focal unchanged.
        val (k, _) = CameraGeometry.intrinsics(4080, 2296, a17[0], a17[1], a17[2], 4.0, 5.2224, null)!!
        assertEquals(3125.0, k.fx, 1e-6)
        assertEquals(1529.5 - (3060 - 2296) / 2.0, k.cy, 1e-6)
    }

    @Test fun publishedCalibrationWins() {
        val cal = floatArrayOf(3200f, 3190f, 2050f, 1520f, 0f)
        val (k, src) = CameraGeometry.intrinsics(4080, 3060, a17[0], a17[1], a17[2], 4.0, 5.2224, cal)!!
        assertEquals(IntrinsicsSource.CAMERA2, src)
        assertEquals(3200.0, k.fx, 1e-3); assertEquals(3190.0, k.fy, 1e-3)
        assertEquals(2050.0, k.cx, 1e-3); assertEquals(1520.0, k.cy, 1e-3)
    }

    @Test fun zeroCalibrationFallsBackAndMissingDataGivesNull() {
        val zeros = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        assertEquals(IntrinsicsSource.FOCAL_LENGTH, CameraGeometry.intrinsics(4080, 3060, 4080, 3060, 4080, 4.0, 5.2224, zeros)!!.second)
        assertNull(CameraGeometry.intrinsics(4080, 3060, 4080, 3060, null, 4.0, 5.2224, null))
        assertNull(CameraGeometry.intrinsics(4080, 3060, 4080, 3060, 4080, null, 5.2224, null))
        assertNull(CameraGeometry.intrinsics(0, 3060, 4080, 3060, 4080, 4.0, 5.2224, null))
    }
}
