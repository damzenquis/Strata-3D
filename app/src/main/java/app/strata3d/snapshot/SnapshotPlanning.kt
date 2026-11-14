package app.strata3d.snapshot

import app.strata3d.model.BoundingBox
import app.strata3d.model.ModelScale
import app.strata3d.render.GpuLimits
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Image background for a snapshot. */
enum class SnapshotStyle {
    /** Real alpha transparency: only the model, no background, grid or overlays. */
    TRANSPARENT,
    /** Theme background, optional grid, optional caption band with the file name and size. */
    FRAMED,
}

/** Output resolution relative to the unobstructed part of the screen. */
enum class SnapshotSize {
    SCREEN,
    DOUBLE,
    /** Long edge of 3840 px. */
    UHD,
}

/**
 * The exact pixel sizes of one export.
 *
 * @param renderWidth GPU render width (also the output width).
 * @param renderHeight GPU render height (the model area).
 * @param captionHeight extra height of the caption band below the render (0 when there is none).
 * @param scale output pixels per on-screen pixel.
 * @param supported false when the GPU cannot render [renderWidth] × [renderHeight]; exports are never shrunk.
 */
data class SnapshotPlan(
    val renderWidth: Int,
    val renderHeight: Int,
    val captionHeight: Int,
    val scale: Float,
    val supported: Boolean,
) {
    val outputWidth: Int get() = renderWidth
    val outputHeight: Int get() = renderHeight + captionHeight
}

/** Pure sizing and formatting rules for snapshots (no Android dependencies; unit-tested). */
object SnapshotPlanning {

    const val UHD_LONG_EDGE = 3840

    fun scaleFor(size: SnapshotSize, baseWidth: Int, baseHeight: Int): Float = when (size) {
        SnapshotSize.SCREEN -> 1f
        SnapshotSize.DOUBLE -> 2f
        SnapshotSize.UHD -> UHD_LONG_EDGE.toFloat() / max(baseWidth, baseHeight).coerceAtLeast(1)
    }

    fun plan(
        size: SnapshotSize,
        baseWidth: Int,
        baseHeight: Int,
        style: SnapshotStyle,
        caption: Boolean,
        limits: GpuLimits?,
    ): SnapshotPlan = planForScale(scaleFor(size, baseWidth, baseHeight), baseWidth, baseHeight, style, caption, limits)

    fun planForScale(
        scale: Float,
        baseWidth: Int,
        baseHeight: Int,
        style: SnapshotStyle,
        caption: Boolean,
        limits: GpuLimits?,
    ): SnapshotPlan {
        val w = max(1, (baseWidth * scale).roundToInt())
        val h = max(1, (baseHeight * scale).roundToInt())
        val band = if (style == SnapshotStyle.FRAMED && caption) captionHeight(w) else 0
        return SnapshotPlan(w, h, band, scale, limits?.fits(w, h) ?: false)
    }

    /** Caption band height for an image [width] pixels wide: proportional, with a legible minimum. */
    fun captionHeight(width: Int): Int = max(40, (width * 0.12f).roundToInt().coerceAtMost(width / 2))

    /** Bytes needed in memory at once: read-back buffer + bitmap (+ framed composite). */
    fun estimatedBytes(plan: SnapshotPlan): Long {
        val render = plan.renderWidth.toLong() * plan.renderHeight * 4
        val composite = if (plan.captionHeight > 0) plan.outputWidth.toLong() * plan.outputHeight * 4 else 0L
        return render * 2 + composite
    }

    /** Reverses the row order of tightly packed RGBA pixels in place (OpenGL reads bottom-up). */
    fun flipRows(rgba: ByteBuffer, width: Int, height: Int) {
        val rowBytes = width * 4
        val top = ByteArray(rowBytes)
        val bottom = ByteArray(rowBytes)
        for (y in 0 until height / 2) {
            val a = y * rowBytes
            val b = (height - 1 - y) * rowBytes
            rgba.position(a); rgba.get(top)
            rgba.position(b); rgba.get(bottom)
            rgba.position(a); rgba.put(bottom)
            rgba.position(b); rgba.put(top)
        }
        rgba.position(0)
    }

    /**
     * "65 × 65 × 25 mm" when the units are known (declared by the file or chosen by the user), converted to
     * millimetres (metres from 10 m upwards). With only assumed units (STL, OBJ, PLY) the raw sizes are shown
     * without a unit, so none is invented.
     */
    fun dimensionsText(bounds: BoundingBox, scale: ModelScale): String {
        val sizes = listOf(bounds.sizeX, bounds.sizeY, bounds.sizeZ)
        if (!scale.isKnown) return sizes.joinToString(" × ") { formatLength(it) }
        val mm = sizes.map { scale.toMm(it.toDouble()) }
        val metres = mm.max() >= 10_000.0
        val text = mm.joinToString(" × ") { formatLength((if (metres) it / 1000.0 else it).toFloat()) }
        return if (metres) "$text m" else "$text mm"
    }

    /** Four significant digits without trailing zeros: 65, 12.5, 0.0123. */
    fun formatLength(value: Float): String {
        val v = abs(value.toDouble())
        val decimals = when {
            v >= 1000 -> 0
            v >= 100 -> 1
            v >= 10 -> 2
            v >= 1 -> 3
            v == 0.0 -> 0
            else -> 3 + (-kotlin.math.log10(v)).toInt()
        }.coerceAtMost(8)
        var s = String.format(Locale.ROOT, "%.${decimals}f", value)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    /** A safe file name: "<model>-<style>-<width>x<height>.png". */
    fun fileName(modelName: String, style: SnapshotStyle, width: Int, height: Int): String {
        val base = modelName.substringBeforeLast('.').ifBlank { "model" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.', '-').take(60).ifBlank { "model" }
        val tag = if (style == SnapshotStyle.TRANSPARENT) "transparent" else "framed"
        return "$base-$tag-${width}x$height.png"
    }
}
