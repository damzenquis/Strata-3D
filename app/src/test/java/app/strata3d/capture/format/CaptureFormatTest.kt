package app.strata3d.capture.format

import app.strata3d.model.JsonParser
import app.strata3d.model.asDouble
import app.strata3d.model.asList
import app.strata3d.model.asString
import app.strata3d.model.at
import app.strata3d.model.get
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CaptureFormatTest {

    @get:Rule val tmp = TemporaryFolder()

    private val k640 = Intrinsics(497.0607, 497.6686, 323.4235, 245.29048, 640, 480)

    private fun manifest(state: CaptureState = CaptureState.RECORDING) = CaptureManifest(
        id = "", name = "Vase \"blue\"\n", createdAt = 1_762_700_000_000L, updatedAt = 1_762_700_000_000L,
        appVersion = "0.9.0", device = DeviceInfo("samsung", "SM-S176V", "s5e8535", 36),
        camera = CameraInfo("0", 4080, 3060, 4.0, 5.2224, 3.9168, IntrinsicsSource.ARCORE_SCALED),
        arcoreVersion = "1.57.0", target = CaptureTarget(Vec3(0.1, -0.2, -0.5), 0.25),
        frameCount = 0, coverage = 0.0, state = state, version = 1,
    )

    private fun frame(i: Int, pose: Pose? = Pose.IDENTITY) = FrameRecord(
        index = i, image = CaptureStore.imageName(i), timestampNs = 1_000_000_000L * i, pose = pose,
        intrinsics = k640.scaledTo(4080, 3060), tracking = Tracking.TRACKING, exposureNs = 8_333_333L, iso = 200,
        focusDiopters = 2.5, sharpness = 123.5, manual = i == 2,
        depth = if (i == 1) DepthRef(CaptureStore.depthName(i), CaptureStore.confidenceName(i), 160, 90) else null,
    )

    // ---- JSON ----

    @Test fun jsonWriterProducesValidCompactJson() {
        val s = JsonOut().obj {
            field("a", 1); field("b", "x\"y\\z\n"); key("c").arr { value(1.5); value(Double.NaN); obj { field("d", true) } }
            key("e").obj { }
        }.toString()
        assertEquals("{\"a\":1,\"b\":\"x\\\"y\\\\z\\n\",\"c\":[1.5,null,{\"d\":true}],\"e\":{}}", s)
        JsonParser.parse(s) // must parse
    }

    @Test fun manifestRoundTrips() {
        val m = manifest().copy(id = "20261109-120000", frameCount = 3, coverage = 0.42)
        assertEquals(m, CaptureJson.decodeManifest(CaptureJson.encodeManifest(m)))
    }

    @Test fun surfaceTargetKeepsBaseAndCentreExplicit() {
        val t = CaptureTarget.onSurface(Vec3(0.1, -0.7, -0.4), 0.12)
        assertEquals(-0.58, t.center.y, 1e-12)
        val m = manifest().copy(id = "x", target = t)
        assertEquals(t, CaptureJson.decodeManifest(CaptureJson.encodeManifest(m)).target)
        val bigger = t.withRadius(0.2)
        assertEquals(t.base, bigger.base)
        assertEquals(-0.5, bigger.center.y, 1e-12)
        // A two-view target has no base and resizes in place.
        val p = CaptureTarget(Vec3(0.0, 0.0, -0.5), 0.1)
        assertEquals(p.center, p.withRadius(0.3).center)
        assertEquals(Placement.TWO_VIEW, CaptureJson.decodeManifest(CaptureJson.encodeManifest(m.copy(target = p))).target!!.placement)
    }

    @Test fun framesRoundTripIncludingMissingPoseDepthAndManual() {
        for (f in listOf(frame(1), frame(2), frame(3, pose = null))) {
            assertEquals(f, CaptureJson.decodeFrame(CaptureJson.encodeFrame(f)))
        }
    }

    @Test fun newerFormatAndForeignFilesAreRejected() {
        val newer = CaptureJson.encodeManifest(manifest().copy(id = "x")).replace("\"version\":1", "\"version\":99")
        try { CaptureJson.decodeManifest(newer); fail() } catch (e: CaptureFormatException) { }
        try { CaptureJson.decodeManifest("{\"format\":\"other\",\"version\":1}"); fail() } catch (e: CaptureFormatException) { }
        try { CaptureJson.decodeFrame("{\"index\":1,") ; fail() } catch (e: CaptureFormatException) { }
    }

    // ---- intrinsics ----

    @Test fun intrinsicsScaleUniformlyToTheSameAspect() {
        val k = k640.scaledTo(4080, 3060)
        assertEquals(497.0607 * 6.375, k.fx, 1e-9)
        assertEquals(323.4235 * 6.375, k.cx, 1e-9)
        assertEquals(245.29048 * 6.375, k.cy, 1e-9)
    }

    @Test fun intrinsicsScaleWithCentreCropToAnotherAspect() {
        // The A17's ARCore GPU texture: 1920x1080 is x3 of 640x480 with 180 rows cropped (measured c_y = 556.87).
        val k = k640.scaledTo(1920, 1080)
        assertEquals(497.0607 * 3, k.fx, 1e-9)
        assertEquals(245.29048 * 3 - 180, k.cy, 1e-9)
        assertEquals(556.87146, k.cy, 1.5)
    }

    // ---- pose math ----

    @Test fun quaternionMatrixRoundTrip() {
        val rnd = Random(3)
        repeat(200) {
            val q = DoubleArray(4) { rnd.nextDouble(-1.0, 1.0) }
            val m = PoseMath.quatToMatrix(q[0], q[1], q[2], q[3])
            val back = PoseMath.matrixToQuat(m)
            val m2 = PoseMath.quatToMatrix(back[0], back[1], back[2], back[3])
            for (i in 0 until 9) assertEquals(m[i], m2[i], 1e-9)
            assertTrue(back[3] >= 0)
        }
    }

    @Test fun identityArcorePoseIsYAndZFlippedInOpenCv() {
        val (r, t) = PoseMath.worldToCameraOpenCv(Pose.IDENTITY)
        val expected = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)
        for (i in 0 until 9) assertEquals(expected[i], r[i], 1e-12)
        assertEquals(0.0, t.length(), 1e-12)
    }

    @Test fun projectionFollowsArcoreAxes() {
        val k = k640
        // Camera at the origin looking down -z: a point straight ahead hits the principal point,
        // a point above (+y) lands above the centre (smaller v), a point to the right (+x) at larger u.
        val c = PoseMath.project(Pose.IDENTITY, k, Vec3(0.0, 0.0, -2.0))!!
        assertEquals(k.cx, c.first, 1e-9); assertEquals(k.cy, c.second, 1e-9)
        assertTrue(PoseMath.project(Pose.IDENTITY, k, Vec3(0.0, 0.5, -2.0))!!.second < k.cy)
        assertTrue(PoseMath.project(Pose.IDENTITY, k, Vec3(0.5, 0.0, -2.0))!!.first > k.cx)
        assertNull(PoseMath.project(Pose.IDENTITY, k, Vec3(0.0, 0.0, 2.0)))
    }

    @Test fun projectionOfRotatedAndTranslatedCamera() {
        // Camera at (3, 0, 0) yawed +90 degrees about +y, so it looks down -x towards the origin.
        val a = PI / 2
        val pose = Pose(3.0, 0.0, 0.0, 0.0, sin(a / 2), 0.0, cos(a / 2))
        val fwd = PoseMath.forward(pose)
        assertEquals(-1.0, fwd.x, 1e-12)
        val c = PoseMath.project(pose, k640, Vec3.ZERO)!!
        assertEquals(k640.cx, c.first, 1e-9); assertEquals(k640.cy, c.second, 1e-9)
        // Looking down -x with +y up, the camera's right (+x) is world -z, so a point at world -z lands right of centre.
        assertTrue(PoseMath.project(pose, k640, Vec3(0.0, 0.0, -0.5))!!.first > k640.cx)
    }

    @Test fun poseInterpolationIsLinearInPositionAndSphericalInRotation() {
        val a = Pose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        val yaw90 = Pose(1.0, 2.0, -3.0, 0.0, sin(PI / 4), 0.0, cos(PI / 4))
        val mid = PoseMath.interpolate(a, yaw90, 0.5)
        assertEquals(0.5, mid.tx, 1e-12); assertEquals(1.0, mid.ty, 1e-12); assertEquals(-1.5, mid.tz, 1e-12)
        // Halfway between 0 and 90 degrees about +y is 45 degrees.
        assertEquals(sin(PI / 8), mid.qy, 1e-9); assertEquals(cos(PI / 8), mid.qw, 1e-9)
        val start = PoseMath.interpolate(a, yaw90, 0.0)
        assertEquals(0.0, start.tx, 1e-12); assertEquals(1.0, start.qw, 1e-12)
        // The same rotation written with the opposite sign must not spin the long way round.
        val negated = yaw90.copy(qy = -yaw90.qy, qw = -yaw90.qw)
        val mid2 = PoseMath.interpolate(a, negated, 0.5)
        assertEquals(cos(PI / 8), kotlin.math.abs(mid2.qw), 1e-9)
    }

    // ---- store ----

    @Test fun storeCreatesAppendsAndSurvivesATruncatedLastLine() {
        val store = CaptureStore(tmp.newFolder("captures"))
        val created = store.create(manifest())
        val dir = created.dir
        for (i in 1..3) {
            File(dir, CaptureStore.imageName(i)).writeBytes(byteArrayOf(1, 2, 3))
            store.appendFrame(dir, frame(i))
        }
        // A crash mid-write leaves half a line behind.
        File(dir, CaptureStore.FRAMES).appendText("{\"index\":4,\"image\":\"images/0000")
        val frames = store.readFrames(dir)
        assertEquals(listOf(1, 2, 3), frames.map { it.index })
        // A frame whose image never landed is ignored too.
        store.appendFrame(dir, frame(5))
        assertEquals(3, store.readFrames(dir).size)
    }

    @Test fun listingRepairsAnInterruptedRecordingButNotTheActiveOne() {
        val store = CaptureStore(tmp.newFolder("captures"))
        val a = store.create(manifest())
        File(a.dir, CaptureStore.imageName(1)).writeBytes(byteArrayOf(1))
        store.appendFrame(a.dir, frame(1))
        val b = store.create(manifest().copy(createdAt = manifest().createdAt + 5000))
        assertTrue(a.id != b.id)
        val listed = store.list(activeId = b.id)
        assertEquals(listOf(b.id, a.id), listed.map { it.id })
        assertEquals(CaptureState.RECORDING, listed[0].manifest.state)
        assertEquals(CaptureState.INTERRUPTED, listed[1].manifest.state)
        assertEquals(1, listed[1].manifest.frameCount)
        assertEquals(CaptureState.INTERRUPTED, store.readManifest(a.dir).state) // persisted
    }

    @Test fun storeIdsAreUniqueAndDeleteStaysInsideTheStore() {
        val store = CaptureStore(tmp.newFolder("captures"))
        val a = store.create(manifest())
        val b = store.create(manifest())
        assertTrue(a.id != b.id)
        val outside = tmp.newFolder("precious")
        try { store.delete(outside); fail() } catch (e: IllegalArgumentException) { }
        assertTrue(outside.exists())
        assertTrue(store.delete(a.dir))
        assertFalse(a.dir.exists())
        assertEquals(listOf(b.id), store.list().map { it.id })
    }

    @Test fun renameKeepsEverythingElse() {
        val store = CaptureStore(tmp.newFolder("captures"))
        val a = store.create(manifest())
        val renamed = store.rename(a.dir, "  Ceramic vase ")
        assertEquals("Ceramic vase", renamed.name)
        assertEquals(a.manifest.copy(name = "Ceramic vase", updatedAt = renamed.updatedAt), store.readManifest(a.dir))
    }

    // ---- exports ----

    @Test fun colmapExportUsesWorldToCameraAndSharesCameras() {
        val frames = listOf(frame(1), frame(2, Pose(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)), frame(3, pose = null))
        val files = CaptureExport.colmap(frames)
        val cameras = files.getValue("cameras.txt").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(1, cameras.size)
        assertTrue(cameras[0].startsWith("1 PINHOLE 4080 3060 "))
        val images = files.getValue("images.txt").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, images.size) // the pose-less frame is left out
        // Identity ARCore pose = 180 degrees about x in OpenCV: q = (w 0, x 1, y 0, z 0).
        val p1 = images[0].split(' ')
        assertEquals(0.0, p1[1].toDouble(), 1e-9); assertEquals(1.0, kotlin.math.abs(p1[2].toDouble()), 1e-9)
        assertEquals("000001.jpg", p1[9])
        // Camera centre at z = 1: t = -R C = (0, 0, 1) after the flip.
        val p2 = images[1].split(' ')
        assertEquals(0.0, p2[5].toDouble(), 1e-9); assertEquals(0.0, p2[6].toDouble(), 1e-9); assertEquals(1.0, p2[7].toDouble(), 1e-9)
    }

    @Test fun nerfstudioExportIsCameraToWorld() {
        val json = JsonParser.parse(CaptureExport.nerfstudio(listOf(frame(1, Pose(1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0)), frame(2, null))))
        val frames = json.get("frames").asList()!!
        assertEquals(1, frames.size)
        assertEquals("images/000001.jpg", frames[0].get("file_path").asString())
        val row0 = frames[0].get("transform_matrix").at(0).asList()!!.map { it.asDouble()!! }
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0), row0)
    }

    @Test fun zipContainsDatasetExportsAndReadme() {
        val store = CaptureStore(tmp.newFolder("captures"))
        val a = store.create(manifest())
        File(a.dir, CaptureStore.imageName(1)).writeBytes(ByteArray(5000) { it.toByte() })
        File(a.dir, CaptureStore.DEPTH).mkdirs()
        File(a.dir, CaptureStore.depthName(1)).writeBytes(ByteArray(160 * 90 * 2))
        store.appendFrame(a.dir, frame(1))
        val bytes = ByteArrayOutputStream()
        var lastProgress = 0L to 0L
        store.let { CaptureExport.writeZip(it, a.dir, bytes, onProgress = { d, t -> lastProgress = d to t }) }
        val names = mutableMapOf<String, Int>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; names[e.name] = z.readBytes().size }
        }
        assertEquals(5000, names["images/000001.jpg"])
        assertEquals(160 * 90 * 2, names["depth/000001.d16"])
        for (n in listOf("manifest.json", "frames.jsonl", "colmap/cameras.txt", "colmap/images.txt", "colmap/points3D.txt", "transforms.json", "README.txt")) {
            assertNotNull(n, names[n])
        }
        assertEquals(lastProgress.second, lastProgress.first)
    }

    @Test fun zipExportCanBeCancelled() {
        val store = CaptureStore(tmp.newFolder("captures"))
        val a = store.create(manifest())
        try {
            CaptureExport.writeZip(store, a.dir, ByteArrayOutputStream(), isCancelled = { true })
            fail()
        } catch (e: InterruptedException) { }
    }
}
