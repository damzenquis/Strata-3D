package app.strata3d.capture.recon

import android.os.Process
import android.os.StatFs
import android.util.Log
import app.strata3d.capture.format.CaptureStore
import app.strata3d.capture.recon.ReconPlan.Stage
import app.strata3d.capture.recon.ReconStatus.Error
import app.strata3d.capture.recon.ReconStatus.State
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Runs the reconstruction pipeline for one capture, as child processes of the reconstruction process (see
 * [ReconService]). Blocking: call it on a background thread.
 *
 * - Each stage runs a packaged tool ([ReconPlan.library]) at background priority, with [ReconPlan.Settings.threads]
 *   OpenMP threads. Its output goes to `logs/<stage>.log`.
 * - Memory guard: while SfM runs at the high setting, free memory below [ReconPlan.GUARD_MB] stops it and SfM is
 *   retried at standard (recorded in the status). Any stage below [ReconPlan.HARD_GUARD_MB] is stopped and the
 *   reconstruction fails with [Error.LOW_MEMORY]: the phone comes first.
 * - [cancel] stops the running tool at once.
 * - The working scene lives in `files/recon/<captureId>/` and is deleted at the end; the model goes to
 *   `<capture>/model/`.
 * - Every outcome is written to `status.json`; nothing fails silently.
 */
class ReconRunner(
    private val captureDir: File,
    private val workRoot: File,
    private val nativeLibDir: File,
    private val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
) {
    private val captureId = captureDir.name
    val workDir = File(workRoot, captureId)
    private val scene = File(workDir, "scene")
    private val logs = File(workDir, "logs")
    private val statusFile = File(workDir, STATUS)
    private val startedAt = System.currentTimeMillis()

    @Volatile private var cancelled = false
    @Volatile private var current: java.lang.Process? = null
    private var status = ReconStatus(captureId, State.RUNNING, startedAt = startedAt, updatedAt = startedAt)

    fun cancel() {
        cancelled = true
        current?.destroyForcibly()
    }

    /**
     * Runs the pipeline; returns the final status (also written to status.json).
     *
     * - [review]: stop after the object box with [State.REVIEW], keeping the scene, so the user can check the box.
     * - [resume]: continue a reviewed build from the depth stage, with the box in `scene/objbox.txt` (as the review
     *   screen saved it).
     */
    fun run(review: Boolean = false, resume: Boolean = false, onProgress: (ReconStatus) -> Unit = {}): ReconStatus {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)   // children inherit it
        if (resume) {
            val prev = readStatus(workRoot, captureId)
            if (prev?.state != State.REVIEW || !File(scene, "objbox.txt").isFile) {
                status = status.copy(state = State.FAILED, error = Error.INTERRUPTED, detail = "nothing to resume")
                publish(status, onProgress)
                return status
            }
            // The time spent paused for the review is not build time: shift the start by it.
            val paused = (System.currentTimeMillis() - prev.updatedAt).coerceAtLeast(0)
            status = prev.copy(state = State.RUNNING, startedAt = prev.startedAt + paused)
        } else {
            workDir.deleteRecursively()
        }
        logs.mkdirs()
        val final = try {
            if (resume) dense(onProgress) else pipeline(review, onProgress)
        } catch (e: Stop) {
            status.copy(state = if (e.error == null) State.CANCELLED else State.FAILED, error = e.error, detail = e.detail)
        } catch (e: Exception) {
            Log.e(TAG, "reconstruction", e)
            status.copy(state = State.FAILED, error = Error.TOOL_FAILED, detail = e.javaClass.simpleName + ": " + e.message)
        }
        publish(final.copy(updatedAt = System.currentTimeMillis()), onProgress)
        if (final.state == State.REVIEW) return status       // the scene is needed to continue
        // Keep only the status and logs: the scene (depth maps, undistorted images) is large.
        scene.deleteRecursively()
        workDir.listFiles { f -> f.name.endsWith(".ply") }?.forEach { it.delete() }
        return status
    }

    private fun pipeline(review: Boolean, onProgress: (ReconStatus) -> Unit): ReconStatus {
        val images = File(captureDir, CaptureStore.IMAGES)
        val photos = images.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0
        if (photos < MIN_PHOTOS) throw Stop(Error.TOO_FEW_PHOTOS_PLACED, "$photos photos; at least $MIN_PHOTOS needed")
        ReconPlan.Stage.entries.forEach { stage -> requireTool(stage) }
        val free = try { StatFs(workRoot.apply { mkdirs() }.path).availableBytes } catch (e: Exception) { 0L }
        if (free < ReconPlan.storageNeededBytes(photos)) {
            throw Stop(Error.LOW_STORAGE, "${free / MB} MB free, ${ReconPlan.storageNeededBytes(photos) / MB} MB needed")
        }
        val frames = File(captureDir, CaptureStore.FRAMES).takeIf { it.isFile }
        val manifest = File(captureDir, CaptureStore.MANIFEST).takeIf { it.isFile }

        // 1. Import
        stage(Stage.IMPORT, onProgress)
        exec("makescene", ReconPlan.Args.import(images.path, scene.path), Stage.IMPORT, threads = 1)

        // 2. Cameras (SfM), setting from the memory free now
        val settings = ReconPlan.choose(photos, memAvailableMb(), cpuCores)
        status = status.copy(sfmSetting = settings.reason)
        stage(Stage.CAMERAS, onProgress)
        var sfmOk = exec("sfmrecon", ReconPlan.Args.sfm(scene.path, settings.sfm), Stage.CAMERAS, settings.threads,
            guardMb = if (settings.sfm == ReconPlan.SfmSetting.HIGH) ReconPlan.GUARD_MB else null)
        if (!sfmOk) {
            // Only reachable when the guard stopped the high setting: retry at standard, and record it.
            File(scene, "prebundle.sfm").delete()
            status = status.copy(sfmSetting = settings.reason + "; high stopped by the memory guard, retried at standard")
            publish(status, onProgress)
            sfmOk = exec("sfmrecon", ReconPlan.Args.sfm(scene.path, ReconPlan.SfmSetting.STANDARD), Stage.CAMERAS, settings.threads)
        }
        val views = File(scene, "views").listFiles { f -> f.isDirectory }?.size ?: 0
        var placed = registeredViews()
        // MVE searches for the initial photo pair in parallel and takes whichever thread finds one first, so the pair
        // varies between runs, and a poor pair collapses the solution. On the A17 bottle, 3 of 6 four-thread runs
        // placed 3-15 of 58 photos; single-threaded, the search is deterministic (first-ranked pair) and placed 47
        // every time. The matching is saved in prebundle.sfm, so re-solving skips features and matching (2-40 s).
        var attempt = 1
        var singleThreaded = -1
        if (placed < ReconPlan.goodPlacement(views)) {
            Log.w(TAG, "SfM placed only $placed of $views photos; re-solving single-threaded")
            attempt++
            exec("sfmrecon", ReconPlan.Args.sfm(scene.path, settings.sfm), Stage.CAMERAS, threads = 1)
            placed = registeredViews()
            singleThreaded = placed
        }
        while (placed < ReconPlan.goodPlacement(views) && attempt < ReconPlan.SFM_ATTEMPTS) {
            Log.w(TAG, "SfM placed only $placed of $views photos; re-solving (attempt ${attempt + 1})")
            attempt++
            exec("sfmrecon", ReconPlan.Args.sfm(scene.path, settings.sfm), Stage.CAMERAS, settings.threads)
            placed = registeredViews()
        }
        if (placed < singleThreaded) {
            // The random retries did worse than the deterministic one: reproduce it.
            exec("sfmrecon", ReconPlan.Args.sfm(scene.path, settings.sfm), Stage.CAMERAS, threads = 1)
            placed = registeredViews()
        }
        Log.i(TAG, "SfM placed $placed of $views photos after $attempt attempt(s) (${settings.reason})")
        status = status.copy(photos = views, placed = placed)
        if (placed < ReconPlan.minPlacement(views)) {
            throw Stop(Error.TOO_FEW_PHOTOS_PLACED, "$placed of $views photos placed after $attempt attempts")
        }

        // 3. Object box (aligns the scene upright)
        stage(Stage.BOX, onProgress)
        val boxArgs = ReconPlan.Args.box(scene.path, frames?.path, manifest?.path)
        val boxExit = execExitCode("objbox", boxArgs, Stage.BOX, 1)
        when (boxExit) {
            0 -> {}
            2 -> throw Stop(Error.NO_OBJECT, "$placed of $views photos placed; the cameras do not converge on one object")
            3 -> throw Stop(Error.TAP_OUTSIDE, "the tapped point is outside the object the photos centre on")
            else -> throw Stop(Error.TOOL_FAILED, "objbox exit $boxExit")
        }
        val report = File(scene, "objbox_report.txt").readText()
        status = status.copy(boxMethod = report.lineSequence().firstOrNull { it.startsWith("method ") }?.removePrefix("method "))
        File(scene, "objbox_report.txt").copyTo(File(logs, "objbox_report.txt"), overwrite = true)
        File(scene, "objbox.txt").copyTo(File(scene, AUTO_BOX), overwrite = true)   // for Reset on the review screen
        if (review) {
            // Only a box objbox v2 wrote (upright, centred) can be reviewed; a legacy box continues without review.
            if (BoxReview.ObjectBox.parse(File(scene, "objbox.txt").readText()) != null) {
                return status.copy(state = State.REVIEW, stage = Stage.BOX)
            }
            Log.w(TAG, "legacy box: review skipped")
        }
        return dense(onProgress)
    }

    /** Depth maps to the textured model, with the box in scene/objbox.txt. */
    private fun dense(onProgress: (ReconStatus) -> Unit): ReconStatus {
        val box = File(scene, "objbox.txt").readText().trim()
        if (box.split(',').mapNotNull { it.trim().toDoubleOrNull() }.size != 6) throw Stop(Error.TOOL_FAILED, "invalid box: $box")
        val report = File(scene, "objbox_report.txt").readText()
        val views = File(scene, "views").listFiles { f -> f.isDirectory }?.size ?: 0
        val settings = ReconPlan.choose(views, memAvailableMb(), cpuCores)

        // 4. Depth maps and the dense cloud (OpenMVS), inside the object box. The scene goes to OpenMVS as a COLMAP
        // model in the working folder; OpenMVS runs there, because it resolves image paths against it.
        stage(Stage.DEPTH, onProgress)
        val model = ColmapExport.write(scene, workDir)
        Log.i(TAG, "OpenMVS input: ${model.pointCount} SfM points")
        File(workDir, "roi.txt").writeText(ReconPlan.roiFile(box))
        exec("omvs_import", ReconPlan.Args.omvsImport(settings.threads), Stage.DEPTH, settings.threads)
        exec("omvs_densify", ReconPlan.Args.omvsDensify(settings.threads), Stage.DEPTH, settings.threads)

        // 5. The dense cloud must hold enough points to build a surface from.
        stage(Stage.POINTS, onProgress)
        val dense = File(workDir, "scene_dense.ply")
        val points = ReconPlan.plyVertexCount(plyHeader(dense))
        if (points == null || points < ReconPlan.MIN_DENSE_POINTS) {
            throw Stop(Error.TOOL_FAILED, "dense cloud has ${points ?: 0} points")
        }

        // 6–9. Surface (OpenMVS), ground cut, clean-up, texture
        val surf = File(workDir, "scene_mesh.ply")
        val ground = File(workDir, "nosurface.ply"); val clean = File(workDir, "clean.ply")
        stage(Stage.SURFACE, onProgress)
        exec("omvs_mesh", ReconPlan.Args.omvsMesh(settings.threads), Stage.SURFACE, settings.threads)
        if (!surf.isFile) throw Stop(Error.TOOL_FAILED, "OpenMVS wrote no surface")
        var mesh = surf
        val dist = ReconPlan.medianDistance(report)
        if (ReconPlan.onSurface(report) && dist != null) {
            stage(Stage.GROUND, onProgress)
            exec("groundcut", ReconPlan.Args.ground(ReconPlan.groundCutHeight(dist), surf.path, ground.path), Stage.GROUND, 1)
            mesh = ground
        }
        stage(Stage.CLEAN, onProgress)
        val meshVertices = ReconPlan.plyVertexCount(plyHeader(mesh)) ?: throw Stop(Error.TOOL_FAILED, "unreadable surface ${mesh.name}")
        exec("meshclean", ReconPlan.Args.clean(mesh.path, clean.path, ReconPlan.minComponentVertices(meshVertices)), Stage.CLEAN, settings.threads)
        stage(Stage.TEXTURE, onProgress)
        val modelDir = File(captureDir, MODEL_DIR)
        val tmpModel = File(captureDir, "$MODEL_DIR.tmp")
        tmpModel.deleteRecursively(); tmpModel.mkdirs()
        exec("texrecon", ReconPlan.Args.texture(scene.path, clean.path, File(tmpModel, "model").path), Stage.TEXTURE, settings.threads)
        if (!File(tmpModel, "model.obj").isFile) throw Stop(Error.TOOL_FAILED, "texrecon wrote no model")
        // texrecon's intermediate files (data costs, labeling, settings): not part of the model, about 8 MB.
        tmpModel.listFiles { f -> f.name.endsWith(".spt") || f.name.endsWith(".vec") || f.name.endsWith(".conf") }?.forEach { it.delete() }
        // The user's real-world size, if they gave one: scale the model to millimetres. A failure here leaves the
        // model unsized (the card then offers "Add size"); it doesn't fail the build.
        try {
            SizeStore.apply(captureDir, File(tmpModel, "model.obj"))?.let { Log.i(TAG, "sized: ${it.dimensionsMm}") }
        } catch (e: Exception) {
            Log.e(TAG, "sizing", e)
        }
        // The still picture for the Model ready card. Without it the card shows a plain icon; the model is unaffected.
        try {
            ModelPreview.write(tmpModel)
        } catch (e: Throwable) {
            Log.e(TAG, "preview", e)
        }
        // Replace any earlier model only once the new one is complete.
        modelDir.deleteRecursively()
        if (!tmpModel.renameTo(modelDir)) throw Stop(Error.TOOL_FAILED, "could not move the model into the capture")
        val vertices = File(modelDir, "model.obj").useLines { l -> l.count { it.startsWith("v ") } }
        return status.copy(state = State.DONE, stage = null, model = "$MODEL_DIR/model.obj", vertices = vertices)
    }

    // ---- helpers ----

    /** The first 512 bytes of a PLY file as text (its header), or "" if missing. */
    private fun plyHeader(f: File): String =
        if (!f.isFile) "" else f.inputStream().use { s -> ByteArray(512).let { b -> String(b, 0, maxOf(0, s.read(b)), Charsets.US_ASCII) } }

    private fun stage(s: Stage, onProgress: (ReconStatus) -> Unit) {
        if (cancelled) throw Stop(null, null)
        status = status.copy(stage = s)
        publish(status, onProgress)
    }

    private fun publish(s: ReconStatus, onProgress: (ReconStatus) -> Unit) {
        status = s.copy(updatedAt = System.currentTimeMillis())
        try {
            workDir.mkdirs()
            val tmp = File(workDir, "$STATUS.tmp")
            FileOutputStream(tmp).use { it.write(status.encode().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(tmp.toPath(), statusFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Log.e(TAG, "status", e)
        }
        onProgress(status)
    }

    private fun requireTool(stage: Stage) {
        val tool = when (stage) {
            Stage.IMPORT -> "makescene"; Stage.CAMERAS -> "sfmrecon"; Stage.BOX -> "objbox"; Stage.DEPTH -> "omvs_densify"
            Stage.POINTS -> "omvs_import"; Stage.SURFACE -> "omvs_mesh"; Stage.GROUND -> "groundcut"
            Stage.CLEAN -> "meshclean"; Stage.TEXTURE -> "texrecon"
        }
        val f = File(nativeLibDir, ReconPlan.library(tool))
        if (!f.isFile || !f.canExecute()) throw Stop(Error.UNSUPPORTED_DEVICE, "${f.name} not available for this phone's processor")
    }

    /** Runs a tool; true on exit 0, false only when the memory guard ([guardMb]) stopped it. Throws otherwise. */
    private fun exec(tool: String, args: List<String>, stage: Stage, threads: Int, guardMb: Long? = null): Boolean {
        val code = execExitCode(tool, args, stage, threads, guardMb)
        return when {
            code == 0 -> true
            code == GUARD_STOPPED && guardMb != null -> false
            else -> throw Stop(Error.TOOL_FAILED, "$tool exit $code")
        }
    }

    private fun execExitCode(tool: String, args: List<String>, stage: Stage, threads: Int, guardMb: Long? = null): Int {
        if (cancelled) throw Stop(null, null)
        val cmd = listOf(File(nativeLibDir, ReconPlan.library(tool)).path) + args
        val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(logs, "${stage.id}.log")))
        pb.environment()["OMP_NUM_THREADS"] = threads.toString()
        val p = pb.start()
        current = p
        try {
            while (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                if (cancelled) { p.destroyForcibly(); throw Stop(null, null) }
                val free = memAvailableMb()
                if (free in 0 until ReconPlan.HARD_GUARD_MB) {
                    p.destroyForcibly()
                    throw Stop(Error.LOW_MEMORY, "$tool stopped at $free MB free")
                }
                if (guardMb != null && free in 0 until guardMb) {
                    p.destroyForcibly(); p.waitFor()
                    Log.w(TAG, "memory guard stopped $tool at $free MB free")
                    return GUARD_STOPPED
                }
            }
        } finally {
            current = null
        }
        if (cancelled) throw Stop(null, null)
        return p.exitValue()
    }

    /** Number of views SfM placed (non-zero focal length in meta.ini). */
    private fun registeredViews(): Int =
        File(scene, "views").listFiles { f -> f.isDirectory }.orEmpty().count { v ->
            File(v, "meta.ini").takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.startsWith("focal_length") }?.substringAfter('=')?.trim()?.toDoubleOrNull()?.let { it > 0 } == true
        }

    /** MemAvailable from /proc/meminfo in MB, or -1 if unreadable. */
    private fun memAvailableMb(): Long = try {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024) ?: -1
        }
    } catch (e: Exception) { -1 }

    /** Stops the pipeline: [error] null means cancelled by the user. */
    private class Stop(val error: Error?, val detail: String?) : Exception(detail)

    companion object {
        private const val TAG = "ReconRunner"
        const val STATUS = "status.json"
        const val MODEL_DIR = "model"
        /** objbox's own box, kept so the review screen can reset to it. */
        const val AUTO_BOX = "objbox_auto.txt"
        const val MIN_PHOTOS = 10
        private const val GUARD_STOPPED = -9999
        private const val MB = 1024L * 1024

        /** The kept scene of a build paused for review (or still running). */
        fun sceneDir(workRoot: File, captureId: String) = File(File(workRoot, captureId), "scene")

        /**
         * Ends a build paused for review without building: marks it cancelled and deletes the kept scene. Called from
         * the app's process (nothing runs during a review).
         */
        fun abandonReview(workRoot: File, captureId: String) {
            val prev = readStatus(workRoot, captureId) ?: return
            if (prev.state != State.REVIEW) return
            val dir = File(workRoot, captureId)
            val s = prev.copy(state = State.CANCELLED, updatedAt = System.currentTimeMillis())
            val tmp = File(dir, "$STATUS.tmp")
            FileOutputStream(tmp).use { it.write(s.encode().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(tmp.toPath(), File(dir, STATUS).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            File(dir, "scene").deleteRecursively()
        }

        /** Reads a capture's last reconstruction status, or null if it never ran. */
        fun readStatus(workRoot: File, captureId: String): ReconStatus? = try {
            File(File(workRoot, captureId), STATUS).takeIf { it.isFile }?.readText()?.let { ReconStatus.decode(it) }
        } catch (e: Exception) { null }
    }
}
