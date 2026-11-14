package app.strata3d.model

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Unified entry point for parsing supported 3D model formats (STL, OBJ, PLY, 3MF and glTF/GLB), plus `.zip`
 * packages that contain one of them together with the files it references.
 */
object ModelParser {

    enum class Format { STL, OBJ, PLY, THREE_MF, GLTF }

    private const val SNIFF_BUFFER_SIZE = 1024

    /** File extensions the parsers understand, most preferred first when picking the model out of several files. */
    val MODEL_EXTENSIONS = listOf("gltf", "glb", "3mf", "stl", "obj", "ply")

    fun isModelFileName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MODEL_EXTENSIONS

    /**
     * Parses a 3D model stream into a [Mesh].
     *
     * @param input Stream containing model data.
     * @param fileName Optional file name to aid detection by extension.
     * @param knownLength Optional total stream size in bytes (-1 if unknown).
     * @param resources Optional access to companion files (e.g. a glTF's `.bin`), looked up by relative URI.
     */
    fun parse(
        input: InputStream,
        fileName: String = "",
        knownLength: Long = -1L,
        resources: ModelResources? = null,
    ): Mesh {
        val stream = input as? BufferedInputStream ?: BufferedInputStream(input, 1 shl 16)
        val lowerName = fileName.lowercase()
        if (lowerName.endsWith(".zip")) return ZipPackage.parse(stream)
        // A file named as something else (a document, an image, an .xml) is refused by its name, before any parser
        // can misread its bytes. Names without an extension are identified by content below.
        unsupportedExtension(fileName)?.let { byName ->
            // Name the kind of file when its content is recognisable (an image, a PDF...), else name the extension.
            stream.mark(SNIFF_BUFFER_SIZE)
            val head = ByteArray(SNIFF_BUFFER_SIZE)
            var total = 0
            while (total < SNIFF_BUFFER_SIZE) { val c = stream.read(head, total, SNIFF_BUFFER_SIZE - total); if (c < 0) break; total += c }
            stream.reset()
            val kind = notAModel(head, total)
            throw ModelParseException(if (kind != null) "This file is $kind, not a 3D model. Strata:3D opens $SUPPORTED." else byName)
        }

        val format = when {
            lowerName.endsWith(".gltf") || lowerName.endsWith(".glb") -> Format.GLTF
            lowerName.endsWith(".3mf") -> Format.THREE_MF
            lowerName.endsWith(".ply") -> Format.PLY
            lowerName.endsWith(".obj") -> Format.OBJ
            lowerName.endsWith(".stl") -> Format.STL
            else -> sniffFormat(stream, knownLength)
        }

        return when (format) {
            Format.OBJ -> ObjParser.parse(stream, knownLength)
            Format.STL -> StlParser.parse(stream, knownLength)
            Format.PLY -> PlyParser.parse(stream, knownLength)
            Format.THREE_MF -> ThreeMfParser.parse(stream, knownLength)
            Format.GLTF -> GltfParser.parse(stream, knownLength, resources)
        }
    }

    /** Supported formats, as listed in messages. */
    const val SUPPORTED = "STL, OBJ, PLY, 3MF, glTF, GLB, or a ZIP containing one"

    /** The message for a file name whose extension is not a model format, or null when it is one (or has none). */
    fun unsupportedExtension(fileName: String): String? {
        val name = fileName.substringAfterLast('/').trim()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (!name.contains('.') || ext.isEmpty() || ext == "zip" || ext in MODEL_EXTENSIONS) return null
        return ".$ext files aren't 3D models. Strata:3D opens $SUPPORTED."
    }

    internal fun sniffFormat(stream: BufferedInputStream, knownLength: Long = -1L): Format {
        stream.mark(SNIFF_BUFFER_SIZE)
        val buffer = ByteArray(SNIFF_BUFFER_SIZE)
        var total = 0
        while (total < SNIFF_BUFFER_SIZE) {
            val count = stream.read(buffer, total, SNIFF_BUFFER_SIZE - total)
            if (count < 0) break
            total += count
        }
        stream.reset()

        notAModel(buffer, total)?.let { kind ->
            throw ModelParseException("This file is $kind, not a 3D model")
        }
        if (startsWithMagic(buffer, total, "ply")) return Format.PLY
        if (startsWithMagic(buffer, total, "glTF")) return Format.GLTF
        if (isZipMagic(buffer, total)) return Format.THREE_MF
        if (looksLikeJson(buffer, total)) return Format.GLTF
        if (looksLikeObj(buffer, total)) return Format.OBJ
        if (startsWithMagic(buffer, total, "solid")) return Format.STL   // ASCII STL
        // STL is the one format without a signature, so it is only assumed when the data really looks like a
        // binary STL: not plain text, and (when the size is known) exactly the size its triangle count says.
        if (isText(buffer, total)) throw ModelParseException("This file is text, but not a 3D model. Strata:3D opens $SUPPORTED.")
        if (knownLength > 0 && total >= 84) {
            val declared = (buffer[80].toLong() and 0xFF) or ((buffer[81].toLong() and 0xFF) shl 8) or
                ((buffer[82].toLong() and 0xFF) shl 16) or ((buffer[83].toLong() and 0xFF) shl 24)
            if (knownLength != 84 + declared * 50) {
                throw ModelParseException("This file isn't a 3D model Strata:3D can read. It opens $SUPPORTED.")
            }
        }
        return Format.STL
    }

    /** True when the bytes are printable text (with tabs and line breaks). */
    internal fun isText(bytes: ByteArray, length: Int): Boolean = length > 0 && (0 until length).none { i ->
        val b = bytes[i].toInt() and 0xFF
        b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C
    }

    /**
     * Recognises common non-model files by their signature, so that an image or document is rejected with a
     * clear message instead of being misread as STL (the format with no signature of its own).
     */
    internal fun notAModel(b: ByteArray, n: Int): String? {
        fun at(offset: Int, vararg sig: Int) = n >= offset + sig.size && sig.indices.all { (b[offset + it].toInt() and 0xFF) == sig[it] }
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "a PNG image"
            at(0, 0xFF, 0xD8, 0xFF) -> "a JPEG image"
            at(0, 0x47, 0x49, 0x46, 0x38) -> "a GIF image"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "a WebP image"
            at(4, 0x66, 0x74, 0x79, 0x70) -> "a video or HEIC image"
            at(0, 0x25, 0x50, 0x44, 0x46) -> "a PDF document"
            at(0, 0x7F, 0x45, 0x4C, 0x46) -> "a program file"
            at(0, 0x1F, 0x8B) -> "a compressed (gzip) file"
            at(0, 0x52, 0x61, 0x72, 0x21) -> "a RAR archive"
            at(0, 0x37, 0x7A, 0xBC, 0xAF) -> "a 7-Zip archive"
            else -> null
        }
    }

    private fun isZipMagic(bytes: ByteArray, length: Int): Boolean =
        length >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun looksLikeJson(bytes: ByteArray, length: Int): Boolean {
        var start = 0
        while (start < length && bytes[start].toInt().toChar().isWhitespace()) start++
        return start < length && bytes[start] == '{'.code.toByte()
    }

    private fun startsWithMagic(bytes: ByteArray, length: Int, magic: String): Boolean {
        var start = 0
        while (start < length && bytes[start].toInt().toChar().isWhitespace()) start++
        if (length - start < magic.length) return false
        val lower = magic.lowercase()
        for (i in lower.indices) {
            if (bytes[start + i].toInt().toChar().lowercaseChar() != lower[i]) return false
        }
        return true
    }

    private fun looksLikeObj(bytes: ByteArray, length: Int): Boolean {
        if (length == 0) return false

        // Check if bytes are text
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x20 && b != '\t'.code && b != '\n'.code && b != '\r'.code && b != 0x0C) {
                return false
            }
        }

        // Check for ASCII STL keyword 'solid'
        var start = 0
        while (start < length && bytes[start].toInt().toChar().isWhitespace()) start++
        val text = String(bytes, start, (length - start).coerceAtMost(32), Charsets.US_ASCII).lowercase()
        if (text.startsWith("solid")) {
            return false
        }

        // Search for OBJ keywords at line starts
        var i = 0
        var hasObjDirective = false
        while (i < length) {
            while (i < length && (bytes[i] == ' '.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
            if (i >= length) break

            val remaining = length - i
            if (remaining >= 2) {
                val c0 = bytes[i].toInt().toChar().lowercaseChar()
                val c1 = bytes[i + 1].toInt().toChar()
                if ((c0 == 'v' || c0 == 'f') && c1.isWhitespace()) {
                    hasObjDirective = true
                    break
                }
                if (c0 == '#' || (c0 == 'o' && c1.isWhitespace()) || (c0 == 'g' && c1.isWhitespace())) {
                    hasObjDirective = true
                }
            }

            // Skip to next line
            while (i < length && bytes[i] != '\n'.code.toByte()) i++
            if (i < length && bytes[i] == '\n'.code.toByte()) i++
        }

        return hasObjDirective
    }
}
