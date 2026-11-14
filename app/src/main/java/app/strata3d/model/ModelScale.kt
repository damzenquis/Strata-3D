package app.strata3d.model

import java.util.Locale
import kotlin.math.abs

/** Where the unit of a model's coordinates comes from. */
enum class UnitSource {
    /** Declared by the file: glTF is always metres, 3MF has a `unit` attribute (millimetres by default). */
    FILE,

    /** Chosen by the user in the measurement card (remembered per file). */
    USER,

    /** Nothing is known (STL, OBJ, PLY): millimetres are assumed, and every display says so. */
    ASSUMED,
}

/**
 * How model coordinates map to real lengths. Geometry is never rescaled: the [unit] only changes how
 * coordinates are *interpreted*, and every displayed length, area and volume is converted to millimetres
 * through [mmPerUnit].
 *
 * Model transforms (3MF build items and components, glTF node hierarchies) are already applied to the
 * coordinates by the parsers, so lengths measured between coordinates are lengths in the assembled model.
 */
data class ModelScale(val unit: LengthUnit, val source: UnitSource) {

    val mmPerUnit: Double get() = unit.mmPerUnit

    /** True when the unit comes from the file or the user, false when millimetres are only assumed. */
    val isKnown: Boolean get() = source != UnitSource.ASSUMED

    fun toMm(length: Double): Double = length * mmPerUnit
    fun toMm2(area: Double): Double = area * mmPerUnit * mmPerUnit
    fun toMm3(volume: Double): Double = volume * mmPerUnit * mmPerUnit * mmPerUnit

    companion object {
        /**
         * The user's choice wins (it corrects files that declare the wrong unit), then the file's declared unit,
         * then assumed millimetres.
         */
        fun resolve(declared: LengthUnit?, userChoice: LengthUnit?): ModelScale = when {
            userChoice != null -> ModelScale(userChoice, UnitSource.USER)
            declared != null -> ModelScale(declared, UnitSource.FILE)
            else -> ModelScale(LengthUnit.MILLIMETER, UnitSource.ASSUMED)
        }
    }
}

/** Locale-aware display of converted lengths, areas and volumes (pure; unit-tested). */
object LengthFormat {

    /**
     * A length given in millimetres: "12.40 mm", "0.125 mm" below 1 mm, "12.40 m" from 10 m upwards (so large
     * scenes stay readable).
     */
    fun length(mm: Double, locale: Locale = Locale.getDefault()): String {
        val a = abs(mm)
        return when {
            !mm.isFinite() -> "–"
            a >= 10_000.0 -> String.format(locale, "%.2f m", mm / 1000.0)
            // Three decimals below 1 mm; float noise around zero (for example ΔZ on a flat face) reads 0.00.
            a >= 0.0005 && a < 1.0 -> String.format(locale, "%.3f mm", mm)
            else -> String.format(locale, "%.2f mm", mm)
        }
    }

    /** "w × d × h mm" from three millimetre sizes, using the largest size to pick mm or m for all three. */
    fun size(xMm: Double, yMm: Double, zMm: Double, locale: Locale = Locale.getDefault()): String {
        val inMetres = maxOf(abs(xMm), abs(yMm), abs(zMm)) >= 10_000.0
        val (div, symbol) = if (inMetres) 1000.0 to "m" else 1.0 to "mm"
        return listOf(xMm, yMm, zMm).joinToString(" × ", postfix = " $symbol") { String.format(locale, "%.2f", it / div) }
    }

    /** An area in mm², shown in cm². */
    fun area(mm2: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.2f cm²", mm2 / 100.0)

    /** A volume in mm³, shown in cm³. */
    fun volume(mm3: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.2f cm³", mm3 / 1000.0)
}
