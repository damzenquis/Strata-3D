package app.strata3d.capture.recon

import app.strata3d.model.TexturedObj
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a built model (`model/` with model.obj, model.mtl and colour images) for other apps (approved mockups 2–3):
 * - **GLB:** one file, colours inside; glTF is +y up and in metres, so a sized model (millimetres, +z up) is turned and
 *   scaled and opens at its real size; an unsized one keeps its numbers. Materials are unlit (the colours are photos).
 * - **OBJ (.zip):** the model, its materials and colour images as they are.
 * [glb] is pure (bytes in, bytes out) and unit-tested; the zip is plain java.io.
 */
object ModelExport {

    enum class Format(val extension: String, val mime: String) { GLB("glb", "model/gltf-binary"), OBJ_ZIP("zip", "application/zip") }

    class Cancelled : Exception()

    /** Files of a built model, in the order they are written. */
    fun files(modelDir: File): List<File> {
        val mtl = File(modelDir, "model.mtl")
        val textures = if (mtl.isFile) mtl.bufferedReader().use { TexturedObj.parseMtl(it) }.values.distinct() else emptyList()
        return listOf(File(modelDir, "model.obj"), mtl).filter { it.isFile } + textures.map { File(modelDir, it) }.filter { it.isFile }
    }

    /** Approximate output size, for the export sheet. */
    fun estimateBytes(modelDir: File, format: Format): Long {
        val all = files(modelDir)
        val images = all.filter { it.extension.equals("png", true) || it.extension.equals("jpg", true) }.sumOf { it.length() }
        val obj = File(modelDir, "model.obj").length()
        return when (format) {
            // Measured on a 29k-triangle model: GLB geometry (20 bytes per corner) is about 2/3 of the OBJ text; the
            // zip deflates the OBJ to about 28 %. Images are stored as they are.
            Format.GLB -> images + obj * 2 / 3
            Format.OBJ_ZIP -> images + obj * 7 / 25 + File(modelDir, "model.mtl").length()
        }
    }

    /** Writes [format] for the model in [modelDir] to [out]. [progress] gets 0–100; returning false cancels. */
    fun write(modelDir: File, format: Format, out: OutputStream, progress: (Int) -> Boolean) {
        when (format) {
            Format.GLB -> {
                val textures = File(modelDir, "model.mtl").takeIf { it.isFile }?.bufferedReader()?.use { TexturedObj.parseMtl(it) }.orEmpty()
                if (!progress(5)) throw Cancelled()
                val model = File(modelDir, "model.obj").bufferedReader().use { TexturedObj.parse(it, textures) }
                if (!progress(40)) throw Cancelled()
                val images = model.parts.mapNotNull { it.texture }.distinct().associateWith { File(modelDir, it).readBytes() }
                if (!progress(70)) throw Cancelled()
                out.write(glb(model, images))
                progress(100)
            }
            Format.OBJ_ZIP -> {
                val files = files(modelDir)
                val total = files.sumOf { it.length() }.coerceAtLeast(1)
                var done = 0L
                ZipOutputStream(out).use { zip ->
                    for (f in files) {
                        zip.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                zip.write(buf, 0, n); done += n
                                if (!progress((done * 100 / total).toInt())) throw Cancelled()
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * A GLB file: one mesh with a primitive per material, each with its PNG colour image embedded. Positions are
     * converted from the app's frame (+z up) to glTF's (+y up): (x, y, z) → (x, z, −y), and from millimetres to metres
     * when the model is sized. Texture v is flipped (glTF's origin is the image's top-left).
     */
    fun glb(model: TexturedObj, images: Map<String, ByteArray>): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = StringBuilder(); val accessors = StringBuilder(); val prims = StringBuilder()
        val imageList = images.keys.toList()
        var viewCount = 0; var accCount = 0
        fun pad(to: Int = 4) { while (bin.size() % to != 0) bin.write(0) }
        fun view(bytes: ByteArray, target: Int?): Int {
            pad(); val offset = bin.size(); bin.write(bytes)
            if (viewCount > 0) views.append(',')
            views.append("{\"buffer\":0,\"byteOffset\":$offset,\"byteLength\":${bytes.size}${target?.let { ",\"target\":$it" } ?: ""}}")
            return viewCount++
        }
        val scale = if (model.millimetres) 0.001f else 1f
        for ((pi, part) in model.parts.withIndex()) {
            val n = part.positions.size / 3
            if (n == 0) continue
            val pos = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN)
            val min = FloatArray(3) { Float.MAX_VALUE }; val max = FloatArray(3) { -Float.MAX_VALUE }
            for (i in 0 until n) {
                val q = floatArrayOf(part.positions[3 * i] * scale, part.positions[3 * i + 2] * scale, -part.positions[3 * i + 1] * scale)
                for (k in 0..2) { pos.putFloat(q[k]); min[k] = minOf(min[k], q[k]); max[k] = maxOf(max[k], q[k]) }
            }
            val uv = ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) { uv.putFloat(part.uvs[2 * i]); uv.putFloat(1f - part.uvs[2 * i + 1]) }
            val pv = view(pos.array(), 34962); val tv = view(uv.array(), 34962)
            if (accCount > 0) accessors.append(',')
            accessors.append(String.format(Locale.ROOT,
                "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\",\"min\":[%s,%s,%s],\"max\":[%s,%s,%s]}," +
                    "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC2\"}",
                pv, n, min[0], min[1], min[2], max[0], max[1], max[2], tv, n))
            val pa = accCount; accCount += 2
            val texture = part.texture?.let { imageList.indexOf(it) }?.takeIf { it >= 0 }
            if (pi > 0 && prims.isNotEmpty()) prims.append(',')
            prims.append("{\"attributes\":{\"POSITION\":$pa,\"TEXCOORD_0\":${pa + 1}},\"material\":${texture ?: imageList.size}}")
        }
        val imageViews = imageList.map { view(images.getValue(it), null) }
        pad()
        val materials = imageList.indices.joinToString(",") { i ->
            "{\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":$i},\"metallicFactor\":0,\"roughnessFactor\":1},\"extensions\":{\"KHR_materials_unlit\":{}}}"
        }.let { if (it.isEmpty()) it else "$it," } + "{\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.8,0.8,0.8,1],\"metallicFactor\":0,\"roughnessFactor\":1}}"
        val json = buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Strata:3D\"},\"extensionsUsed\":[\"KHR_materials_unlit\"],")
            append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],")
            append("\"meshes\":[{\"primitives\":[$prims]}],\"materials\":[$materials],")
            if (imageList.isNotEmpty()) {
                append("\"textures\":[${imageList.indices.joinToString(",") { "{\"sampler\":0,\"source\":$it}" }}],")
                append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987}],")
                append("\"images\":[${imageViews.joinToString(",") { "{\"bufferView\":$it,\"mimeType\":\"image/png\"}" }}],")
            }
            append("\"accessors\":[$accessors],\"bufferViews\":[$views],\"buffers\":[{\"byteLength\":${bin.size()}}]}")
        }.toByteArray(Charsets.UTF_8).let { j -> j + ByteArray((4 - j.size % 4) % 4) { 0x20 } }
        val body = bin.toByteArray()
        val out = ByteBuffer.allocate(12 + 8 + json.size + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity())
        out.putInt(json.size).putInt(0x4E4F534A).put(json)
        out.putInt(body.size).putInt(0x004E4942).put(body)
        return out.array()
    }
}
