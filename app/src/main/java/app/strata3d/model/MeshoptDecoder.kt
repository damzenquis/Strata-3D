package app.strata3d.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MeshoptDecodeException(message: String, cause: Throwable? = null) : ModelParseException(message, cause)

/**
 * Decoder for meshoptimizer-compressed glTF buffer views (`EXT_meshopt_compression` / `KHR_meshopt_compression`).
 *
 * A Kotlin port of the reference decoder that ships with meshoptimizer (`meshopt_decoder_reference.js`,
 * MIT License, Copyright (C) 2016-2026 Arseny Kapoulkine; reference implementation by Jasper St. Pierre).
 * Covers the vertex codec (versions 0 and 1), the triangle index codec, the index sequence codec, and the
 * OCTAHEDRAL / QUATERNION / EXPONENTIAL / COLOR filters. All multi-byte data is little-endian.
 */
object MeshoptDecoder {

    /**
     * Decodes one compressed buffer view into [count] × [byteStride] bytes.
     *
     * @param mode "ATTRIBUTES", "TRIANGLES" or "INDICES".
     * @param filter "NONE", "OCTAHEDRAL", "QUATERNION", "EXPONENTIAL" or "COLOR" (attributes only).
     */
    fun decode(source: ByteArray, count: Int, byteStride: Int, mode: String, filter: String?): ByteArray {
        if (count < 0 || byteStride <= 0 || count.toLong() * byteStride > Int.MAX_VALUE) {
            throw MeshoptDecodeException("Invalid meshopt buffer size ($count × $byteStride)")
        }
        val target = ByteArray(count * byteStride)
        try {
            when (mode) {
                "ATTRIBUTES" -> decodeVertexBuffer(target, count, byteStride, source, filter ?: "NONE")
                "TRIANGLES" -> decodeIndexBuffer(target, count, byteStride, source)
                "INDICES" -> decodeIndexSequence(target, count, byteStride, source)
                else -> throw MeshoptDecodeException("Unknown meshopt mode '$mode'")
            }
        } catch (e: MeshoptDecodeException) {
            throw e
        } catch (e: IndexOutOfBoundsException) {
            throw MeshoptDecodeException("Meshopt data is truncated or corrupt", e)
        }
        return target
    }

    private fun dezig(v: Int): Int = (v ushr 1) xor -(v and 1)

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw MeshoptDecodeException(message)
    }

    // --- Vertex codec ------------------------------------------------------------

    private val HEADER_MODES = arrayOf(
        intArrayOf(0, 2, 4, 8), // v0
        intArrayOf(0, 1, 2, 4), // v1, control 0
        intArrayOf(1, 2, 4, 8), // v1, control 1
    )

    private fun decodeVertexBuffer(target: ByteArray, elementCount: Int, byteStride: Int, source: ByteArray, filter: String) {
        check(source.isNotEmpty() && (source[0].u() == 0xa0 || source[0].u() == 0xa1), "Unsupported meshopt vertex data header")
        check(byteStride % 4 == 0 && byteStride <= 256, "Invalid meshopt vertex stride $byteStride")
        val version = source[0].u() and 0x0f

        val maxBlockElements = minOf((0x2000 / byteStride) and 0x000f.inv(), 0x100)
        val deltas = IntArray(maxBlockElements * byteStride)

        val tailSize = if (version == 0) byteStride else byteStride + byteStride / 4
        val tailDataOffs = source.size - tailSize
        check(tailDataOffs >= 1, "Meshopt vertex data is too short")

        // Values the deltas are relative to.
        val tempData = IntArray(byteStride) { source[tailDataOffs + it].u() }
        // Channel modes (v1 only).
        val channels = if (version == 0) null else IntArray(tailSize - byteStride) { source[tailDataOffs + byteStride + it].u() }

        var srcOffs = 1

        var dstElemBase = 0
        while (dstElemBase < elementCount) {
            val blockCount = minOf(elementCount - dstElemBase, maxBlockElements)
            val groupCount = ((blockCount + 0x0f) and 0x0f.inv()) ushr 4
            val headerByteCount = ((groupCount + 0x03) and 0x03.inv()) ushr 2

            val controlBitsOffs = srcOffs
            srcOffs += if (version == 0) 0 else byteStride / 4

            deltas.fill(0)

            for (byte in 0 until byteStride) {
                val deltaBase = byte * blockCount
                val controlMode = if (version == 0) 0 else (source[controlBitsOffs + (byte ushr 2)].u() ushr ((byte and 0x03) shl 1)) and 0x03

                if (controlMode == 2) continue // all deltas are 0
                if (controlMode == 3) {        // stored uncompressed
                    for (i in 0 until blockCount) deltas[deltaBase + i] = source[srcOffs + i].u()
                    srcOffs += blockCount
                    continue
                }

                val headerBitsOffs = srcOffs
                srcOffs += headerByteCount

                for (group in 0 until groupCount) {
                    val mode = (source[headerBitsOffs + (group ushr 2)].u() ushr ((group and 0x03) shl 1)) and 0x03
                    val modeBits = HEADER_MODES[if (version == 0) 0 else controlMode + 1][mode]
                    val deltaOffs = deltaBase + (group shl 4)

                    when (modeBits) {
                        0 -> Unit
                        1 -> {
                            val srcBase = srcOffs
                            srcOffs += 0x02
                            for (m in 0 until 0x10) {
                                var delta = (source[srcBase + (m ushr 3)].u() ushr (m and 0x07)) and 0x01
                                if (delta == 1) delta = source[srcOffs++].u()
                                putDelta(deltas, deltaOffs + m, deltaBase, blockCount, delta)
                            }
                        }
                        2 -> {
                            val srcBase = srcOffs
                            srcOffs += 0x04
                            for (m in 0 until 0x10) {
                                val shift = 6 - ((m and 0x03) shl 1)
                                var delta = (source[srcBase + (m ushr 2)].u() ushr shift) and 0x03
                                if (delta == 3) delta = source[srcOffs++].u()
                                putDelta(deltas, deltaOffs + m, deltaBase, blockCount, delta)
                            }
                        }
                        4 -> {
                            val srcBase = srcOffs
                            srcOffs += 0x08
                            for (m in 0 until 0x10) {
                                val shift = 4 - ((m and 0x01) shl 2)
                                var delta = (source[srcBase + (m ushr 1)].u() ushr shift) and 0x0f
                                if (delta == 0xf) delta = source[srcOffs++].u()
                                putDelta(deltas, deltaOffs + m, deltaBase, blockCount, delta)
                            }
                        }
                        else -> {
                            for (m in 0 until 0x10) putDelta(deltas, deltaOffs + m, deltaBase, blockCount, source[srcOffs + m].u())
                            srcOffs += 0x10
                        }
                    }
                }
            }

            for (elem in 0 until blockCount) {
                val dstElem = dstElemBase + elem
                var byteGroup = 0
                while (byteGroup < byteStride) {
                    val channelMode = if (channels == null) 0 else channels[byteGroup ushr 2] and 0x03
                    check(channelMode != 0x03, "Invalid meshopt channel mode")
                    when (channelMode) {
                        0 -> for (byte in byteGroup until byteGroup + 4) {
                            val temp = (tempData[byte] + dezig(deltas[(byte) * blockCount + elem])) and 0xff
                            tempData[byte] = temp
                            target[dstElem * byteStride + byte] = temp.toByte()
                        }
                        1 -> {
                            var byte = byteGroup
                            while (byte < byteGroup + 4) {
                                val delta = dezig(deltas[(byte) * blockCount + elem] + (deltas[(byte + 1) * blockCount + elem] shl 8))
                                val temp = (tempData[byte] + (tempData[byte + 1] shl 8) + delta) and 0xffff
                                tempData[byte] = temp and 0xff
                                tempData[byte + 1] = temp ushr 8
                                target[dstElem * byteStride + byte] = (temp and 0xff).toByte()
                                target[dstElem * byteStride + byte + 1] = (temp ushr 8).toByte()
                                byte += 2
                            }
                        }
                        2 -> {
                            val byte = byteGroup
                            val delta = deltas[(byte) * blockCount + elem] or (deltas[(byte + 1) * blockCount + elem] shl 8) or (deltas[(byte + 2) * blockCount + elem] shl 16) or (deltas[(byte + 3) * blockCount + elem] shl 24)
                            var temp = tempData[byte] or (tempData[byte + 1] shl 8) or (tempData[byte + 2] shl 16) or (tempData[byte + 3] shl 24)
                            val rot = channels!![byteGroup ushr 2] ushr 4
                            temp = temp xor Integer.rotateRight(delta, rot)
                            for (k in 0 until 4) {
                                val v = (temp ushr (8 * k)) and 0xff
                                tempData[byte + k] = v
                                target[dstElem * byteStride + byte + k] = v.toByte()
                            }
                        }
                    }
                    byteGroup += 4
                }
            }
            dstElemBase += maxBlockElements
        }

        val tailSizePadded = max(tailSize, if (version == 0) 32 else 24)
        check(srcOffs == source.size - tailSizePadded, "Meshopt vertex data has an unexpected length")

        applyFilter(target, elementCount, byteStride, filter)
    }

    /** Stores a byte delta, guarding against groups that run past this byte's slice of the block. */
    private fun putDelta(deltas: IntArray, index: Int, deltaBase: Int, blockCount: Int, value: Int) {
        // Groups are 16 wide, so the last group of a byte can extend past blockCount; those values are unused,
        // but they must not overwrite the next byte's deltas.
        if (index - deltaBase < blockCount) deltas[index] = value
    }

    // --- Filters -------------------------------------------------------------------

    private fun applyFilter(target: ByteArray, elementCount: Int, byteStride: Int, filter: String) {
        val bb = ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN)
        when (filter) {
            "NONE", "" -> Unit
            "OCTAHEDRAL" -> {
                check(byteStride == 4 || byteStride == 8, "OCTAHEDRAL filter needs a stride of 4 or 8")
                val wide = byteStride == 8
                val maxInt = if (wide) 32767.0 else 127.0
                for (i in 0 until elementCount) {
                    var x = readSigned(bb, i, 0, wide).toDouble()
                    var y = readSigned(bb, i, 1, wide).toDouble()
                    val one = readSigned(bb, i, 2, wide).toDouble()
                    x /= one
                    y /= one
                    val z = 1.0 - abs(x) - abs(y)
                    val t = max(-z, 0.0)
                    x -= if (x >= 0) t else -t
                    y -= if (y >= 0) t else -t
                    val h = maxInt / sqrt(x * x + y * y + z * z)
                    writeSigned(bb, i, 0, wide, jsRound(x * h))
                    writeSigned(bb, i, 1, wide, jsRound(y * h))
                    writeSigned(bb, i, 2, wide, jsRound(z * h))
                }
            }
            "QUATERNION" -> {
                check(byteStride == 8, "QUATERNION filter needs a stride of 8")
                for (i in 0 until elementCount) {
                    val base = i * 8
                    val inputW = bb.getShort(base + 6).toInt()
                    val maxComponent = inputW and 0x03
                    val s = Math.sqrt(0.5) / (inputW or 0x03)
                    val x = bb.getShort(base).toInt() * s
                    val y = bb.getShort(base + 2).toInt() * s
                    val z = bb.getShort(base + 4).toInt() * s
                    val w = sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
                    bb.putShort(base + ((maxComponent + 1) % 4) * 2, jsRound(x * 32767).toShort())
                    bb.putShort(base + ((maxComponent + 2) % 4) * 2, jsRound(y * 32767).toShort())
                    bb.putShort(base + ((maxComponent + 3) % 4) * 2, jsRound(z * 32767).toShort())
                    bb.putShort(base + ((maxComponent + 0) % 4) * 2, jsRound(w * 32767).toShort())
                }
            }
            "EXPONENTIAL" -> {
                check(byteStride and 0x03 == 0, "EXPONENTIAL filter needs a stride that is a multiple of 4")
                for (i in 0 until elementCount * (byteStride / 4)) {
                    val v = bb.getInt(i * 4)
                    val exp = v shr 24
                    val mantissa = (v shl 8) shr 8
                    val scale = java.lang.Float.intBitsToFloat((exp + 127) shl 23)
                    bb.putFloat(i * 4, scale * mantissa)
                }
            }
            "COLOR" -> {
                check(byteStride == 4 || byteStride == 8, "COLOR filter needs a stride of 4 or 8")
                val wide = byteStride == 8
                val maxInt = (1 shl (byteStride * 2)) - 1
                for (i in 0 until elementCount) {
                    val y = readUnsigned(bb, i, 0, wide)
                    val co = readSigned(bb, i, 1, wide)
                    val cg = readSigned(bb, i, 2, wide)
                    val alphaInput = readUnsigned(bb, i, 3, wide)
                    val alphaBit = 31 - Integer.numberOfLeadingZeros(alphaInput)
                    val asv = (1 shl (alphaBit + 1)) - 1
                    val r = y + co - cg
                    val g = y + cg
                    val b = y - co - cg
                    var a = alphaInput and (asv shr 1)
                    a = (a shl 1) or (a and 1)
                    val ss = maxInt.toDouble() / asv
                    writeUnsigned(bb, i, 0, wide, jsRound(r * ss))
                    writeUnsigned(bb, i, 1, wide, jsRound(g * ss))
                    writeUnsigned(bb, i, 2, wide, jsRound(b * ss))
                    writeUnsigned(bb, i, 3, wide, jsRound(a * ss))
                }
            }
            else -> throw MeshoptDecodeException("Unknown meshopt filter '$filter'")
        }
    }

    /** JavaScript Math.round: halves round towards +infinity. */
    private fun jsRound(v: Double): Int = Math.floor(v + 0.5).toInt()

    private fun readSigned(bb: ByteBuffer, elem: Int, comp: Int, wide: Boolean): Int =
        if (wide) bb.getShort(elem * 8 + comp * 2).toInt() else bb.get(elem * 4 + comp).toInt()

    private fun readUnsigned(bb: ByteBuffer, elem: Int, comp: Int, wide: Boolean): Int =
        if (wide) bb.getShort(elem * 8 + comp * 2).toInt() and 0xffff else bb.get(elem * 4 + comp).toInt() and 0xff

    private fun writeSigned(bb: ByteBuffer, elem: Int, comp: Int, wide: Boolean, v: Int) {
        if (wide) bb.putShort(elem * 8 + comp * 2, v.toShort()) else bb.put(elem * 4 + comp, v.toByte())
    }

    private fun writeUnsigned(bb: ByteBuffer, elem: Int, comp: Int, wide: Boolean, v: Int) = writeSigned(bb, elem, comp, wide, v)

    // --- Index codecs ----------------------------------------------------------------

    private class Fifo(size: Int) {
        val data = IntArray(size)
        var offset = 0
        fun read(n: Int): Int = data[(offset - 1 - n) and (data.size - 1)]
        fun push(v: Int) {
            data[offset] = v
            offset = (offset + 1) and (data.size - 1)
        }
    }

    private class Reader(val source: ByteArray, var offs: Int) {
        fun byte(): Int = source[offs++].u()
        fun leb128(): Int {
            var n = 0
            var shift = 0
            while (true) {
                val b = byte()
                n = n or ((b and 0x7f) shl shift)
                if (b < 0x80) return n
                shift += 7
                if (shift > 35) throw MeshoptDecodeException("Corrupt meshopt varint")
            }
        }
    }

    private fun writeIndex(target: ByteArray, i: Int, byteStride: Int, value: Int) {
        val o = i * byteStride
        target[o] = value.toByte()
        target[o + 1] = (value ushr 8).toByte()
        if (byteStride == 4) {
            target[o + 2] = (value ushr 16).toByte()
            target[o + 3] = (value ushr 24).toByte()
        }
    }

    private fun decodeIndexBuffer(target: ByteArray, count: Int, byteStride: Int, source: ByteArray) {
        check(source.isNotEmpty() && source[0].u() == 0xe1, "Unsupported meshopt index data header")
        check(count % 3 == 0, "Meshopt triangle data needs a multiple of 3 indices")
        check(byteStride == 2 || byteStride == 4, "Meshopt index stride must be 2 or 4")
        val triCount = count / 3
        check(source.size >= 1 + triCount + 16, "Meshopt index data is too short")

        var codeOffs = 1
        val data = Reader(source, codeOffs + triCount)
        val codeauxOffs = source.size - 0x10

        var next = 0
        var last = 0
        val edgefifo = Fifo(32)
        val vertexfifo = Fifo(16)
        fun decodeIndex(v: Int): Int {
            last += dezig(v)
            return last
        }

        var dst = 0
        for (i in 0 until triCount) {
            val code = source[codeOffs++].u()
            val b0 = code ushr 4
            val b1 = code and 0x0f

            if (b0 < 0x0f) {
                val a = edgefifo.read((b0 shl 1) + 0)
                val b = edgefifo.read((b0 shl 1) + 1)
                val c: Int
                when {
                    b1 == 0x00 -> { c = next++; vertexfifo.push(c) }
                    b1 < 0x0d -> c = vertexfifo.read(b1)
                    b1 == 0x0d -> { c = --last; vertexfifo.push(c) }
                    b1 == 0x0e -> { c = ++last; vertexfifo.push(c) }
                    else -> { c = decodeIndex(data.leb128()); vertexfifo.push(c) }
                }
                // fifo pushes happen backwards
                edgefifo.push(b); edgefifo.push(c)
                edgefifo.push(c); edgefifo.push(a)
                writeIndex(target, dst++, byteStride, a)
                writeIndex(target, dst++, byteStride, b)
                writeIndex(target, dst++, byteStride, c)
            } else {
                val a: Int
                val b: Int
                val c: Int
                if (b1 < 0x0e) {
                    val e = source[codeauxOffs + b1].u()
                    val z = e ushr 4
                    val w = e and 0x0f
                    a = next++
                    b = if (z == 0x00) next++ else vertexfifo.read(z - 1)
                    c = if (w == 0x00) next++ else vertexfifo.read(w - 1)
                    vertexfifo.push(a)
                    if (z == 0x00) vertexfifo.push(b)
                    if (w == 0x00) vertexfifo.push(c)
                } else {
                    val e = data.byte()
                    if (e == 0x00) next = 0
                    val z = e ushr 4
                    val w = e and 0x0f
                    a = if (b1 == 0x0e) next++ else decodeIndex(data.leb128())
                    b = when (z) {
                        0x00 -> next++
                        0x0f -> decodeIndex(data.leb128())
                        else -> vertexfifo.read(z - 1)
                    }
                    c = when (w) {
                        0x00 -> next++
                        0x0f -> decodeIndex(data.leb128())
                        else -> vertexfifo.read(w - 1)
                    }
                    vertexfifo.push(a)
                    if (z == 0x00 || z == 0x0f) vertexfifo.push(b)
                    if (w == 0x00 || w == 0x0f) vertexfifo.push(c)
                }
                edgefifo.push(a); edgefifo.push(b)
                edgefifo.push(b); edgefifo.push(c)
                edgefifo.push(c); edgefifo.push(a)
                writeIndex(target, dst++, byteStride, a)
                writeIndex(target, dst++, byteStride, b)
                writeIndex(target, dst++, byteStride, c)
            }
        }
        check(data.offs == codeauxOffs, "Meshopt index data has an unexpected length")
    }

    private fun decodeIndexSequence(target: ByteArray, count: Int, byteStride: Int, source: ByteArray) {
        check(source.isNotEmpty() && source[0].u() == 0xd1, "Unsupported meshopt index sequence header")
        check(byteStride == 2 || byteStride == 4, "Meshopt index stride must be 2 or 4")
        val data = Reader(source, 1)
        val last = IntArray(2)
        for (i in 0 until count) {
            val v = data.leb128()
            val b = v and 0x01
            last[b] += dezig(v ushr 1)
            writeIndex(target, i, byteStride, last[b])
        }
        check(data.offs == source.size - 4, "Meshopt index sequence has an unexpected length")
    }

    private fun Byte.u(): Int = toInt() and 0xff
}
