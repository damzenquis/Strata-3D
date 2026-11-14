package app.strata3d.capture.shutter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.sqrt

/**
 * Reads the phone's orientation (game rotation vector: gyroscope + accelerometer, no compass) and rotation speed
 * (gyroscope) on its own thread, and reports each orientation reading to [listener] on that thread.
 *
 * [start] and [stop] are idempotent and may be called from the main thread. Sensors are registered only between them,
 * so nothing runs while the capture screen is paused or closed.
 */
class MotionSensors(
    context: Context,
    private val listener: (timestampNs: Long, orientation: DeviceOrientation, angularSpeedRadPerS: Double) -> Unit,
) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyro: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** True when the phone has both sensors the smart shutter needs. Without them, capture is manual only. */
    val available: Boolean get() = rotation != null && gyro != null

    /** Latest orientation (sensor thread writes, any thread reads), or null before the first reading. */
    @Volatile var latest: DeviceOrientation? = null
        private set

    @Volatile private var angularSpeed = 0.0
    private var thread: HandlerThread? = null
    private val quat = FloatArray(4)

    private val callback = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                    angularSpeed = sqrt((x * x + y * y + z * z).toDouble())
                }
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    SensorManager.getQuaternionFromVector(quat, e.values)   // (w, x, y, z)
                    val o = try {
                        DeviceOrientation.of(quat[1].toDouble(), quat[2].toDouble(), quat[3].toDouble(), quat[0].toDouble())
                    } catch (_: IllegalArgumentException) { return }
                    latest = o
                    listener(e.timestamp, o, angularSpeed)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @Synchronized fun start() {
        if (thread != null || !available) return
        val t = HandlerThread("strata-sensors").also { it.start() }
        val h = Handler(t.looper)
        manager.registerListener(callback, gyro, SensorManager.SENSOR_DELAY_GAME, h)
        manager.registerListener(callback, rotation, SensorManager.SENSOR_DELAY_GAME, h)
        thread = t
    }

    @Synchronized fun stop() {
        val t = thread ?: return
        manager.unregisterListener(callback)
        t.quitSafely()
        thread = null
    }
}
