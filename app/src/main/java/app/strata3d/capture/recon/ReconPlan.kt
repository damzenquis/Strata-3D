package app.strata3d.capture.recon

import java.util.Locale

/**
 * What the on-device reconstruction runs, decided from the phone's state (pure, unit-tested). The pipeline is the
 * one described in tools/recon/README.md: import, SfM, object box, depth maps, point cloud, surface, ground
 * cut, clean-up, texture.
 */
object ReconPlan {

    enum class Stage(val id: String) {
        IMPORT("import"), CAMERAS("cameras"), BOX("box"), DEPTH("depth"), POINTS("points"),
        SURFACE("surface"), GROUND("ground"), CLEAN("clean"), TEXTURE("texture");

        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    /** SfM feature resolution: high (~3 MP) places more photos on plain sides but needs far more memory. */
    enum class SfmSetting(val id: String, val maxPixels: Int) { HIGH("high", 3_400_000), STANDARD("standard", 1_600_000) }

    data class Settings(val sfm: SfmSetting, val threads: Int, val reason: String)

    /** Free memory that must remain while SfM runs (the guard stops SfM below this). */
    const val GUARD_MB = 600L
    /** Free memory below which any stage is stopped: the phone is too short of memory to continue safely. */
    const val HARD_GUARD_MB = 350L

    /**
     * Chooses the SfM setting from the memory free **now** and the threads from the CPU count. The memory estimate is
     * provisional, from the Galaxy A17 runs (3 MP features: 840–980 MB peak for 57 photos; 0.8 MP: ~290 MB).
     */
    fun choose(photos: Int, memAvailableMb: Long, cpuCores: Int): Settings {
        val needHigh = 200L + photos * 17L
        val high = memAvailableMb - needHigh >= GUARD_MB + 100
        val threads = (cpuCores / 2).coerceIn(1, 4)
        val reason = String.format(
            Locale.ROOT, "%s: %d MB free, about %d MB needed at 3 MP for %d photos; %d threads",
            if (high) "high" else "standard", memAvailableMb, needHigh, photos, threads,
        )
        return Settings(if (high) SfmSetting.HIGH else SfmSetting.STANDARD, threads, reason)
    }

    /** Free storage needed before starting: the working scene holds depth maps and undistorted images. */
    fun storageNeededBytes(photos: Int): Long = 500L * MB + photos * 15L * MB

    /** SfM runs at most this many times when too few photos are placed (re-solving reuses the saved matching). */
    const val SFM_ATTEMPTS = 5

    /** Photos placed below which SfM is re-solved: 60 % of the photos. */
    fun goodPlacement(photos: Int): Int = (photos * 0.6).toInt().coerceAtLeast(1)

    /** Photos placed below which the build stops (too few to build from): 30 % of the photos, and at least 10. */
    fun minPlacement(photos: Int): Int = maxOf(ReconRunner.MIN_PHOTOS, (photos * 0.3).toInt())

    /**
     * Dense cloud resolution (OpenMVS): photos are scaled down 4× but never below this many pixels on the short side.
     * Measured on the A17: 380 gave coarse flat sheets and a hole in a wing (statue); 640 (OpenMVS's default) took
     * 41 min; 500 took 4.6 min with solid wings at about 300 MB.
     */
    const val DENSE_MIN_RESOLUTION = 500

    /** Surface size: OpenMVS reduces a larger mesh to about this many faces (it never adds faces). */
    const val TARGET_FACES = 100_000

    /** A dense cloud with fewer points than this has nothing to build a surface from. */
    const val MIN_DENSE_POINTS = 1000

    /** OpenMVS's region of interest file (two lines: min x y z, max x y z) from objbox's "minx,miny,minz,maxx,maxy,maxz". */
    fun roiFile(box: String): String {
        val v = box.split(',').map { it.trim().toDouble() }
        require(v.size == 6) { "invalid box: $box" }
        return String.format(Locale.ROOT, "%s %s %s\n%s %s %s\n", v[0], v[1], v[2], v[3], v[4], v[5])
    }

    /**
     * Pieces smaller than this are removed as strays: 2 % of the surface's vertices, at least 100. (A fixed 10,000 suited
     * MVE's 40k+ vertex surfaces; an OpenMVS bottle has about 14k, so a real part could be deleted.)
     */
    fun minComponentVertices(surfaceVertices: Int): Int = maxOf(100, surfaceVertices / 50)

    /** Vertex count from a PLY header, or null. */
    fun plyVertexCount(header: String): Int? =
        Regex("(?m)^element vertex (\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull()

    /** Ground-cut height: 2 % of the median camera distance (the desk layer measured 0.3–1.5 %). */
    fun groundCutHeight(medianCameraDistance: Double): Double = 0.02 * medianCameraDistance

    /** Arguments per tool. [scene] is the MVE scene folder; paths are absolute. */
    object Args {
        fun import(images: String, scene: String) = listOf("-m", "3200000", "-i", images, scene)
        fun sfm(scene: String, setting: SfmSetting) = listOf("--max-pixels=${setting.maxPixels}", scene)
        fun box(scene: String, frames: String?, manifest: String?) =
            listOfNotNull(frames?.let { "--frames=$it" }, manifest?.let { "--manifest=$it" }, scene)
        /** OpenMVS tools run in the working folder (they resolve image paths against it); paths are relative to it. */
        fun omvsImport(threads: Int) =
            listOf("-i", ".", "-o", "scene.mvs", "--image-folder", "images/", "--max-threads", "$threads")
        fun omvsDensify(threads: Int) = listOf(
            "scene.mvs", "-o", "scene_dense.mvs", "--import-roi-file", "roi.txt", "--resolution-level", "2",
            "--min-resolution", "$DENSE_MIN_RESOLUTION", "--max-threads", "$threads",
        )
        fun omvsMesh(threads: Int) =
            listOf("scene_dense.mvs", "-o", "scene_mesh.mvs", "--target-face-num", "$TARGET_FACES", "--max-threads", "$threads")
        fun ground(height: Double, input: String, out: String) =
            listOf(String.format(Locale.ROOT, "--height=%.6f", height), input, out)
        /**
         * Clean-up of an OpenMVS surface: removes only stray pieces smaller than [minComponentVertices]. OpenMVS meshes
         * carry no confidence values (`-t0`), and MVE's collapsing of thin triangles (meant for marching-cubes
         * surfaces) is off (`-n`): a graph-cut surface has legitimate long thin triangles.
         */
        fun clean(input: String, out: String, minComponentVertices: Int) =
            listOf("-t0", "-n", "-c$minComponentVertices", input, out)
        fun texture(scene: String, mesh: String, prefix: String) = listOf("$scene::undistorted", mesh, prefix)
    }

    /** Executable name per tool, as packaged in the APK (lib<name>.so in the native-library folder). */
    fun library(tool: String) = "libmve_$tool.so"

    /** Reads "median distance X" from objbox_report.txt. */
    fun medianDistance(report: String): Double? =
        Regex("median distance ([0-9.eE+-]+)").find(report)?.groupValues?.get(1)?.toDoubleOrNull()

    /** True when objbox found a supporting surface (the scene is aligned with the surface at z = 0, +z up). */
    fun onSurface(report: String): Boolean = report.lineSequence().any { it.startsWith("method ") && it.contains("on surface") }

    private const val MB = 1024L * 1024
}
