package app.strata3d.capture.recon

import app.strata3d.capture.format.CaptureFormatException
import app.strata3d.capture.format.JsonOut
import app.strata3d.model.JsonParser
import app.strata3d.model.asDouble
import app.strata3d.model.asString
import app.strata3d.model.get
import java.io.BufferedReader
import java.io.Writer
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Real-world size of a built model (approved mockup: one number, Height or Width, cm or in). Pure and unit-tested.
 *
 * Photos alone don't give a model a real size, so its units are arbitrary until the user gives one measurement.
 * The other two dimensions then follow from the model's own proportions. One number only: a second one could
 * disagree with the first, and choosing between them would be a silent guess.
 *
 * The models are Z-up, standing on the surface (see objbox), so:
 * - height = extent along Z;
 * - width and depth = the longer and shorter sides of the smallest rectangle around the footprint (seen from above).
 * Each extent ignores the outermost [TRIM] of the vertices at both ends, so stray fragments don't count.
 */
object ModelSize {

    enum class Axis(val id: String) {
        HEIGHT("height"), WIDTH("width");
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    enum class SizeUnit(val id: String, val symbol: String, val mm: Double) {
        CM("cm", "cm", 10.0), IN("in", "in", 25.4);
        companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
    }

    /** What the user entered: one measurement of the object. */
    data class Request(val axis: Axis, val value: Double, val unit: SizeUnit) {
        val mm: Double get() = value * unit.mm
    }

    /** Height, width and depth, in model units or (once sized) millimetres. */
    data class Dimensions(val height: Double, val width: Double, val depth: Double) {
        fun of(axis: Axis) = if (axis == Axis.HEIGHT) height else width
        fun times(f: Double) = Dimensions(height * f, width * f, depth * f)
    }

    /** The capture's `size.json`: the request, plus the model's dimensions in mm once applied to a model. */
    data class Record(val request: Request, val dimensionsMm: Dimensions? = null) {
        fun encode(): String = JsonOut().obj {
            field("axis", request.axis.id)
            field("value", request.value)
            field("unit", request.unit.id)
            dimensionsMm?.let { field("heightMm", it.height); field("widthMm", it.width); field("depthMm", it.depth) }
        }.toString()

        companion object {
            fun decode(text: String): Record {
                val o = try { JsonParser.parse(text) } catch (e: Exception) { throw CaptureFormatException("Malformed size") }
                val request = Request(
                    Axis.of(o.get("axis").asString()) ?: throw CaptureFormatException("Bad axis"),
                    o.get("value").asDouble()?.takeIf { it > 0 && it.isFinite() } ?: throw CaptureFormatException("Bad value"),
                    SizeUnit.of(o.get("unit").asString()) ?: throw CaptureFormatException("Bad unit"),
                )
                val h = o.get("heightMm").asDouble(); val w = o.get("widthMm").asDouble(); val d = o.get("depthMm").asDouble()
                return Record(request, if (h != null && w != null && d != null) Dimensions(h, w, d) else null)
            }
        }
    }

    const val TRIM = 0.005
    /** Accepted range of an entered measurement, in mm: 5 mm to 5 m (the capture is for objects, not rooms). */
    const val MIN_MM = 5.0
    const val MAX_MM = 5000.0

    /** First comment line of a sized model: the viewer reads its coordinates as millimetres. */
    const val UNIT_COMMENT = app.strata3d.model.ObjParser.MILLIMETRE_COMMENT

    fun isValid(r: Request) = r.value.isFinite() && r.mm in MIN_MM..MAX_MM

    /** A number typed by the user ("12.5" or "12,5", no grouping); null if it isn't a plain positive number. */
    fun parseNumber(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty() || t.any { !(it.isDigit() || it == '.' || it == ',') } || t.count { it == '.' || it == ',' } > 1) return null
        return t.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    }

    /** Height, width and depth of the vertices (x, y, z interleaved), in the vertices' units. */
    fun measure(xyz: DoubleArray): Dimensions {
        val n = xyz.size / 3
        require(n > 0) { "no vertices" }
        val z = DoubleArray(n) { xyz[3 * it + 2] }
        val height = trimmedExtent(z)
        // Footprint: the smallest-area rectangle around it (1° steps); width is its longer side, depth the shorter.
        var bestArea = Double.MAX_VALUE; var width = 0.0; var depth = 0.0
        val u = DoubleArray(n); val v = DoubleArray(n)
        for (deg in 0 until 90) {
            val a = Math.toRadians(deg.toDouble()); val c = cos(a); val s = sin(a)
            for (i in 0 until n) { u[i] = xyz[3 * i] * c + xyz[3 * i + 1] * s; v[i] = -xyz[3 * i] * s + xyz[3 * i + 1] * c }
            val eu = trimmedExtent(u); val ev = trimmedExtent(v)
            if (eu * ev < bestArea) { bestArea = eu * ev; width = maxOf(eu, ev); depth = minOf(eu, ev) }
        }
        return Dimensions(height, width, depth)
    }

    /** Scale that makes the measured [axis] of [model] equal to the request, with the result in millimetres. */
    fun factor(model: Dimensions, r: Request): Double {
        val measured = model.of(r.axis)
        require(measured > 0) { "the model has no ${r.axis.id}" }
        return r.mm / measured
    }

    /**
     * Copies an OBJ, multiplying every vertex position by [factor] and marking it as millimetres ([UNIT_COMMENT] as
     * the first line, replacing an earlier one). Texture coordinates and normals are unchanged (uniform scale).
     */
    fun scaleObj(input: BufferedReader, output: Writer, factor: Double) {
        output.write(UNIT_COMMENT); output.write("\n")
        input.lineSequence().forEach { line ->
            when {
                line == UNIT_COMMENT -> {}
                line.startsWith("v ") -> {
                    val p = line.substring(2).trim().split(Regex("\\s+"))
                    output.write("v")
                    p.forEachIndexed { i, t ->
                        output.write(" ")
                        output.write(if (i < 3) fmt(t.toDouble() * factor) else t)
                    }
                    output.write("\n")
                }
                else -> { output.write(line); output.write("\n") }
            }
        }
    }

    /** Vertex positions of an OBJ (x, y, z interleaved) and whether it is already marked as millimetres. */
    fun readVertices(input: BufferedReader): Pair<DoubleArray, Boolean> {
        var mm = false
        var out = DoubleArray(3 * 4096); var n = 0
        input.lineSequence().forEach { line ->
            if (line == UNIT_COMMENT) mm = true
            else if (line.startsWith("v ")) {
                val p = line.substring(2).trim().split(Regex("\\s+"))
                if (n + 3 > out.size) out = out.copyOf(out.size * 2)
                out[n] = p[0].toDouble(); out[n + 1] = p[1].toDouble(); out[n + 2] = p[2].toDouble(); n += 3
            }
        }
        return out.copyOf(n) to mm
    }

    /** "23.5 × 6.1 × 6.0 cm" (height × width × depth); whole numbers from 100 upwards. */
    fun format(mm: Dimensions, unit: SizeUnit, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getNumberInstance(locale)
        fun one(x: Double): String {
            val v = x / unit.mm
            nf.minimumFractionDigits = if (v >= 100) 0 else 1; nf.maximumFractionDigits = nf.minimumFractionDigits
            return nf.format(v)
        }
        return "${one(mm.height)} × ${one(mm.width)} × ${one(mm.depth)} ${unit.symbol}"
    }

    /** The entered value as shown in the field ("25", "12.5"). */
    fun formatValue(value: Double, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2; isGroupingUsed = false }.format(value)

    private fun trimmedExtent(values: DoubleArray): Double {
        val s = values.copyOf(); s.sort()
        val k = (TRIM * (s.size - 1)).roundToLong().toInt()
        return s[s.size - 1 - k] - s[k]
    }

    private fun fmt(d: Double): String {
        val r = if (abs(d) < 1e-9) 0.0 else d
        return String.format(Locale.ROOT, "%.5f", r).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
