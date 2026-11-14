package app.strata3d.capture.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.strata3d.capture.format.CaptureExport
import app.strata3d.capture.format.CaptureStore
import app.strata3d.capture.format.StoredCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Captures on this phone: listing, thumbnails, rename, delete and export. */
class CaptureLibraryViewModel(app: Application) : AndroidViewModel(app) {

    val store = CaptureStore(File(app.filesDir, CaptureStore.DIR))

    data class ExportProgress(val captureId: String, val percent: Int)

    private val _captures = MutableStateFlow<List<StoredCapture>?>(null)
    val captures: StateFlow<List<StoredCapture>?> = _captures.asStateFlow()

    private val _export = MutableStateFlow<ExportProgress?>(null)
    val export: StateFlow<ExportProgress?> = _export.asStateFlow()

    /** One-off messages for a snackbar (string resource ids). */
    private val _messages = MutableStateFlow<Int?>(null)
    val messages: StateFlow<Int?> = _messages.asStateFlow()
    fun messageShown() { _messages.value = null }

    private val thumbnails = HashMap<String, Bitmap?>()
    private var exportJob: Job? = null

    /** Lists captures; [activeId] is a capture still being recorded (not repaired as interrupted). */
    fun refresh(activeId: String? = null) {
        viewModelScope.launch {
            _captures.value = withContext(Dispatchers.IO) { store.list(activeId) }
        }
    }

    fun find(id: String): StoredCapture? = _captures.value?.firstOrNull { it.id == id }

    // ---- on-device reconstruction (ReconService, own process) ----

    /** The capture's last reconstruction status, or null if it was never built. Blocking: call off the main thread. */
    fun reconStatus(id: String): app.strata3d.capture.recon.ReconStatus? =
        app.strata3d.capture.recon.ReconRunner.readStatus(app.strata3d.capture.recon.ReconService.workRoot(getApplication()), id)

    /**
     * True while the reconstruction service runs (a status that says "running" without it was interrupted). Not the
     * process: Android keeps the ":recon" process cached after the service stops.
     */
    fun reconServiceRunning(): Boolean {
        val am = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION") // still returns the caller's own services, which is all this needs
        return am.getRunningServices(Int.MAX_VALUE).orEmpty()
            .any { it.service.className == app.strata3d.capture.recon.ReconService::class.java.name && it.started }
    }

    fun startBuild(id: String, review: Boolean = false) = app.strata3d.capture.recon.ReconService.start(getApplication(), id, review)

    /** Continues a build paused for review, with the box saved by the review screen. */
    fun resumeBuild(id: String) = app.strata3d.capture.recon.ReconService.resume(getApplication(), id)

    /** Ends a build paused for review without building (deletes the kept scene). */
    fun abandonReview(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.strata3d.capture.recon.ReconRunner.abandonReview(app.strata3d.capture.recon.ReconService.workRoot(getApplication()), id)
            app.strata3d.capture.recon.ReconService.dismissReviewNotification(getApplication())
        }
    }

    /** The kept scene of [id]'s build paused for review. */
    fun reviewScene(id: String) = app.strata3d.capture.recon.ReconRunner.sceneDir(app.strata3d.capture.recon.ReconService.workRoot(getApplication()), id)
    /** The capture's real-world size (request and, once applied, the model's dimensions). */
    fun size(id: String): app.strata3d.capture.recon.ModelSize.Record? =
        find(id)?.let { app.strata3d.capture.recon.SizeStore.read(it.dir) }

    /**
     * Saves the user's size for a capture and, when [model] (the built model.obj) is given, scales it now. Runs on
     * the caller's IO dispatcher. Returns the stored record (with dimensions when a model was sized).
     */
    fun saveSize(id: String, request: app.strata3d.capture.recon.ModelSize.Request, model: File?): app.strata3d.capture.recon.ModelSize.Record? {
        val dir = find(id)?.dir ?: return null
        val store = app.strata3d.capture.recon.SizeStore
        store.write(dir, app.strata3d.capture.recon.ModelSize.Record(request))
        return if (model != null && model.isFile) store.apply(dir, model) else store.read(dir)
    }

    fun cancelBuild() = app.strata3d.capture.recon.ReconService.cancel(getApplication())

    /** A small thumbnail of the capture's first photo (decoded once, sub-sampled). */
    suspend fun thumbnail(capture: StoredCapture): Bitmap? {
        synchronized(thumbnails) { if (thumbnails.containsKey(capture.id)) return thumbnails[capture.id] }
        val bmp = withContext(Dispatchers.IO) {
            val first = File(capture.dir, CaptureStore.imageName(1))
            if (!first.isFile) return@withContext null
            try {
                BitmapFactory.decodeFile(first.path, BitmapFactory.Options().apply { inSampleSize = 32 })
            } catch (e: OutOfMemoryError) { null }
        }
        synchronized(thumbnails) { thumbnails[capture.id] = bmp }
        return bmp
    }

    fun rename(capture: StoredCapture, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.rename(capture.dir, name) }
            refresh()
        }
    }

    fun delete(capture: StoredCapture) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.delete(capture.dir)
                // Its reconstruction status, logs and any scene kept for review go with it.
                java.io.File(app.strata3d.capture.recon.ReconService.workRoot(getApplication()), capture.id).deleteRecursively()
            }
            synchronized(thumbnails) { thumbnails.remove(capture.id) }
            refresh()
        }
    }

    /** Writes the capture as a .zip to [uri] (chosen by the user with the system file picker). */
    fun export(capture: StoredCapture, uri: Uri, okMessage: Int, failMessage: Int) {
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _export.value = ExportProgress(capture.id, 0)
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?: return@withContext false
                    out.use {
                        CaptureExport.writeZip(store, capture.dir, it, onProgress = { done, total ->
                            val pct = if (total > 0) (done * 100 / total).toInt() else 100
                            _export.update { p -> if (p != null && p.percent != pct) p.copy(percent = pct) else p }
                        }, isCancelled = { !isActive })
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            _export.value = null
            _messages.value = if (ok) okMessage else failMessage
        }
    }

    /**
     * What this phone offers for capture: motion sensors and the back cameras' lens data. Lets us check a new phone
     * (for example the Galaxy A36) without a special build.
     */
    suspend fun deviceReport(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("Strata:3D capture device report")
            val profile = app.strata3d.capture.DeviceProfile.read(context)
            profile.facts().forEach { appendLine(it) }
            appendLine("SoC: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}, Android ${Build.VERSION.RELEASE}")
            appendLine("What the app does on this phone:")
            profile.decisions().forEach { appendLine("  • $it") }
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = try { cm.getCameraCharacteristics(id) } catch (e: Exception) { continue }
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width * it.height }?.take(4)
                appendLine("Camera $id: level ${c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}, " +
                    "focal ${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()} mm, " +
                    "sensor ${c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} mm, " +
                    "calibration ${if (c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null) "published" else "none"}, " +
                    "JPEG ${jpeg?.joinToString()}")
            }
            val mode = context.getSharedPreferences(app.strata3d.capture.CameraXCapture.PREFS, Context.MODE_PRIVATE)
                .getString(app.strata3d.capture.CameraXCapture.KEY_MODE, null) ?: "SMART"
            appendLine("Shutter setting: $mode")
            val caps = _captures.value.orEmpty()
            caps.firstOrNull()?.let { last ->
                appendLine("Last capture: format ${last.manifest.version}, ${last.manifest.camera.imageWidth}x${last.manifest.camera.imageHeight}, ${last.manifest.frameCount} photos, intrinsics ${last.manifest.camera.intrinsicsSource.id}")
            }
        }
    }
}
