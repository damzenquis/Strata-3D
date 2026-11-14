package app.strata3d.model

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

class PlyParseException(message: String, cause: Throwable? = null) : ModelParseException(message, cause)

/**
 * Parser for the Polygon File Format (`.ply`), also known as the Stanford Triangle Format.
 *
 * Supports the three encodings the format defines:
 * - `ascii`
 * - `binary_little_endian`
 * - `binary_big_endian`
 *
 * Reads the header's element/property table, extracts `x/y/z` vertex properties by name, and
 * triangulates triangular / quad / n-gon faces (fan from the first vertex). Colour, normal and
 * texture properties are read and discarded, matching the single-colour renderer used for STL/OBJ.
 */
object PlyParser {

    private enum class Format { ASCII, BINARY_LITTLE_ENDIAN, BINARY_BIG_ENDIAN }

    private enum class Scalar(val bytes: Int) {
        INT8(1), UINT8(1), INT16(2), UINT16(2), INT32(4), UINT32(4), FLOAT32(4), FLOAT64(8);

        companion object {
            fun parse(token: String): Scalar? = when (token.lowercase()) {
                "char", "int8" -> INT8
                "uchar", "uint8" -> UINT8
                "short", "int16" -> INT16
                "ushort", "uint16" -> UINT16
                "int", "int32" -> INT32
                "uint", "uint32" -> UINT32
                "float", "float32" -> FLOAT32
                "double", "float64" -> FLOAT64
                else -> null
            }
        }
    }

    private class Property(
        val name: String,
        val type: Scalar,
        val isList: Boolean = false,
        val countType: Scalar? = null,
    ) {
        val itemType: Scalar get() = type
    }

    private class Element(val name: String, val count: Int, val properties: List<Property>)

    private class Header {
        var format: Format? = null
        val elements = ArrayList<Element>()
    }

    fun parse(input: InputStream, knownLength: Long = -1L): Mesh {
        val stream = input as? BufferedInputStream ?: BufferedInputStream(input, 1 shl 16)
        val header = readHeader(stream)
        val format = header.format ?: throw PlyParseException("PLY header is missing a 'format' line")

        return when (format) {
            Format.ASCII -> parseAscii(stream, header)
            Format.BINARY_LITTLE_ENDIAN -> parseBinary(stream, header, littleEndian = true)
            Format.BINARY_BIG_ENDIAN -> parseBinary(stream, header, littleEndian = false)
        }
    }

    // --- Header parsing -------------------------------------------------------

    private fun readHeader(stream: InputStream): Header {
        var first = readLine(stream) ?: throw PlyParseException("File is empty")
        first = stripBom(first).trim()
        if (!first.equals("ply", ignoreCase = true)) {
            throw PlyParseException("Not a PLY file (missing 'ply' magic)")
        }

        val header = Header()
        var currentName: String? = null
        var currentCount = 0
        val currentProps = ArrayList<Property>()

        fun endElement() {
            if (currentName != null) {
                header.elements.add(Element(currentName!!, currentCount, ArrayList(currentProps)))
            }
            currentProps.clear()
        }

        while (true) {
            val line = readLine(stream)?.trim()
                ?: throw PlyParseException("Unexpected end of file in PLY header")
            if (line.isEmpty() || line.startsWith("comment", ignoreCase = true) ||
                line.startsWith("obj_info", ignoreCase = true)
            ) {
                continue
            }
            val parts = line.split(Regex("\\s+"))
            when (parts[0].lowercase()) {
                "format" -> {
                    if (parts.size < 2) throw PlyParseException("Malformed PLY 'format' line")
                    header.format = when (parts[1].lowercase()) {
                        "ascii" -> Format.ASCII
                        "binary_little_endian" -> Format.BINARY_LITTLE_ENDIAN
                        "binary_big_endian" -> Format.BINARY_BIG_ENDIAN
                        else -> throw PlyParseException("Unsupported PLY format '${parts[1]}'")
                    }
                }
                "element" -> {
                    endElement()
                    if (parts.size < 3) throw PlyParseException("Malformed PLY 'element' line")
                    currentName = parts[1]
                    currentCount = parts[2].toIntOrNull()
                        ?: throw PlyParseException("Invalid element count '${parts[2]}'")
                    if (currentCount < 0) throw PlyParseException("Negative element count in PLY header")
                }
                "property" -> {
                    if (currentName == null) throw PlyParseException("PLY property declared before any element")
                    if (parts.size >= 3 && parts[1].equals("list", ignoreCase = true)) {
                        if (parts.size < 5) throw PlyParseException("Malformed PLY list property")
                        val countType = Scalar.parse(parts[2])
                            ?: throw PlyParseException("Unknown PLY type '${parts[2]}'")
                        val itemType = Scalar.parse(parts[3])
                            ?: throw PlyParseException("Unknown PLY type '${parts[3]}'")
                        currentProps.add(Property(parts[4], itemType, isList = true, countType))
                    } else {
                        if (parts.size < 3) throw PlyParseException("Malformed PLY property")
                        val type = Scalar.parse(parts[1])
                            ?: throw PlyParseException("Unknown PLY type '${parts[1]}'")
                        currentProps.add(Property(parts[2], type))
                    }
                }
                "end_header" -> {
                    endElement()
                    return header
                }
                // Unknown header directives are ignored.
            }
        }
    }

    private fun stripBom(line: String): String =
        if (line.isNotEmpty() && line[0] == '\uFEFF') line.substring(1) else line

    private fun readLine(stream: InputStream): String? {
        val sb = StringBuilder(64)
        var b = stream.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = stream.read()
        }
        return sb.toString()
    }

    // --- Element layout helpers ----------------------------------------------

    private class Layout(val vertexCount: Int, val faceCount: Int) {
        var xIndex = -1
        var yIndex = -1
        var zIndex = -1
        var faceListIndex = -1
    }

    private fun resolveLayout(header: Header): Layout {
        var vertexCount = 0
        var faceCount = 0
        for (element in header.elements) {
            when (element.name.lowercase()) {
                "vertex" -> vertexCount += element.count
                "face" -> faceCount += element.count
            }
        }
        if (vertexCount == 0) throw PlyParseException("PLY file has no vertex element")
        if (faceCount == 0) throw PlyParseException("PLY file has no face element")

        val layout = Layout(vertexCount, faceCount)
        for (element in header.elements) {
            if (element.name.equals("vertex", ignoreCase = true)) {
                element.properties.forEachIndexed { i, p ->
                    when (p.name.lowercase()) {
                        "x" -> layout.xIndex = i
                        "y" -> layout.yIndex = i
                        "z" -> layout.zIndex = i
                    }
                }
            } else if (element.name.equals("face", ignoreCase = true)) {
                element.properties.forEachIndexed { i, p ->
                    if (p.isList && layout.faceListIndex < 0) layout.faceListIndex = i
                }
            }
        }
        if (layout.xIndex < 0 || layout.yIndex < 0 || layout.zIndex < 0) {
            throw PlyParseException("PLY vertex element is missing x, y or z properties")
        }
        if (layout.faceListIndex < 0) {
            throw PlyParseException("PLY face element has no vertex index list property")
        }
        return layout
    }

    // --- ASCII data -----------------------------------------------------------

    private fun parseAscii(stream: InputStream, header: Header): Mesh {
        val layout = resolveLayout(header)
        val tokenizer = TokenReader(stream)
        val vertexData = FloatArray(layout.vertexCount * 3)
        val builder = MeshBuilder(layout.faceCount.coerceIn(1, 1_000_000))
        val indices = IntList(16)
        val tri = FloatArray(9)
        var vertexOffset = 0

        for (element in header.elements) {
            when (element.name.lowercase()) {
                "vertex" -> {
                    for (v in 0 until element.count) {
                        for (pi in element.properties.indices) {
                            val token = tokenizer.next()
                                ?: throw PlyParseException("Unexpected end of file in PLY vertices")
                            if (pi == layout.xIndex || pi == layout.yIndex || pi == layout.zIndex) {
                                val value = token.toFloatOrNull()
                                    ?: throw PlyParseException("Invalid number '$token' in PLY vertex")
                                val dst = (vertexOffset + v) * 3
                                when (pi) {
                                    layout.xIndex -> vertexData[dst] = value
                                    layout.yIndex -> vertexData[dst + 1] = value
                                    layout.zIndex -> vertexData[dst + 2] = value
                                }
                            }
                        }
                    }
                    vertexOffset += element.count
                }
                "face" -> {
                    for (f in 0 until element.count) {
                        indices.clear()
                        for (pi in element.properties.indices) {
                            if (pi == layout.faceListIndex) {
                                val listProp = element.properties[pi]
                                val n = tokenizer.next()?.toIntOrNull()
                                    ?: throw PlyParseException("Invalid face index count in PLY")
                                repeat(n) {
                                    val idx = tokenizer.next()?.toIntOrNull()
                                        ?: throw PlyParseException("Invalid vertex index in PLY face")
                                    indices.add(idx)
                                }
                            } else {
                                tokenizer.next()
                                    ?: throw PlyParseException("Unexpected end of file in PLY faces")
                            }
                        }
                        emitFace(indices, vertexData, builder, tri)
                    }
                }
                else -> skipAsciiElement(element, tokenizer)
            }
        }
        return builder.build()
    }

    private fun skipAsciiElement(element: Element, tokenizer: TokenReader) {
        for (r in 0 until element.count) {
            for (p in element.properties) {
                if (p.isList) {
                    val n = tokenizer.next()?.toIntOrNull() ?: return
                    repeat(n) { if (tokenizer.next() == null) return }
                } else {
                    if (tokenizer.next() == null) return
                }
            }
        }
    }

    // --- Binary data ----------------------------------------------------------

    private fun parseBinary(stream: InputStream, header: Header, littleEndian: Boolean): Mesh {
        val layout = resolveLayout(header)
        val reader = BinReader(DataInputStream(stream), littleEndian)
        val vertexData = FloatArray(layout.vertexCount * 3)
        val builder = MeshBuilder(layout.faceCount.coerceIn(1, 1_000_000))
        val indices = IntList(16)
        val tri = FloatArray(9)
        var vertexOffset = 0

        try {
            for (element in header.elements) {
                when (element.name.lowercase()) {
                    "vertex" -> {
                        for (v in 0 until element.count) {
                            for (pi in element.properties.indices) {
                                val value = reader.readScalar(element.properties[pi].type)
                                if (pi == layout.xIndex || pi == layout.yIndex || pi == layout.zIndex) {
                                    val dst = (vertexOffset + v) * 3
                                    when (pi) {
                                        layout.xIndex -> vertexData[dst] = value.toFloat()
                                        layout.yIndex -> vertexData[dst + 1] = value.toFloat()
                                        layout.zIndex -> vertexData[dst + 2] = value.toFloat()
                                    }
                                }
                            }
                        }
                        vertexOffset += element.count
                    }
                    "face" -> {
                        for (f in 0 until element.count) {
                            indices.clear()
                            for (pi in element.properties.indices) {
                                val p = element.properties[pi]
                                if (pi == layout.faceListIndex) {
                                    val n = reader.readScalar(p.countType!!).toInt()
                                    if (n < 0) throw PlyParseException("Negative face degree in PLY")
                                    repeat(n) { indices.add(reader.readScalar(p.itemType).toInt()) }
                                } else {
                                    reader.readScalar(p.type)
                                }
                            }
                            emitFace(indices, vertexData, builder, tri)
                        }
                    }
                    else -> skipBinaryElement(element, reader)
                }
            }
        } catch (e: EOFException) {
            throw PlyParseException("Unexpected end of file in PLY data", e)
        }
        return builder.build()
    }

    private fun skipBinaryElement(element: Element, reader: BinReader) {
        for (r in 0 until element.count) {
            for (p in element.properties) {
                if (p.isList) {
                    val n = reader.readScalar(p.countType!!).toInt()
                    repeat(n) { reader.readScalar(p.itemType) }
                } else {
                    reader.readScalar(p.type)
                }
            }
        }
    }

    // --- Shared face emission -------------------------------------------------

    private fun emitFace(indices: IntList, vertexData: FloatArray, builder: MeshBuilder, tri: FloatArray) {
        val n = indices.size
        if (n < 3) return
        val vertexCount = vertexData.size / 3
        val i0 = indices.get(0)
        if (i0 < 0 || i0 >= vertexCount) throw PlyParseException("Vertex index $i0 out of bounds")
        val x0 = vertexData[i0 * 3]; val y0 = vertexData[i0 * 3 + 1]; val z0 = vertexData[i0 * 3 + 2]
        for (i in 1 until n - 1) {
            val i1 = indices.get(i)
            val i2 = indices.get(i + 1)
            if (i1 < 0 || i1 >= vertexCount || i2 < 0 || i2 >= vertexCount) {
                throw PlyParseException("Vertex index out of bounds in PLY face")
            }
            tri[0] = x0; tri[1] = y0; tri[2] = z0
            tri[3] = vertexData[i1 * 3]; tri[4] = vertexData[i1 * 3 + 1]; tri[5] = vertexData[i1 * 3 + 2]
            tri[6] = vertexData[i2 * 3]; tri[7] = vertexData[i2 * 3 + 1]; tri[8] = vertexData[i2 * 3 + 2]
            builder.addTriangle(tri)
        }
    }

    // --- Binary scalar reader (endian-aware) ----------------------------------

    private class BinReader(private val din: DataInputStream, private val little: Boolean) {
        fun readScalar(t: Scalar): Double = when (t) {
            Scalar.INT8 -> din.readByte().toDouble()
            Scalar.UINT8 -> din.readUnsignedByte().toDouble()
            Scalar.INT16 -> (if (little) readShortLE() else din.readShort()).toDouble()
            Scalar.UINT16 -> (if (little) readUShortLE() else din.readUnsignedShort()).toDouble()
            Scalar.INT32 -> (if (little) readIntLE() else din.readInt()).toDouble()
            Scalar.UINT32 -> (if (little) readUIntLE() else readUIntBE(din.readInt())).toDouble()
            Scalar.FLOAT32 -> (if (little) Float.fromBits(readIntLE()) else din.readFloat()).toDouble()
            Scalar.FLOAT64 -> (if (little) Double.fromBits(readLongLE()) else din.readDouble())
        }

        // Little-endian: the first byte is the low byte.
        private fun readShortLE(): Short = readUShortLE().toShort()
        private fun readUShortLE(): Int = readUnsigned() or (readUnsigned() shl 8)
        private fun readIntLE(): Int =
            readUnsigned() or (readUnsigned() shl 8) or (readUnsigned() shl 16) or (readUnsigned() shl 24)
        private fun readUIntLE(): Long = readIntLE().toLong() and 0xFFFFFFFFL
        private fun readLongLE(): Long {
            val lo = readIntLE().toLong() and 0xFFFFFFFFL
            val hi = readIntLE().toLong() and 0xFFFFFFFFL
            return lo or (hi shl 32)
        }
        private fun readUIntBE(v: Int): Long = v.toLong() and 0xFFFFFFFFL
        private fun readUnsigned(): Int = din.readUnsignedByte()
    }

    // --- ASCII token reader ---------------------------------------------------

    private class TokenReader(private val input: InputStream) {
        private val buffer = ByteArray(1 shl 16)
        private var pos = 0
        private var limit = 0
        private val sb = StringBuilder(32)

        fun next(): String? {
            sb.setLength(0)
            var b = read()
            while (b != -1 && isSpace(b)) b = read()
            if (b == -1) return null
            while (b != -1 && !isSpace(b)) {
                sb.append(b.toChar())
                b = read()
            }
            return sb.toString()
        }

        private fun read(): Int {
            if (pos == limit) {
                limit = input.read(buffer, 0, buffer.size)
                pos = 0
                if (limit <= 0) {
                    limit = 0
                    return -1
                }
            }
            return buffer[pos++].toInt() and 0xFF
        }

        private fun isSpace(b: Int) =
            b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code || b == 0x0C
    }

    // --- Growable int list ----------------------------------------------------

    private class IntList(initialCapacity: Int = 16) {
        private var data = IntArray(initialCapacity)
        var size = 0
            private set

        fun add(v: Int) {
            if (size >= data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun get(i: Int): Int = data[i]

        fun clear() { size = 0 }
    }
}
