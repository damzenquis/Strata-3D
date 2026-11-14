package app.strata3d.capture.format

import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports a capture's frames to the text formats of common reconstruction tools (for checking captures). */
object CaptureExport {

    private fun num(d: Double) = String.format(Locale.ROOT, "%.9g", d)

    /**
     * COLMAP sparse-model text files (`cameras.txt`, `images.txt`, `points3D.txt`). Version 1 frames carry their
     * ARCore poses as world-to-camera transforms; frames without a pose (all of version 2) are left out of
     * `images.txt`, and COLMAP registers them itself. Every frame's intrinsics are listed; identical intrinsics share
     * one PINHOLE camera entry.
     */
    fun colmap(frames: List<FrameRecord>): Map<String, String> {
        val cameraIds = LinkedHashMap<Intrinsics, Int>()
        val images = StringBuilder()
        images.append("# Image list with two lines of data per image:\n")
        images.append("#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n")
        images.append("#   POINTS2D[] as (X, Y, POINT3D_ID)\n")
        images.append(
            if (frames.any { it.pose != null }) "# Poses from ARCore (metres), exported by Strata:3D.\n"
            else "# No poses: run feature extraction, matching and mapping in COLMAP with these cameras.\n",
        )
        var imageId = 1
        for (f in frames) {
            val camId = cameraIds.getOrPut(f.intrinsics) { cameraIds.size + 1 }
            val pose = f.pose ?: continue
            val (r, t) = PoseMath.worldToCameraOpenCv(pose)
            val q = PoseMath.matrixToQuat(r)
            images.append(imageId++).append(' ')
                .append(num(q[3])).append(' ').append(num(q[0])).append(' ').append(num(q[1])).append(' ').append(num(q[2])).append(' ')
                .append(num(t.x)).append(' ').append(num(t.y)).append(' ').append(num(t.z)).append(' ')
                .append(camId).append(' ').append(f.image.substringAfterLast('/')).append("\n\n")
        }
        val cameras = StringBuilder()
        cameras.append("# Camera list with one line of data per camera:\n")
        cameras.append("#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n")
        for ((k, id) in cameraIds) {
            cameras.append(id).append(" PINHOLE ").append(k.width).append(' ').append(k.height).append(' ')
                .append(num(k.fx)).append(' ').append(num(k.fy)).append(' ').append(num(k.cx)).append(' ').append(num(k.cy)).append('\n')
        }
        return mapOf(
            "cameras.txt" to cameras.toString(),
            "images.txt" to images.toString(),
            "points3D.txt" to "# 3D point list (empty: run point triangulation in COLMAP)\n",
        )
    }

    /** nerfstudio `transforms.json` (camera-to-world, OpenGL camera axes, per-frame intrinsics). */
    fun nerfstudio(frames: List<FrameRecord>): String = JsonOut().obj {
        field("camera_model", "OPENCV")
        key("frames").arr {
            for (f in frames) {
                val pose = f.pose ?: continue
                val m = PoseMath.cameraToWorldGl(pose)
                obj {
                    field("file_path", f.image)
                    field("fl_x", f.intrinsics.fx); field("fl_y", f.intrinsics.fy)
                    field("cx", f.intrinsics.cx); field("cy", f.intrinsics.cy)
                    field("w", f.intrinsics.width); field("h", f.intrinsics.height)
                    field("k1", 0.0); field("k2", 0.0); field("p1", 0.0); field("p2", 0.0)
                    key("transform_matrix").arr { for (row in 0 until 4) arr { for (c in 0 until 4) value(m[row * 4 + c]) } }
                }
            }
        }
    }.toString()

    const val README = """Strata:3D capture export
========================

manifest.json      capture metadata (format "strata3d-capture"; see "version")
frames.jsonl       one JSON object per photo: timestamp, intrinsics, and
                   version 2: phone orientation (gravity) and image rotation
                   version 1: ARCore pose
images/            full-resolution photos (JPEG as the camera stored it; see rotationDegrees / EXIF)
colmap/            COLMAP text model: cameras.txt with every photo's intrinsics; images.txt has poses
                   only for version 1 captures (version 2: let COLMAP register the photos)

Version 2 (CameraX): no poses. orientation = device-to-world quaternion [x,y,z,w] from the game
rotation vector; world +z up; device +x right, +y up, +z out of the screen; the camera looks along -z.
manifest.objectTap = where the user tapped the object (u, v in 0..1 across the upright image).

Version 1 (ARCore) also has:
depth/             ARCore raw depth: .d16 = uint16 millimetres little-endian, .c8 = 8-bit confidence
transforms.json    nerfstudio transforms (camera-to-world, OpenGL camera axes)
Poses: camera-to-world in ARCore's world frame (metres, +y up); camera +x right, +y up, looking down -z.

Intrinsics: see manifest.camera.intrinsicsSource (no lens distortion model).
"""

    /**
     * Writes the whole capture as a zip: the raw dataset plus COLMAP and nerfstudio files and a README.
     * JPEGs are stored without recompression. [onProgress] gets (bytes done, bytes total); [isCancelled] is
     * polled between files and aborts with [InterruptedException].
     */
    fun writeZip(
        store: CaptureStore,
        dir: File,
        out: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val frames = store.readFrames(dir)
        val files = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.path }.toList()
        val total = files.sumOf { it.length() }
        var done = 0L
        val buffer = ByteArray(256 * 1024)
        ZipOutputStream(out.buffered(256 * 1024)).use { zip ->
            fun text(name: String, content: String) {
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (f in files) {
                if (isCancelled()) throw InterruptedException("Export cancelled")
                val rel = f.relativeTo(dir).invariantSeparatorsPath
                zip.setLevel(if (rel.endsWith(".jpg")) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        done += n
                    }
                }
                zip.closeEntry()
                onProgress(done, total)
            }
            for ((name, content) in colmap(frames)) text("colmap/$name", content)
            if (frames.any { it.pose != null }) text("transforms.json", nerfstudio(frames))   // needs poses
            text("README.txt", README)
        }
    }
}
