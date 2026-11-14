package app.strata3d.capture.quality

/**
 * Focus and motion-blur score of a photo: the variance of the 4-neighbour Laplacian of the luma, evaluated on a
 * regular grid of sample points in the central 80 % of the frame. Sampling keeps it at ~0.25 M points regardless of
 * resolution (a few milliseconds), while each Laplacian still uses full-resolution neighbours, so it reacts to blur
 * at the photo's real resolution. Higher is sharper; values are comparable within one capture (same camera and
 * lighting), not across captures.
 */
object Sharpness {

    /** Target number of sample points along the longer side. */
    private const val GRID = 640

    /**
     * @param luma 8-bit luma plane (row-major, [rowStride] bytes per row), for example the Y part of an NV21 buffer.
     */
    fun laplacianVariance(luma: ByteArray, width: Int, height: Int, rowStride: Int = width, offset: Int = 0): Double {
        require(width >= 3 && height >= 3 && rowStride >= width && offset + (height - 1).toLong() * rowStride + width <= luma.size)
        val x0 = maxOf(1, width / 10); val x1 = minOf(width - 2, width - width / 10)
        val y0 = maxOf(1, height / 10); val y1 = minOf(height - 2, height - height / 10)
        val step = maxOf(1, maxOf(x1 - x0, y1 - y0) / GRID)
        var n = 0L; var sum = 0.0; var sumSq = 0.0
        var y = y0
        while (y <= y1) {
            val row = offset + y * rowStride
            var x = x0
            while (x <= x1) {
                val i = row + x
                val c = luma[i].toInt() and 0xFF
                val lap = (luma[i - 1].toInt() and 0xFF) + (luma[i + 1].toInt() and 0xFF) +
                    (luma[i - rowStride].toInt() and 0xFF) + (luma[i + rowStride].toInt() and 0xFF) - 4 * c
                sum += lap; sumSq += lap.toDouble() * lap; n++
                x += step
            }
            y += step
        }
        if (n == 0L) return 0.0
        val mean = sum / n
        return (sumSq / n - mean * mean).coerceAtLeast(0.0)
    }
}
