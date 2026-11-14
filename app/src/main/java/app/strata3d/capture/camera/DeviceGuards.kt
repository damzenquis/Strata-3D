package app.strata3d.capture.camera

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import java.io.File
import java.util.concurrent.Executor

/**
 * Keeps capture within the phone's limits: pauses on high temperature and stops when storage runs low.
 * Thermal status needs Android 10; older phones report [hot] = false.
 */
class DeviceGuards(context: Context, private val storageDir: File, private val onChange: () -> Unit) {

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var listener: Any? = null

    @Volatile var hot: Boolean = false
        private set

    /** Warm but not yet hot (thermal status MODERATE): capture slows its shutter to let the phone cool. */
    @Volatile var warm: Boolean = false
        private set

    fun start(executor: Executor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || listener != null) return
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            val nowHot = status >= PowerManager.THERMAL_STATUS_SEVERE
            warm = status >= PowerManager.THERMAL_STATUS_MODERATE
            if (nowHot != hot) { hot = nowHot; onChange() }
        }
        power.addThermalStatusListener(executor, l)
        listener = l
        val status = power.currentThermalStatus
        hot = status >= PowerManager.THERMAL_STATUS_SEVERE
        warm = status >= PowerManager.THERMAL_STATUS_MODERATE
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (listener as? PowerManager.OnThermalStatusChangedListener)?.let { power.removeThermalStatusListener(it) }
        }
        listener = null
    }

    fun freeBytes(): Long = try {
        storageDir.mkdirs()
        StatFs(storageDir.path).availableBytes
    } catch (e: IllegalArgumentException) {
        0L
    }

    companion object {
        /** Free space needed to start a capture. */
        const val MIN_FREE_TO_START = 500L * 1024 * 1024
        /** Recording stops (and keeps what it has) below this. */
        const val MIN_FREE_TO_CONTINUE = 200L * 1024 * 1024
    }
}
