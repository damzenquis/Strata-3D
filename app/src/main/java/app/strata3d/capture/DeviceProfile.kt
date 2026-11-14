package app.strata3d.capture

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.util.Locale

/**
 * What this phone offers, read once, and every decision the app takes from it (device-agnostic: nothing is tuned to
 * one model of phone). The facts come from [read]; the decisions are plain functions of them, unit-tested, so capture,
 * reconstruction and the device report all apply the same rules.
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    /** Android's own low-RAM flag (Android Go and similar). */
    val lowRamFlag: Boolean,
    val cores: Int,
    val abis: List<String>,
    val rotationVector: Boolean,
    val gyroscope: Boolean,
    val freeStorageMb: Long,
) {
    /** Low-memory phone: Android says so, or under about 2.8 GB of RAM. Capture then takes smaller, fewer photos. */
    val lowMemory: Boolean get() = lowRamFlag || totalRamMb < LOW_MEMORY_TOTAL_MB

    /** The smart shutter needs both motion sensors; without them capture is manual only. */
    val smartShutter: Boolean get() = rotationVector && gyroscope

    /** The reconstruction programs are 64-bit ARM; other processors can capture but not build models. */
    val canBuildModels: Boolean get() = ARM64 in abis

    /** Largest photo capture asks for: 12 MP, or 8 MP on a low-memory phone. */
    val maxPhotoSize: Pair<Int, Int> get() = if (lowMemory) 3264 to 2448 else 4096 to 3072

    /** JPEG quality of captured photos. */
    val jpegQuality: Int get() = if (lowMemory) 90 else 92

    /** Shortest time between smart-shutter photos (ms): longer on a low-memory phone, which saves more slowly. */
    val minShutterIntervalMs: Long get() = if (lowMemory) 700 else 400

    /** The decisions, in plain words, for the device report. */
    fun decisions(photos: Int = 50): List<String> {
        val recon = app.strata3d.capture.recon.ReconPlan.choose(photos, availableRamMb, cores)
        return listOf(
            "Photos: up to ${maxPhotoSize.first}x${maxPhotoSize.second}, JPEG quality $jpegQuality" + if (lowMemory) " (low-memory phone)" else "",
            "Smart shutter: " + if (smartShutter) "available, at most one photo every $minShutterIntervalMs ms" else "unavailable (motion sensors missing), manual only",
            "Building models: " + if (canBuildModels) "available, ${recon.threads} threads" else "unavailable (needs a 64-bit ARM processor)",
            "Placing $photos photos now: ${recon.reason}",
        )
    }

    /** The facts, in plain words. */
    fun facts(): List<String> = listOf(
        "Device: $manufacturer $model, API $sdk",
        String.format(Locale.ROOT, "RAM: %.1f GB, %d MB free now%s; CPU cores: %d; ABIs: %s", totalRamMb / 1024.0, availableRamMb, if (lowRamFlag) " (low-RAM device)" else "", cores, abis.joinToString()),
        "Motion sensors: rotation vector ${if (rotationVector) "yes" else "no"}, gyroscope ${if (gyroscope) "yes" else "no"}",
        "Free storage: $freeStorageMb MB",
    )

    companion object {
        const val ARM64 = "arm64-v8a"
        const val LOW_MEMORY_TOTAL_MB = 2867L   // about 2.8 GB

        /** Reads the facts now (memory and storage change; read again when they matter). */
        fun read(context: Context): DeviceProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val storage = try { StatFs(File(context.filesDir.path).path).availableBytes } catch (e: IllegalArgumentException) { 0L }
            return DeviceProfile(
                manufacturer = Build.MANUFACTURER, model = Build.MODEL, sdk = Build.VERSION.SDK_INT,
                totalRamMb = mem.totalMem / MB, availableRamMb = mem.availMem / MB, lowRamFlag = am.isLowRamDevice,
                cores = Runtime.getRuntime().availableProcessors(), abis = Build.SUPPORTED_ABIS.toList(),
                rotationVector = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null,
                gyroscope = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
                freeStorageMb = storage / MB,
            )
        }

        private const val MB = 1024L * 1024
    }
}
