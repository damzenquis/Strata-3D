package app.strata3d.model

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class ZipPackageException(message: String, cause: Throwable? = null) : ModelParseException(message, cause)

/**
 * Opens a `.zip` that contains a model plus the files it references, the usual way multi-file glTF assets
 * (`scene.gltf` + `scene.bin` + textures) are shared. The model is chosen by [ModelParser.MODEL_EXTENSIONS]
 * preference (glTF first); referenced files are resolved relative to the model's folder inside the archive,
 * falling back to a match by file name anywhere in it.
 */
internal object ZipPackage {

    /** Refuse archives that would not fit in memory on a phone anyway. */
    private const val MAX_TOTAL_BYTES = 1L shl 30

    fun parse(input: InputStream): Mesh {
        val entries = LinkedHashMap<String, ByteArray>() // normalised path -> bytes
        var total = 0L
        try {
            val zip = ZipInputStream(input)
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').removePrefix("/")
                if (!entry.isDirectory && !name.startsWith("__MACOSX/") && !name.substringAfterLast('/').startsWith("._")) {
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) throw ZipPackageException("This .zip is too large to open (over 1 GB unpacked)")
                    entries[name] = bytes
                }
                entry = zip.nextEntry
            }
        } catch (e: ModelParseException) {
            throw e
        } catch (e: Exception) {
            throw ZipPackageException("Could not read the .zip file: ${e.message}", e)
        }
        if (entries.isEmpty()) throw ZipPackageException("The .zip file is empty or not a valid archive")

        val modelPath = ModelParser.MODEL_EXTENSIONS.firstNotNullOfOrNull { ext ->
            entries.keys.filter { it.lowercase().endsWith(".$ext") }.minByOrNull { it.count { c -> c == '/' } }
        } ?: throw ZipPackageException("The .zip does not contain a supported 3D model (STL, OBJ, PLY, 3MF, glTF or GLB)")

        val folder = modelPath.substringBeforeLast('/', "")
        val byLowerPath = entries.mapKeys { it.key.lowercase() }
        val resources = ModelResources { uri ->
            val relative = ModelResources.normalize(uri)
            val joined = resolve(folder, relative).lowercase()
            val bytes = byLowerPath[joined]
                ?: ModelResources.fileName(uri).lowercase().let { file ->
                    byLowerPath.entries.firstOrNull { it.key.substringAfterLast('/') == file }?.value
                }
            bytes?.let { ByteArrayInputStream(it) }
        }

        val modelBytes = entries.getValue(modelPath)
        return ModelParser.parse(ByteArrayInputStream(modelBytes), modelPath.substringAfterLast('/'), modelBytes.size.toLong(), resources)
    }

    /** Joins [relative] onto [folder], resolving `.` and `..` segments. */
    private fun resolve(folder: String, relative: String): String {
        val parts = ArrayList<String>()
        (if (folder.isEmpty()) relative else "$folder/$relative").split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
