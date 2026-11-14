package app.strata3d.capture.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessTest {

    private fun checker(w: Int, h: Int, cell: Int) = ByteArray(w * h) { i -> if (((i % w) / cell + (i / w) / cell) % 2 == 0) 30 else 220.toByte() }

    private fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
                s += src[yy * w + xx].toInt() and 0xFF; n++
            }
            out[y * w + x] = (s / n).toByte()
        }
        return out
    }

    @Test fun sharpnessDropsWithBlurAndIgnoresFlatImages() {
        val w = 400; val h = 300
        val sharp = checker(w, h, 3)
        val s0 = Sharpness.laplacianVariance(sharp, w, h)
        val s1 = Sharpness.laplacianVariance(boxBlur(sharp, w, h, 1), w, h)
        val s2 = Sharpness.laplacianVariance(boxBlur(sharp, w, h, 3), w, h)
        assertTrue("$s0 > $s1 > $s2", s0 > s1 && s1 > s2)
        assertEquals(0.0, Sharpness.laplacianVariance(ByteArray(w * h) { 77 }, w, h), 1e-12)
    }

    @Test fun sharpnessHonoursRowStrideAndOffset() {
        val w = 200; val h = 100; val stride = 256; val offset = 37
        val packed = checker(w, h, 2)
        val padded = ByteArray(offset + stride * h)
        for (y in 0 until h) System.arraycopy(packed, y * w, padded, offset + y * stride, w)
        assertEquals(Sharpness.laplacianVariance(packed, w, h), Sharpness.laplacianVariance(padded, w, h, stride, offset), 1e-9)
    }
}
