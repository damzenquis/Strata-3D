package app.strata3d.capture.recon

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.sqrt

/**
 * The MVE scene's SfM result as a COLMAP text model (cameras.txt, images.txt, points3D.txt), the input of OpenMVS's
 * InterfaceCOLMAP. The text is built by pure, unit-tested functions; [write] does the file work.
 *
 * - **Cameras:** one PINHOLE camera per placed view, for its `undistorted.png`. MVE removes the lens distortion there
 *   and keeps the focal length. MVE and COLMAP share the camera convention: x_cam = R·X + t, +z forward, y down.
 * - **Images:** named `<view folder>.png`. `images/<name>` is a link to the view's `undistorted.png`.
 * - **Points:** the SfM points from `synth_0.out` with their tracks. MVE's bundle file has no 2D positions, so each
 *   observation is the point projected into that view; the points are bundle-adjusted, so this is close. Observations
 *   behind a camera or outside its image are dropped, and so are points left with fewer than two.
 */
object ColmapExport {

    /** A placed view: [r] row-major world-to-camera rotation, [t] translation, intrinsics in pixels. */
    data class View(
        val id: Int, val name: String, val r: DoubleArray, val t: DoubleArray,
        val w: Int, val h: Int, val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    )

    data class Model(val cameras: String, val images: String, val points: String, val pointCount: Int)

    /** A view from MVE's meta.ini, or null when it has no camera (the photo wasn't placed). */
    fun parseView(metaIni: String, name: String, w: Int, h: Int): View? {
        fun numbers(key: String) = Regex("(?m)^\\s*$key\\s*=\\s*(.+)$").find(metaIni)?.groupValues?.get(1)
            ?.trim()?.split(Regex("\\s+"))?.map { it.toDouble() }
        val focal = numbers("focal_length")?.firstOrNull() ?: return null
        val id = numbers("id")?.firstOrNull()?.toInt() ?: return null
        val r = numbers("rotation")?.takeIf { it.size == 9 } ?: return null
        val t = numbers("translation")?.takeIf { it.size == 3 } ?: return null
        val pp = numbers("principal_point")?.takeIf { it.size == 2 } ?: listOf(0.5, 0.5)
        val aspect = numbers("pixel_aspect")?.firstOrNull() ?: 1.0
        val f = focal * maxOf(w, h)
        return View(id, name, r.toDoubleArray(), t.toDoubleArray(), w, h, f, f * aspect, pp[0] * w, pp[1] * h)
    }

    /** Builds the three COLMAP files from the placed [views] and MVE's bundle text ([synth], synth_0.out). */
    fun build(views: List<View>, synth: String): Model {
        val byId = views.associateBy { it.id }
        val obs = views.associate { it.id to StringBuilder() }
        val obsCount = HashMap<Int, Int>()
        val lines = synth.lines()
        val (ncam, npts) = lines[1].trim().split(Regex("\\s+")).map { it.toInt() }
        var i = 2 + 5 * ncam
        val points = StringBuilder()
        var pid = 0
        repeat(npts) {
            val x = lines[i].trim().split(Regex("\\s+")).map { it.toDouble() }
            val rgb = lines[i + 1].trim().split(Regex("\\s+"))
            val tr = lines[i + 2].trim().split(Regex("\\s+")).map { it.toInt() }
            i += 3
            val track = ArrayList<Triple<View, Double, Double>>()
            for (k in 0 until tr[0]) {
                val v = byId[tr[1 + 3 * k]] ?: continue
                val p = project(v, x) ?: continue
                track += Triple(v, p.first, p.second)
            }
            if (track.size < 2) return@repeat
            pid++
            val refs = track.joinToString(" ") { (v, u, w) ->
                val idx = obsCount.getOrDefault(v.id, 0)
                obsCount[v.id] = idx + 1
                obs.getValue(v.id).append(String.format(Locale.ROOT, "%s%.2f %.2f %d", if (idx > 0) " " else "", u, w, pid))
                "${v.id + 1} $idx"
            }
            points.append(String.format(Locale.ROOT, "%d %.6f %.6f %.6f %s %s %s 0 %s\n", pid, x[0], x[1], x[2], rgb[0], rgb[1], rgb[2], refs))
        }
        val cameras = StringBuilder(); val images = StringBuilder()
        for (v in views.sortedBy { it.id }) {
            cameras.append(String.format(Locale.ROOT, "%d PINHOLE %d %d %.6f %.6f %.6f %.6f\n", v.id + 1, v.w, v.h, v.fx, v.fy, v.cx, v.cy))
            val q = quaternion(v.r)
            images.append(String.format(Locale.ROOT, "%d %.10f %.10f %.10f %.10f %.10f %.10f %.10f %d %s\n",
                v.id + 1, q[0], q[1], q[2], q[3], v.t[0], v.t[1], v.t[2], v.id + 1, v.name))
            images.append(obs.getValue(v.id)).append('\n')
        }
        return Model(cameras.toString(), images.toString(), points.toString(), pid)
    }

    /** Pixel position of world point [x] in [v], or null when behind the camera or outside the image. */
    fun project(v: View, x: List<Double>): Pair<Double, Double>? {
        val r = v.r
        val cx = r[0] * x[0] + r[1] * x[1] + r[2] * x[2] + v.t[0]
        val cy = r[3] * x[0] + r[4] * x[1] + r[5] * x[2] + v.t[1]
        val cz = r[6] * x[0] + r[7] * x[1] + r[8] * x[2] + v.t[2]
        if (cz <= 0) return null
        val u = v.fx * cx / cz + v.cx; val w = v.fy * cy / cz + v.cy
        return if (u >= 0 && u < v.w && w >= 0 && w < v.h) u to w else null
    }

    /** Unit quaternion (w, x, y, z), w ≥ 0, of a row-major rotation matrix. */
    fun quaternion(m: DoubleArray): DoubleArray {
        fun at(i: Int, j: Int) = m[3 * i + j]
        val tr = at(0, 0) + at(1, 1) + at(2, 2)
        val q = if (tr > 0) {
            val s = 2 * sqrt(tr + 1)
            doubleArrayOf(0.25 * s, (at(2, 1) - at(1, 2)) / s, (at(0, 2) - at(2, 0)) / s, (at(1, 0) - at(0, 1)) / s)
        } else {
            val i = (0..2).maxBy { at(it, it) }; val j = (i + 1) % 3; val k = (i + 2) % 3
            val s = 2 * sqrt(1 + at(i, i) - at(j, j) - at(k, k))
            DoubleArray(4).also {
                it[0] = (at(k, j) - at(j, k)) / s; it[1 + i] = 0.25 * s
                it[1 + j] = (at(j, i) + at(i, j)) / s; it[1 + k] = (at(k, i) + at(i, k)) / s
            }
        }
        return if (q[0] < 0) DoubleArray(4) { -q[it] } else q
    }

    /** Width and height from a PNG file's header, or null if it isn't a PNG. */
    fun pngSize(f: File): Pair<Int, Int>? = RandomAccessFile(f, "r").use { raf ->
        val b = ByteArray(24)
        if (raf.read(b) != 24 || b[1] != 'P'.code.toByte() || b[2] != 'N'.code.toByte()) return null
        fun be(o: Int) = (b[o].toInt() and 0xff shl 24) or (b[o + 1].toInt() and 0xff shl 16) or (b[o + 2].toInt() and 0xff shl 8) or (b[o + 3].toInt() and 0xff)
        be(16) to be(20)
    }

    /**
     * Writes `<out>/sparse/{cameras,images,points3D}.txt` and `<out>/images/<view>.png` links for the MVE [scene]
     * (views with a camera and an undistorted image). Returns the model (for its counts).
     */
    fun write(scene: File, out: File): Model {
        val sparse = File(out, "sparse").apply { mkdirs() }
        val images = File(out, "images").apply { mkdirs() }
        val views = File(scene, "views").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { d ->
            val png = File(d, "undistorted.png").takeIf { it.isFile } ?: return@mapNotNull null
            val (w, h) = pngSize(png) ?: return@mapNotNull null
            val name = "${d.name}.png"
            val view = parseView(File(d, "meta.ini").readText(), name, w, h) ?: return@mapNotNull null
            val link = File(images, name).toPath()
            java.nio.file.Files.deleteIfExists(link)
            java.nio.file.Files.createSymbolicLink(link, png.toPath())
            view
        }
        val model = build(views, File(scene, "synth_0.out").readText())
        File(sparse, "cameras.txt").writeText(model.cameras)
        File(sparse, "images.txt").writeText(model.images)
        File(sparse, "points3D.txt").writeText(model.points)
        return model
    }
}
