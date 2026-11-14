package app.strata3d.capture.recon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.strata3d.model.TexturedObj
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The still picture of a built model on the Model ready card: the textured model, upright (+z up), seen from the
 * front-left and slightly above, on a transparent background. A small software renderer (orthographic, z-buffer,
 * texture lookup, gentle shading), so it runs in the reconstruction process without a GPU. [render] is pure.
 */
object ModelPreview {

    const val FILE = "preview.png"
    const val WIDTH = 720
    const val HEIGHT = 432

    /** A colour image as ARGB pixels. */
    class Texture(val w: Int, val h: Int, val argb: IntArray)

    /**
     * Renders [model] into a WIDTH × HEIGHT ARGB image. [textures] maps a part's texture file name to its pixels; a
     * part without one is drawn light grey. Azimuth and elevation in degrees.
     */
    fun render(model: TexturedObj, textures: Map<String, Texture>, w: Int = WIDTH, h: Int = HEIGHT, azimuth: Double = -35.0, elevation: Double = 18.0): IntArray {
        val out = IntArray(w * h)
        val depth = FloatArray(w * h) { Float.NEGATIVE_INFINITY }
        val az = Math.toRadians(azimuth); val el = Math.toRadians(elevation)
        // Camera axes (world +z up): right, up, and toward the viewer.
        val rx = cos(az); val ry = sin(az)
        val fx = -sin(az) * cos(el); val fy = cos(az) * cos(el); val fz = -sin(el)   // view direction
        val ux = ry * fz; val uy = -rx * fz; val uz = rx * fy - ry * fx            // up = right × forward
        // Fit: project all corners, take the 1st/99th percentile extents so stray bits don't shrink the model.
        val all = model.parts.flatMap { p -> (0 until p.positions.size / 3).map { i -> Triple(p.positions[3 * i], p.positions[3 * i + 1], p.positions[3 * i + 2]) } }
        if (all.isEmpty()) return out
        val sx = all.map { (x, y, _) -> x * rx + y * ry }.sorted()
        val sy = all.map { (x, y, z) -> x * ux + y * uy + z * uz }.sorted()
        fun pct(l: List<Double>, q: Double) = l[((l.size - 1) * q).toInt()]
        val x0 = pct(sx, 0.01); val x1 = pct(sx, 0.99); val y0 = pct(sy, 0.01); val y1 = pct(sy, 0.99)
        val scale = 0.88 * min(w / max(x1 - x0, 1e-9), h / max(y1 - y0, 1e-9))
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val lightX = -0.3 * rx + 0.5 * ux - fx; val lightY = -0.3 * ry + 0.5 * uy - fy; val lightZ = 0.5 * uz - fz
        val ll = sqrt(lightX * lightX + lightY * lightY + lightZ * lightZ)

        val px = DoubleArray(3); val py = DoubleArray(3); val pz = DoubleArray(3)
        for (part in model.parts) {
            val tex = part.texture?.let { textures[it] }
            val p = part.positions; val t = part.uvs
            for (tri in 0 until part.triangleCount) {
                for (k in 0..2) {
                    val o = 9 * tri + 3 * k
                    val x = p[o].toDouble(); val y = p[o + 1].toDouble(); val z = p[o + 2].toDouble()
                    px[k] = w / 2.0 + scale * (x * rx + y * ry - cx)
                    py[k] = h / 2.0 - scale * (x * ux + y * uy + z * uz - cy)
                    pz[k] = -(x * fx + y * fy + z * fz)                 // larger = nearer the viewer
                }
                val area = (px[1] - px[0]) * (py[2] - py[0]) - (px[2] - px[0]) * (py[1] - py[0])
                if (abs(area) < 1e-12) continue
                // Gentle shading from the face normal (both sides lit: open surfaces show their inside too).
                val o = 9 * tri
                val ax = p[o + 3] - p[o]; val ay = p[o + 4] - p[o + 1]; val azz = p[o + 5] - p[o + 2]
                val bx = p[o + 6] - p[o]; val by = p[o + 7] - p[o + 1]; val bz = p[o + 8] - p[o + 2]
                val nx = ay * bz - azz * by; val ny = azz * bx - ax * bz; val nz = ax * by - ay * bx
                val nl = sqrt((nx * nx + ny * ny + nz * nz).toDouble())
                val lambert = if (nl > 0) abs((nx * lightX + ny * lightY + nz * lightZ) / (nl * ll)) else 1.0
                val shade = 0.72 + 0.28 * lambert
                val minX = max(0, floor(minOf(px[0], px[1], px[2])).toInt()); val maxX = min(w - 1, floor(maxOf(px[0], px[1], px[2])).toInt())
                val minY = max(0, floor(minOf(py[0], py[1], py[2])).toInt()); val maxY = min(h - 1, floor(maxOf(py[0], py[1], py[2])).toInt())
                for (yy in minY..maxY) for (xx in minX..maxX) {
                    val qx = xx + 0.5; val qy = yy + 0.5
                    val w0 = ((px[1] - qx) * (py[2] - qy) - (px[2] - qx) * (py[1] - qy)) / area
                    val w1 = ((px[2] - qx) * (py[0] - qy) - (px[0] - qx) * (py[2] - qy)) / area
                    val w2 = 1 - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val d = (w0 * pz[0] + w1 * pz[1] + w2 * pz[2]).toFloat()
                    val i = yy * w + xx
                    if (d <= depth[i]) continue
                    depth[i] = d
                    var r = 200; var g = 200; var b = 200
                    if (tex != null) {
                        val u = w0 * t[6 * tri] + w1 * t[6 * tri + 2] + w2 * t[6 * tri + 4]
                        val v = w0 * t[6 * tri + 1] + w1 * t[6 * tri + 3] + w2 * t[6 * tri + 5]
                        val tx = floor(u * tex.w).toInt().coerceIn(0, tex.w - 1)          // nearest pixel
                        val ty = floor((1 - v) * tex.h).toInt().coerceIn(0, tex.h - 1)    // image rows run top-down
                        val c = tex.argb[ty * tex.w + tx]
                        r = c shr 16 and 0xFF; g = c shr 8 and 0xFF; b = c and 0xFF
                    }
                    out[i] = (0xFF shl 24) or ((r * shade).toInt().coerceAtMost(255) shl 16) or
                        ((g * shade).toInt().coerceAtMost(255) shl 8) or (b * shade).toInt().coerceAtMost(255)
                }
            }
        }
        return out
    }

    /** Renders `model.obj` in [modelDir] (with its MTL and colour images) to [FILE] there. */
    fun write(modelDir: File) {
        val textures = File(modelDir, "model.mtl").takeIf { it.isFile }?.bufferedReader()?.use { TexturedObj.parseMtl(it) }.orEmpty()
        val model = File(modelDir, "model.obj").bufferedReader().use { TexturedObj.parse(it, textures) }
        val images = textures.values.distinct().mapNotNull { name ->
            val bmp = BitmapFactory.decodeFile(File(modelDir, name).path) ?: return@mapNotNull null
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            name to Texture(bmp.width, bmp.height, px).also { bmp.recycle() }
        }.toMap()
        val argb = render(model, images)
        val out = Bitmap.createBitmap(argb, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val tmp = File(modelDir, "$FILE.tmp")
        FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        if (!tmp.renameTo(File(modelDir, FILE))) tmp.delete()
    }
}
