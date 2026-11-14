package app.strata3d.render

import java.nio.ByteBuffer

/** Size limits of this device's GPU, queried once the GL context exists. */
data class GpuLimits(
    val maxRenderbufferSize: Int,
    val maxViewportWidth: Int,
    val maxViewportHeight: Int,
    val maxSamples: Int,
) {
    /** Largest off-screen image the GPU can render in one pass. */
    val maxWidth: Int get() = minOf(maxRenderbufferSize, maxViewportWidth)
    val maxHeight: Int get() = minOf(maxRenderbufferSize, maxViewportHeight)

    fun fits(width: Int, height: Int): Boolean = width in 1..maxWidth && height in 1..maxHeight
}

/**
 * An off-screen render of the current view.
 *
 * @param transparent clear to fully transparent and omit the grid (a real alpha-channel image).
 * @param background RGB clear colour when not transparent.
 * @param edgeScale edge-line width multiplier, normally the output scale relative to the screen.
 */
class SnapshotRequest(
    val width: Int,
    val height: Int,
    val transparent: Boolean,
    val background: FloatArray,
    val showGrid: Boolean,
    val edgeScale: Float,
)

/** Premultiplied RGBA pixels, bottom row first (OpenGL order). */
class SnapshotPixels(val width: Int, val height: Int, val rgba: ByteBuffer)

class SnapshotException(message: String) : Exception(message)
