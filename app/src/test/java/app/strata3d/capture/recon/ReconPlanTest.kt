package app.strata3d.capture.recon

import app.strata3d.capture.recon.ReconPlan.SfmSetting
import app.strata3d.capture.recon.ReconPlan.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconPlanTest {

    @Test fun `high SfM only when it fits with the guard margin`() {
        // 58 photos need about 200 + 58 x 17 = 1186 MB at 3 MP; the guard keeps 600 MB plus 100 MB margin.
        assertEquals(SfmSetting.STANDARD, ReconPlan.choose(58, 1199, 8).sfm)    // A17 today: standard (as logged)
        assertEquals(SfmSetting.STANDARD, ReconPlan.choose(58, 1885, 8).sfm)
        assertEquals(SfmSetting.HIGH, ReconPlan.choose(58, 1886, 8).sfm)
        assertEquals(SfmSetting.STANDARD, ReconPlan.choose(84, 1442, 8).sfm)    // statue today: standard
        assertTrue(ReconPlan.choose(58, 1199, 8).reason.startsWith("standard: 1199 MB free, about 1186 MB"))
    }

    @Test fun `threads are half the cores, between 1 and 4`() {
        assertEquals(4, ReconPlan.choose(10, 4000, 8).threads)
        assertEquals(4, ReconPlan.choose(10, 4000, 12).threads)
        assertEquals(2, ReconPlan.choose(10, 4000, 4).threads)
        assertEquals(1, ReconPlan.choose(10, 4000, 1).threads)
    }

    @Test fun `OpenMVS region of interest and dense cloud size`() {
        assertEquals("-0.3314 0.0067 -0.3314\n0.3314 1.1671 0.3314\n", ReconPlan.roiFile("-0.3314,0.0067,-0.3314,0.3314,1.1671,0.3314"))
        assertEquals(477611, ReconPlan.plyVertexCount("ply\nformat binary_little_endian 1.0\nelement vertex 477611\nproperty float x\n"))
        assertNull(ReconPlan.plyVertexCount("not a ply"))
    }

    @Test fun `tool arguments match the measured pipeline`() {
        assertEquals(listOf("-m", "3200000", "-i", "/c/images", "/w/scene"), ReconPlan.Args.import("/c/images", "/w/scene"))
        assertEquals(listOf("--max-pixels=1600000", "/w/scene"), ReconPlan.Args.sfm("/w/scene", SfmSetting.STANDARD))
        assertEquals(listOf("--max-pixels=3400000", "/w/scene"), ReconPlan.Args.sfm("/w/scene", SfmSetting.HIGH))
        assertEquals(listOf("--frames=/c/f", "--manifest=/c/m", "/w/scene"), ReconPlan.Args.box("/w/scene", "/c/f", "/c/m"))
        assertEquals(listOf("/w/scene"), ReconPlan.Args.box("/w/scene", null, null))
        assertEquals(listOf("-i", ".", "-o", "scene.mvs", "--image-folder", "images/", "--max-threads", "4"), ReconPlan.Args.omvsImport(4))
        assertEquals(listOf("scene.mvs", "-o", "scene_dense.mvs", "--import-roi-file", "roi.txt", "--resolution-level", "2",
            "--min-resolution", "500", "--max-threads", "4"), ReconPlan.Args.omvsDensify(4))
        assertEquals(listOf("scene_dense.mvs", "-o", "scene_mesh.mvs", "--target-face-num", "100000", "--max-threads", "2"),
            ReconPlan.Args.omvsMesh(2))
        assertEquals(listOf("-t0", "-n", "-c275", "in.ply", "out.ply"), ReconPlan.Args.clean("in.ply", "out.ply", 275))
        assertEquals(275, ReconPlan.minComponentVertices(13771))   // the OpenMVS bottle: strays under 2 % go
        assertEquals(100, ReconPlan.minComponentVertices(2000))
        assertEquals(listOf("--height=0.044981", "in.ply", "out.ply"), ReconPlan.Args.ground(0.044981, "in.ply", "out.ply"))
        assertEquals(listOf("/w/scene::undistorted", "m.ply", "/c/model/model"), ReconPlan.Args.texture("/w/scene", "m.ply", "/c/model/model"))
        assertEquals("libmve_dmrecon.so", ReconPlan.library("dmrecon"))
    }

    @Test fun `objbox report parsing`() {
        val report = """
            cameras 49, look-at (-0.137, 0.088, 1.918), median distance 2.24903
            gravity from 49 photos: spread median 1.157 deg, 90% 4.335 deg
            method v2 (gravity, on surface)
            box -0.3314,0.0067,-0.3314,0.3314,1.1671,0.3314
        """.trimIndent()
        assertEquals(2.24903, ReconPlan.medianDistance(report)!!, 1e-9)
        assertTrue(ReconPlan.onSurface(report))
        assertEquals(0.0449806, ReconPlan.groundCutHeight(2.24903), 1e-6)
        assertFalse(ReconPlan.onSurface(report.replace("on surface", "no surface")))
        assertFalse(ReconPlan.onSurface("method legacy"))
        assertNull(ReconPlan.medianDistance("no distance here"))
    }

    @Test fun `placement thresholds for re-solving and stopping`() {
        assertEquals(34, ReconPlan.goodPlacement(58))      // 4 or 33 of 58 placed: re-solve; 49 of 58: fine
        assertEquals(17, ReconPlan.minPlacement(58))       // fewer than 17 of 58 after all attempts: stop
        assertEquals(10, ReconPlan.minPlacement(20))       // never fewer than 10 photos
        assertEquals(5, ReconPlan.SFM_ATTEMPTS)
    }

    @Test fun `storage need grows with photos`() {
        assertEquals((500L + 58 * 15) * 1024 * 1024, ReconPlan.storageNeededBytes(58))
    }

    @Test fun `status round trips and old fields default`() {
        val s = ReconStatus("20261111-105419", ReconStatus.State.RUNNING, Stage.DEPTH, 3, 6, 1L, 2L,
            sfmSetting = "standard: 1199 MB free", boxMethod = "v2 (gravity, on surface)")
        assertEquals(s, ReconStatus.decode(s.encode()))
        val f = ReconStatus("x", ReconStatus.State.FAILED, error = ReconStatus.Error.TAP_OUTSIDE, detail = "objbox exit 3")
        assertEquals(f, ReconStatus.decode(f.encode()))
        val d = ReconStatus("x", ReconStatus.State.DONE, model = "model/model.obj", photos = 58, placed = 49, vertices = 42046)
        assertEquals(d, ReconStatus.decode(d.encode()))
    }
}
