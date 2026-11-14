package app.strata3d.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.strata3d.R
import app.strata3d.diagnostics.DiagnosticOverlay
import app.strata3d.diagnostics.DiagnosticsReport
import app.strata3d.diagnostics.MeshAnalyzer
import app.strata3d.diagnostics.MeshFingerprint
import app.strata3d.model.LengthUnit
import app.strata3d.model.Mesh
import app.strata3d.model.ModelScale
import app.strata3d.model.ModelParser
import app.strata3d.model.ModelResources
import app.strata3d.render.Projection
import app.strata3d.render.SectionAxis
import app.strata3d.render.RenderMode
import android.graphics.Bitmap
import app.strata3d.recent.RecentEntry
import app.strata3d.recent.RecentFilesStore
import app.strata3d.snapshot.SnapshotExporter
import app.strata3d.util.FileLocationHelper
import app.strata3d.util.FileLocationInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

enum class ModelColor(val rgb: FloatArray) {
    Blue(floatArrayOf(0.36f, 0.60f, 0.90f)),
    Orange(floatArrayOf(0.95f, 0.55f, 0.20f)),
    Grey(floatArrayOf(0.72f, 0.73f, 0.75f)),
    Green(floatArrayOf(0.35f, 0.75f, 0.45f)),
    Red(floatArrayOf(0.88f, 0.30f, 0.30f)),
}

data class LoadedModel(
    val mesh: Mesh,
    val fileName: String,
    /** Size in bytes, or -1 if unknown. */
    val fileSize: Long,
    val loadTimeMs: Long,
    val locationInfo: FileLocationInfo,
    /** Units the user chose for this file's coordinates, or null to use the file's own (or assumed mm). */
    val unitChoice: LengthUnit? = null,
    /** The Recent Files entry for this model (where the unit choice is remembered), once recorded. */
    val recentId: String? = null,
) {
    /** How coordinates convert to millimetres for every displayed length, area and volume. */
    val scale: ModelScale get() = ModelScale.resolve(mesh.unit, unitChoice)
    val fileLocation: String get() = locationInfo.displayPath
    val rawUri: String? get() = locationInfo.rawUri
}

/** A measurement point in model coordinates; [snapped] when it sits exactly on a mesh vertex. */
data class Point3D(val x: Float, val y: Float, val z: Float, val snapped: Boolean = false) {
    fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)
}

/** Distances between two points, in model coordinate units (convert with [ModelScale] for display). */
data class Measurement(
    val point1: Point3D,
    val point2: Point3D,
) {
    val deltaX: Float get() = kotlin.math.abs(point2.x - point1.x)
    val deltaY: Float get() = kotlin.math.abs(point2.y - point1.y)
    val deltaZ: Float get() = kotlin.math.abs(point2.z - point1.z)
    val distance: Float get() = kotlin.math.sqrt(
        (point2.x - point1.x) * (point2.x - point1.x) +
        (point2.y - point1.y) * (point2.y - point1.y) +
        (point2.z - point1.z) * (point2.z - point1.z)
    )
}

/** Cross-section controls: the plane sits at [fraction] of the model's extent along [axis]. */
data class SectionState(
    val axis: SectionAxis = SectionAxis.Z,
    val fraction: Float = 0.5f,
    val flipped: Boolean = false,
)

/** Where the on-demand mesh check stands for the current model. */
sealed interface DiagnosticsState {
    data object NotAnalyzed : DiagnosticsState
    data object Analyzing : DiagnosticsState
    /** [overlay] is ready for the 3D view (null when there is nothing to highlight). */
    class Done(val report: DiagnosticsReport, val overlay: DiagnosticOverlay?) : DiagnosticsState
    class Failed(val message: String) : DiagnosticsState
}

data class ViewerUiState(
    val model: LoadedModel? = null,
    val loading: Boolean = false,
    val loadingName: String? = null,
    val error: String? = null,
    val infoMessage: String? = null,
    val renderMode: RenderMode = RenderMode.SHADED,
    /** Photo colours shown for models that have them (on by default). */
    val photoColours: Boolean = true,
    val projection: Projection = Projection.PERSPECTIVE,
    val showGrid: Boolean = true,
    val modelColor: ModelColor = ModelColor.Blue,
    val isMeasuring: Boolean = false,
    val measurePoint1: Point3D? = null,
    val measurePoint2: Point3D? = null,
    val diagnostics: DiagnosticsState = DiagnosticsState.NotAnalyzed,
    /** Active cross-section, or null when the whole model is shown. */
    val section: SectionState? = null,
    /** Whether this device can fill section cuts (stencil buffer); assumed until the 3D view reports. */
    val stencilSupported: Boolean = true,
    /** Highlight the mesh check's findings in the 3D view. */
    val showDiagnostics: Boolean = false,
    /** Measurement taps snap to visible vertices near the finger. */
    val snapToVertices: Boolean = true,
    /** Recent Files history, most recent first. */
    val recentFiles: List<RecentEntry> = emptyList(),
    /** Set after a successful load: the UI captures a thumbnail for this recent entry. */
    val thumbnailRequest: String? = null,
    /** A recent entry that just failed to open; the UI offers to reopen it. */
    val recentUnavailableId: String? = null,
) {
    val measurement: Measurement?
        get() = if (measurePoint1 != null && measurePoint2 != null) {
            Measurement(measurePoint1, measurePoint2)
        } else null
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var diagnosticsJob: Job? = null

    /**
     * Recent mesh-check reports by geometry fingerprint, so reopening a file in the same session needs no new
     * analysis. A report is only used for geometry with the same fingerprint. Small, because reports keep a
     * per-corner topology map.
     */
    private val diagnosticsCache = object : LinkedHashMap<MeshFingerprint, DiagnosticsReport>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MeshFingerprint, DiagnosticsReport>?) = size > 2
    }

    private val debuggable =
        (application.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val recentStore = RecentFilesStore(application)

    init {
        // Remove temporary snapshot files left over from earlier sessions (off the main thread).
        viewModelScope.launch(Dispatchers.IO) { SnapshotExporter.cleanUp(application) }
        // The history is a small JSON file read off the main thread; thumbnails load lazily when shown.
        viewModelScope.launch { setRecents(recentStore.load()) }
    }

    /**
     * Applies a recent-files result to the state. Store calls always happen *before* [MutableStateFlow.update]:
     * update may re-run its block when the state changes concurrently, which must never repeat a side effect.
     */
    private fun setRecents(list: List<RecentEntry>) = _state.update { it.copy(recentFiles = list) }
    private val cacheModelFile by lazy {
        File(getApplication<Application>().cacheDir, "current_model.bin")
    }

    fun load(uri: Uri) = load(listOf(uri))

    /**
     * Loads a model. Several URIs may be given when the user picked a model together with the files it
     * references (for example `scene.gltf` + `scene.bin`); the model is the one with a model extension.
     */
    fun load(uris: List<Uri>) = load(uris, replacingRecentId = null, fromRecentId = null)

    /** Opens a Recent Files entry that is still accessible (no picker needed). */
    fun openRecent(entry: RecentEntry) {
        load(entry.file.allUris.map(Uri::parse), replacingRecentId = null, fromRecentId = entry.file.id)
    }

    /** The user re-selected the document(s) for an entry that needed reopening. */
    fun reopenRecent(entryId: String, uris: List<Uri>) {
        load(uris, replacingRecentId = entryId, fromRecentId = null)
    }

    fun removeRecent(id: String) {
        viewModelScope.launch { setRecents(recentStore.remove(id)) }
    }

    fun clearRecents() {
        viewModelScope.launch { setRecents(recentStore.clear()) }
    }

    fun saveThumbnail(id: String, bitmap: Bitmap) {
        viewModelScope.launch { setRecents(recentStore.saveThumbnail(id, bitmap)) }
    }

    fun consumeThumbnailRequest() = _state.update { it.copy(thumbnailRequest = null) }

    fun clearRecentUnavailable() = _state.update { it.copy(recentUnavailableId = null) }

    suspend fun loadThumbnail(entry: RecentEntry): Bitmap? = recentStore.loadThumbnail(entry.file)

    private class LoadOutcome(val model: LoadedModel, val uri: Uri, val companions: List<Pair<String, Uri>>)

    private fun load(uris: List<Uri>, replacingRecentId: String?, fromRecentId: String?) {
        if (uris.isEmpty()) return
        loadJob?.cancel()
        val resolver = getApplication<Application>().contentResolver
        _state.update {
            it.copy(
                loading = true,
                loadingName = null,
                error = null,
                infoMessage = null,
                isMeasuring = false,
                measurePoint1 = null,
                measurePoint2 = null,
                diagnostics = DiagnosticsState.NotAnalyzed,
                section = null,
            )
        }
        diagnosticsJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val named = uris.map { it to queryNameAndSize(resolver, it) }
                    val candidates = named.filter { (_, info) ->
                        ModelParser.isModelFileName(info.first) || info.first.endsWith(".zip", ignoreCase = true)
                    }
                    val (uri, nameAndSize) = when {
                        named.size == 1 -> named[0]
                        candidates.isEmpty() -> throw IOException("None of the selected files is a 3D model")
                        candidates.size > 1 -> throw IOException(
                            "Select one model at a time, plus any files it needs (such as its .bin)",
                        )
                        else -> candidates[0]
                    }
                    val (name, size) = nameAndSize
                    val companionList = named.filter { it.first != uri }.map { (u, info) -> info.first to u }
                    val companions = companionList.associate { (n, u) -> n.lowercase() to u }
                    val location = FileLocationHelper.resolve(getApplication(), uri)
                    _state.update { it.copy(loadingName = name) }

                    // Cache locally so downloading/saving is reliable even if transient URI permissions expire
                    resolver.openInputStream(uri)?.use { input ->
                        cacheModelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Unable to open the file")

                    val resources = ModelResources { relative ->
                        companions[ModelResources.fileName(relative).lowercase()]?.let { resolver.openInputStream(it) }
                            ?: siblingFile(uri, relative)?.inputStream()
                    }

                    val effectiveSize = if (size > 0) size else cacheModelFile.length()
                    val start = System.nanoTime()
                    val plain = cacheModelFile.inputStream().use { ModelParser.parse(it, name, effectiveSize, resources) }
                    // Photo colours for OBJ models that come with their colour images (like the models this app builds).
                    val mesh = if (name.endsWith(".obj", ignoreCase = true)) {
                        runCatching { app.strata3d.model.PhotoColours.load(cacheModelFile, resources, plain) }
                            .onFailure { android.util.Log.w("ViewerViewModel", "photo colours", it) }.getOrNull() ?: plain
                    } else plain
                    val loadTime = (System.nanoTime() - start) / 1_000_000
                    // Units chosen earlier for this document (read before the model is shown, so no flicker).
                    val unitChoice = recentStore.unitFor(uri, replacingRecentId)?.takeIf { it != mesh.unit }

                    LoadOutcome(
                        LoadedModel(
                            mesh = mesh,
                            fileName = name,
                            fileSize = effectiveSize,
                            loadTimeMs = loadTime,
                            locationInfo = location,
                            unitChoice = unitChoice,
                        ),
                        uri,
                        companionList,
                    )
                }
                val loaded = outcome.model
                _state.update { it.copy(model = loaded, loading = false, loadingName = null) }
                // Record in Recent Files (permissions persisted only where Android grants them), then ask the
                // UI for a thumbnail of what is now on screen.
                val entry = recentStore.record(
                    displayName = loaded.fileName,
                    uri = outcome.uri,
                    companions = outcome.companions,
                    sizeBytes = loaded.fileSize,
                    triangleCount = loaded.mesh.triangleCount,
                    replacingId = replacingRecentId,
                )
                // The user may already have changed the units while the entry was being recorded.
                val shown = _state.value.model?.takeIf { it.mesh === loaded.mesh }
                val choice = if (shown != null) shown.unitChoice else loaded.unitChoice
                if (entry.unit != choice) recentStore.setUnit(entry.id, choice)
                val recents = recentStore.load()
                _state.update { s ->
                    s.copy(
                        recentFiles = recents,
                        thumbnailRequest = entry.id,
                        // Only if the same model is still shown (another load may have replaced it meanwhile).
                        model = s.model?.let { m -> if (m.mesh === loaded.mesh) m.copy(recentId = entry.id) else m },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                fail("This model is too large to load on this device")
            } catch (e: SecurityException) {
                if (fromRecentId != null) recentUnavailable(fromRecentId) else fail("Permission denied while opening the file")
            } catch (e: FileNotFoundException) {
                if (fromRecentId != null) recentUnavailable(fromRecentId) else fail(e.message ?: "Could not open the file")
            } catch (e: Exception) {
                fail(e.message ?: "Could not open the file")
            }
        }
    }

    /** For plain file:// models (rare), companion files can be read straight from the same folder. */
    private fun siblingFile(model: Uri, relative: String): File? {
        if (model.scheme != ContentResolver.SCHEME_FILE) return null
        val parent = model.path?.let { File(it).parentFile } ?: return null
        return File(parent, ModelResources.normalize(relative)).takeIf { it.isFile }
    }

    fun downloadModel(destinationUri: Uri) {
        val currentModel = _state.value.model ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    if (!cacheModelFile.exists()) {
                        throw IOException("Model file is no longer in cache")
                    }
                    cacheModelFile.inputStream().use { input ->
                        resolver.openOutputStream(destinationUri)?.use { output ->
                            input.copyTo(output)
                        } ?: throw IOException("Unable to open destination file for writing")
                    }
                }
                _state.update {
                    it.copy(infoMessage = getApplication<Application>().getString(R.string.file_saved, currentModel.fileName))
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = getApplication<Application>().getString(R.string.error_saving, e.message ?: "Unknown error"))
                }
            }
        }
    }

    /** A recent document could not be opened (moved, deleted or access revoked): offer Reopen instead. */
    private suspend fun recentUnavailable(id: String) {
        val list = recentStore.markUnavailable(id)
        _state.update {
            it.copy(
                loading = false,
                loadingName = null,
                recentFiles = list,
                recentUnavailableId = id,
            )
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, loadingName = null, error = message) }
    }

    fun reportError(message: String) = fail(message)

    fun clearError() = _state.update { it.copy(error = null) }

    fun clearInfoMessage() = _state.update { it.copy(infoMessage = null) }

    fun setRenderMode(mode: RenderMode) = _state.update { it.copy(renderMode = mode) }

    fun togglePhotoColours() = _state.update { it.copy(photoColours = !it.photoColours) }

    fun toggleGrid() = _state.update { it.copy(showGrid = !it.showGrid) }

    fun toggleProjection() = _state.update {
        it.copy(projection = if (it.projection == Projection.PERSPECTIVE) Projection.ORTHOGRAPHIC else Projection.PERSPECTIVE)
    }

    fun setModelColor(color: ModelColor) = _state.update { it.copy(modelColor = color) }

    fun toggleMeasureMode() {
        _state.update {
            val next = !it.isMeasuring
            it.copy(
                isMeasuring = next,
                measurePoint1 = if (next) it.measurePoint1 else null,
                measurePoint2 = if (next) it.measurePoint2 else null,
            )
        }
    }

    fun onMeasurePointPicked(x: Float, y: Float, z: Float, snapped: Boolean = false) {
        val newPoint = Point3D(x, y, z, snapped)
        _state.update { current ->
            when {
                current.measurePoint1 == null -> current.copy(measurePoint1 = newPoint)
                current.measurePoint2 == null -> current.copy(measurePoint2 = newPoint)
                else -> current.copy(measurePoint1 = newPoint, measurePoint2 = null)
            }
        }
    }

    /** Runs the mesh check for the current model in the background (cancellable; cached per geometry). */
    fun analyzeMesh() = analyzeMesh(auto = false)

    private fun analyzeMesh(auto: Boolean) {
        autoCheckRunning = auto
        val mesh = _state.value.model?.mesh ?: return
        diagnosticsJob?.cancel()
        _state.update { it.copy(diagnostics = DiagnosticsState.Analyzing) }
        diagnosticsJob = viewModelScope.launch {
            val result: DiagnosticsState = try {
                withContext(Dispatchers.Default) {
                    val job = coroutineContext[Job]!!
                    val fingerprint = MeshFingerprint.of(mesh)
                    val cached = synchronized(diagnosticsCache) { diagnosticsCache[fingerprint] }
                    val report = cached ?: measureIfDebug(mesh) {
                        MeshAnalyzer.analyze(mesh, fingerprint) { !job.isActive }
                    }.also { synchronized(diagnosticsCache) { diagnosticsCache[fingerprint] = it } }
                    val overlay = DiagnosticOverlay.build(mesh, report).takeUnless { it.isEmpty }
                    DiagnosticsState.Done(report, overlay)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                DiagnosticsState.Failed(getApplication<Application>().getString(R.string.diag_out_of_memory))
            } catch (e: Exception) {
                DiagnosticsState.Failed(e.message ?: e.javaClass.simpleName)
            }
            autoCheckRunning = false
            // Apply only if the same geometry is still shown (a new model resets the check).
            _state.update { s -> if (s.model?.mesh === mesh) s.copy(diagnostics = result) else s }
        }
    }

    fun cancelMeshAnalysis() {
        autoCheckRunning = false
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        _state.update { it.copy(diagnostics = DiagnosticsState.NotAnalyzed) }
    }

    fun toggleDiagnosticsOverlay() = _state.update { it.copy(showDiagnostics = !it.showDiagnostics) }

    /** Debug builds log time and Java-heap use of each analysis (tag StrataDiag) for on-device benchmarks. */
    private fun measureIfDebug(mesh: Mesh, block: () -> DiagnosticsReport): DiagnosticsReport {
        if (!debuggable) return block()
        val rt = Runtime.getRuntime()
        fun used() = rt.totalMemory() - rt.freeMemory()
        val before = used()
        val peak = java.util.concurrent.atomic.AtomicLong(before)
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread({ while (sampling.get()) { peak.accumulateAndGet(used(), ::maxOf); Thread.sleep(5) } }, "DiagSampler")
        sampler.isDaemon = true
        sampler.start()
        try {
            val report = block()
            android.util.Log.d(
                "StrataDiag",
                "analyzed ${mesh.triangleCount} tris in ${report.elapsedMs} ms; heap peak +${(peak.get() - before) shr 20} MB " +
                    "(max ${rt.maxMemory() shr 20} MB); device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; " +
                    report.findings.joinToString { "${it.type}=${it.count}" }.ifEmpty { "no issues" },
            )
            return report
        } finally {
            sampling.set(false)
        }
    }

    /** True while a mesh check that Section started itself is running (closing Section cancels it). */
    private var autoCheckRunning = false

    /**
     * Opening Section also checks the mesh in the background when that has not been done yet, so the cut can be
     * filled once the geometry is confirmed closed. Closing Section cancels a check it started that is still running.
     */
    fun toggleSection() {
        val opening = _state.value.section == null
        _state.update { it.copy(section = if (opening) SectionState() else null) }
        val d = _state.value.diagnostics
        if (opening && (d == DiagnosticsState.NotAnalyzed || d is DiagnosticsState.Failed)) {
            analyzeMesh(auto = true)
        } else if (!opening && autoCheckRunning) {
            cancelMeshAnalysis()
        }
    }

    fun setStencilSupported(supported: Boolean) = _state.update { it.copy(stencilSupported = supported) }

    /** A new axis starts in the middle of the model's extent along it. */
    fun setSectionAxis(axis: SectionAxis) =
        _state.update { s -> s.section?.let { if (it.axis == axis) s else s.copy(section = it.copy(axis = axis, fraction = 0.5f)) } ?: s }

    fun setSectionFraction(fraction: Float) =
        _state.update { s -> s.section?.let { s.copy(section = it.copy(fraction = fraction.coerceIn(0f, 1f))) } ?: s }

    fun flipSection() = _state.update { s -> s.section?.let { s.copy(section = it.copy(flipped = !it.flipped)) } ?: s }

    fun toggleSnap() = _state.update { it.copy(snapToVertices = !it.snapToVertices) }

    /**
     * Sets how the current model's coordinates are interpreted (null = the file's own unit, or assumed mm) and
     * remembers it for this file. Only the interpretation changes: the geometry and the picked points stay as
     * they are, so the displayed values are converted at once.
     */
    fun setUnitChoice(unit: LengthUnit?) {
        val model = _state.value.model ?: return
        // Choosing the unit the file itself declares is the same as having no override.
        val choice = unit?.takeIf { it != model.mesh.unit }
        _state.update { s -> s.model?.let { s.copy(model = it.copy(unitChoice = choice)) } ?: s }
        val id = model.recentId ?: return
        viewModelScope.launch { setRecents(recentStore.setUnit(id, choice)) }
    }

    fun clearMeasurement() {
        _state.update { it.copy(measurePoint1 = null, measurePoint2 = null) }
    }

    fun closeModel() {
        loadJob?.cancel()
        diagnosticsJob?.cancel()
        if (cacheModelFile.exists()) cacheModelFile.delete()
        _state.update {
            it.copy(
                model = null,
                diagnostics = DiagnosticsState.NotAnalyzed,
                section = null,
                isMeasuring = false,
                measurePoint1 = null,
                measurePoint2 = null,
            )
        }
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { path ->
                val file = File(path)
                name = file.name
                size = file.length()
            }
        } else {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        }
                    }
            }
            if (size < 0) {
                size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            }
        }
        return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model") to size
    }
}
