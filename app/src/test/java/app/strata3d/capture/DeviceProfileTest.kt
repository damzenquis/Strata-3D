package app.strata3d.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    private fun phone(totalRamMb: Long = 6000, lowRamFlag: Boolean = false, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
                      gyro: Boolean = true, rotation: Boolean = true, availableMb: Long = 1100, cores: Int = 8) =
        DeviceProfile("Samsung", "SM-A175F", 36, totalRamMb, availableMb, lowRamFlag, cores, abis, rotation, gyro, 50_000)

    @Test fun `a mid-range phone gets full-size photos and everything on`() {
        val p = phone()   // like the Galaxy A17
        assertFalse(p.lowMemory)
        assertEquals(4096 to 3072, p.maxPhotoSize)
        assertEquals(92, p.jpegQuality)
        assertEquals(400L, p.minShutterIntervalMs)
        assertTrue(p.smartShutter)
        assertTrue(p.canBuildModels)
    }

    @Test fun `low memory by Android's flag or by size`() {
        assertTrue(phone(lowRamFlag = true).lowMemory)
        assertTrue(phone(totalRamMb = 2800).lowMemory)
        assertFalse(phone(totalRamMb = 2900).lowMemory)
        val low = phone(totalRamMb = 2048)
        assertEquals(3264 to 2448, low.maxPhotoSize)
        assertEquals(90, low.jpegQuality)
        assertEquals(700L, low.minShutterIntervalMs)
    }

    @Test fun `smart shutter needs both motion sensors, building needs 64-bit ARM`() {
        assertFalse(phone(gyro = false).smartShutter)
        assertFalse(phone(rotation = false).smartShutter)
        assertFalse(phone(abis = listOf("x86_64")).canBuildModels)
        assertFalse(phone(abis = listOf("armeabi-v7a")).canBuildModels)
    }

    @Test fun `the report states each decision`() {
        val d = phone().decisions(58)
        assertTrue(d[0].startsWith("Photos: up to 4096x3072"))
        assertTrue(d[1].startsWith("Smart shutter: available"))
        assertTrue(d[2], d[2].startsWith("Building models: available, 4 threads"))
        assertTrue(d[3].startsWith("Placing 58 photos now: standard: 1100 MB free"))
        assertTrue(phone(abis = listOf("x86_64")).decisions()[2].contains("needs a 64-bit ARM processor"))
    }
}
