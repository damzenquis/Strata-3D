package app.strata3d.capture.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Format version 2: CameraX capture without poses, with sensor orientation and the object tap. */
class CaptureFormatV2Test {

    private val tiltDown30 = run {
        // Device-to-world rotation of -30° about the device x axis.
        val a = Math.toRadians(-30.0) / 2
        SensorOrientation(sin(a), 0.0, 0.0, cos(a))
    }

    private fun manifest() = CaptureManifest(
        id = "20261111-090000", name = "Statue", createdAt = 1_762_800_000_000L, updatedAt = 1_762_800_000_000L,
        appVersion = "0.10.0", device = DeviceInfo("samsung", "SM-S176V", "s5e8535", 36),
        camera = CameraInfo("0", 4080, 3060, 4.0, 5.2224, 3.9168, IntrinsicsSource.FOCAL_LENGTH),
        arcoreVersion = null, target = null, frameCount = 2, coverage = 0.25, state = CaptureState.COMPLETE,
        objectTap = ObjectTap(0.48, 0.55, 123_456_789L, tiltDown30),
    )

    private fun frame(i: Int, orientation: SensorOrientation?) = FrameRecord(
        index = i, image = CaptureStore.imageName(i), timestampNs = 2_000_000_000L * i, pose = null,
        intrinsics = Intrinsics(3136.0, 3136.0, 2040.0, 1530.0, 4080, 3060), tracking = null,
        manual = i == 2, orientation = orientation, rotationDegrees = 90,
    )

    @Test fun manifestRoundTripsWithTheTapAndWithoutArcoreFields() {
        val m = manifest()
        val json = CaptureJson.encodeManifest(m)
        assertTrue(json.contains("\"version\":2"))
        assertFalse(json.contains("arcore"))
        assertFalse(json.contains("poseConvention"))
        assertEquals(m, CaptureJson.decodeManifest(json))
    }

    @Test fun tapWithoutOrientationRoundTrips() {
        val m = manifest().copy(objectTap = ObjectTap(0.1, 0.9, 5L, null))
        assertEquals(m, CaptureJson.decodeManifest(CaptureJson.encodeManifest(m)))
    }

    @Test fun framesRoundTripWithOrientationRotationAndNoPose() {
        for (f in listOf(frame(1, tiltDown30), frame(2, null))) {
            val line = CaptureJson.encodeFrame(f)
            assertFalse(line.contains("\"pose\""))
            assertFalse(line.contains("\"tracking\""))
            assertEquals(f, CaptureJson.decodeFrame(line))
        }
    }

    @Test fun invalidRotationIsIgnored() {
        val line = CaptureJson.encodeFrame(frame(1, null)).replace("\"rotationDegrees\":90", "\"rotationDegrees\":45")
        assertEquals(0, CaptureJson.decodeFrame(line).rotationDegrees)
    }

    @Test fun version1FramesStillDecodeWithTheirTracking() {
        val v1 = "{\"index\":1,\"image\":\"images/000001.jpg\",\"timestampNs\":5,\"pose\":{\"t\":[0,0,0],\"q\":[0,0,0,1]}," +
            "\"intrinsics\":{\"fx\":1,\"fy\":1,\"cx\":0,\"cy\":0,\"width\":4,\"height\":3},\"tracking\":\"tracking\",\"sharpness\":2}"
        val f = CaptureJson.decodeFrame(v1)
        assertEquals(Tracking.TRACKING, f.tracking)
        assertEquals(Pose.IDENTITY, f.pose)
        assertNull(f.orientation)
        assertEquals(0, f.rotationDegrees)
    }

    @Test fun downDirectionFromOrientation() {
        // Identity: the device frame is the world frame (+z up), i.e. the phone lies flat, screen up. Down is -z.
        val flat = SensorOrientation(0.0, 0.0, 0.0, 1.0).downInDevice()
        assertEquals(0.0, flat.x, 1e-12); assertEquals(0.0, flat.y, 1e-12); assertEquals(-1.0, flat.z, 1e-12)
        // Device-to-world = rotation of -30° about x, so down in the device = rotation of +30° about x applied to
        // (0, 0, -1) = (0, sin 30°, -cos 30°).
        val d = tiltDown30.downInDevice()
        assertEquals(1.0, sqrt(d.x * d.x + d.y * d.y + d.z * d.z), 1e-12)
        assertEquals(0.0, d.x, 1e-12)
        assertEquals(sin(Math.toRadians(30.0)), d.y, 1e-12)
        assertEquals(-cos(Math.toRadians(30.0)), d.z, 1e-12)
        // Held upright in portrait (device +y = world +z: rotation of +90° about x): down is device -y.
        val a = Math.toRadians(90.0) / 2
        val upright = SensorOrientation(sin(a), 0.0, 0.0, cos(a)).downInDevice()
        assertEquals(0.0, upright.x, 1e-12); assertEquals(-1.0, upright.y, 1e-12); assertEquals(0.0, upright.z, 1e-12)
    }
}
