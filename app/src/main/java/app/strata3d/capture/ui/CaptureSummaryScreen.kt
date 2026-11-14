package app.strata3d.capture.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.strata3d.R
import app.strata3d.capture.CameraXCapture
import app.strata3d.capture.recon.ModelSize
import app.strata3d.capture.recon.ReconStatus
import app.strata3d.capture.shutter.CoverageRings
import app.strata3d.capture.shutter.DeviceOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext

/** After a capture (or when a capture is opened from the library): what was captured, rename, export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSummaryScreen(viewModel: CaptureLibraryViewModel, captureId: String?, onDone: () -> Unit, onCheckBox: (String) -> Unit) {
    val captures by viewModel.captures.collectAsState()
    val export by viewModel.export.collectAsState()
    val message by viewModel.messages.collectAsState()
    val capture = captures?.firstOrNull { it.id == captureId }
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf(false) }
    val exporter = rememberCaptureExporter(viewModel)

    // Reconstruction status, read from the status file (written by the reconstruction process): every second while
    // building, otherwise every few seconds.
    var recon by remember { mutableStateOf<ReconStatus?>(null) }
    var reconAlive by remember { mutableStateOf(false) }
    LaunchedEffect(captureId) {
        val id = captureId ?: return@LaunchedEffect
        while (true) {
            val (s, alive) = withContext(Dispatchers.IO) { viewModel.reconStatus(id) to viewModel.reconServiceRunning() }
            recon = s; reconAlive = alive
            kotlinx.coroutines.delay(if (s?.state == ReconStatus.State.RUNNING || alive) 1000 else 3000)
        }
    }
    // Android 13+: ask once for notification permission when building starts; building works either way.
    val prefs = remember { context.getSharedPreferences(CameraXCapture.PREFS, android.content.Context.MODE_PRIVATE) }
    var pendingBuild by remember { mutableStateOf<String?>(null) }
    // "Review the box before building": off every time the summary opens (each object differs).
    var review by remember(captureId) { mutableStateOf(false) }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { _ -> pendingBuild?.let { viewModel.startBuild(it, review) }; pendingBuild = null }
    val build: (String) -> Unit = { id ->
        val needsAsk = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        if (needsAsk) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
            pendingBuild = id
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startBuild(id, review)
        }
    }

    // Real size: re-read when a build finishes (it applies the stored size) and after the user changes it.
    var size by remember { mutableStateOf<ModelSize.Record?>(null) }
    var sizing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // The model's picture for the card; drawn now for models built before pictures existed.
    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, captureId, recon?.state, recon?.updatedAt, capture != null) {
        val dir = capture?.let { java.io.File(it.dir, "model") }
        value = if (dir == null || recon?.state != ReconStatus.State.DONE) null else withContext(Dispatchers.IO) {
            val png = java.io.File(dir, app.strata3d.capture.recon.ModelPreview.FILE)
            if (!png.isFile && java.io.File(dir, "model.obj").isFile) {
                runCatching { app.strata3d.capture.recon.ModelPreview.write(dir) }.onFailure { android.util.Log.e("CaptureSummary", "preview", it) }
            }
            android.graphics.BitmapFactory.decodeFile(png.path)?.asImageBitmap()
        }
    }
    var applyingSize by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(captureId, recon?.state, recon?.updatedAt, capture != null) {
        val id = captureId ?: return@LaunchedEffect
        if (!applyingSize) size = withContext(Dispatchers.IO) { viewModel.size(id) }
    }

    LaunchedEffect(captureId) { if (capture == null) viewModel.refresh() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(resources.getString(it)); viewModel.messageShown() } }
    BackHandler(onBack = onDone)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(capture?.let { captureTitle(it) } ?: stringResource(R.string.capture_saved)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.capture_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (capture == null) return@Scaffold
        val m = capture.manifest
        // Scrolls: the Model ready card (picture, size, export, review switch) can be taller than the screen.
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(stringResource(R.string.capture_summary_photos), m.frameCount.toString(), Modifier.weight(1f))
                Metric(stringResource(R.string.capture_summary_covered), "${(m.coverage * 100).toInt()}%", Modifier.weight(1f))
                Metric(stringResource(R.string.capture_summary_size), Formatter.formatShortFileSize(context, capture.sizeBytes), Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Coverage rings from the phone orientation stored with each photo (version 2 captures). Version 1
                // captures (ARCore) have none: only the stored percentage is shown.
                val cells by produceState<List<List<Boolean>>?>(null, capture.id) {
                    value = withContext(Dispatchers.IO) {
                        val rings = CoverageRings(CoverageRings.Settings(cells = CameraXCapture.CELLS))
                        val frames = viewModel.store.readFrames(capture.dir)
                        frames.forEach { f -> f.orientation?.let { o -> rings.add(DeviceOrientation.of(o.x, o.y, o.z, o.w)) } }
                        if (frames.none { it.orientation != null }) null
                        else listOf(rings.cells(CoverageRings.Ring.LOWER), rings.cells(CoverageRings.Ring.UPPER))
                    }
                }
                cells?.let { CoverageDome(it, m.coverage, Modifier.size(96.dp)) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cells?.let { Text(missingText(it), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            val running = recon?.state == ReconStatus.State.RUNNING
            BuildModelCard(
                status = recon,
                interrupted = running && !reconAlive,
                buildingElsewhere = reconAlive && !running,
                review = review,
                onReviewChange = { review = it },
                onBuild = { build(capture.id) },
                onCancel = {
                    if (recon?.state == ReconStatus.State.REVIEW) viewModel.abandonReview(capture.id) else viewModel.cancelBuild()
                },
                onCheckBox = { onCheckBox(capture.id) },
                size = size,
                onSize = { if (!applyingSize) sizing = true },
                preview = preview,
                onExport = { exporting = true },
                onOpen = {
                    val model = java.io.File(capture.dir, recon?.model ?: return@BuildModelCard)
                    context.startActivity(
                        android.content.Intent(context, app.strata3d.MainActivity::class.java)
                            .putExtra(app.strata3d.MainActivity.EXTRA_MODEL_PATH, model.path)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                },
            )
            export?.let { e ->
                if (e.captureId == capture.id) {
                    Text(stringResource(R.string.capture_exporting, e.percent), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { e.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.capture_rename)) }
                OutlinedButton(onClick = { exporter(capture) }, enabled = export == null) { Text(stringResource(R.string.capture_export)) }
                Button(onClick = onDone) { Text(stringResource(R.string.capture_done)) }
            }
        }
    }
    if (exporting && capture != null) {
        val base = captureTitle(capture).replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "model" }
        ExportSheet(java.io.File(capture.dir, "model"), base, sized = size?.dimensionsMm != null, onDismiss = { exporting = false })
    }
    if (sizing && capture != null) {
        SizeSheet(size?.request, onSave = { r ->
            sizing = false; applyingSize = true
            val model = recon?.takeIf { it.state == ReconStatus.State.DONE }?.model?.let { java.io.File(capture.dir, it) }
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { viewModel.saveSize(capture.id, r, model) } }
                result.onSuccess { size = it }.onFailure {
                    android.util.Log.e("CaptureSummary", "sizing", it)
                    size = withContext(Dispatchers.IO) { viewModel.size(capture.id) }
                    snackbar.showSnackbar(resources.getString(R.string.size_failed))
                }
                applyingSize = false
            }
        }, onDismiss = { sizing = false })
    }
    if (renaming && capture != null) {
        RenameDialog(capture, onDismiss = { renaming = false }, onSave = { viewModel.rename(capture, it); renaming = false })
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun missingText(cells: List<List<Boolean>>): String {
    // Report the ring with the largest share of empty cells.
    val worst = cells.indices.maxByOrNull { r -> cells[r].count { !it }.toDouble() / cells[r].size.coerceAtLeast(1) }
    val emptyShare = worst?.let { r -> cells[r].count { !it }.toDouble() / cells[r].size.coerceAtLeast(1) } ?: 0.0
    return when {
        worst == null || emptyShare == 0.0 -> stringResource(R.string.capture_summary_complete)
        worst == 0 -> stringResource(R.string.capture_summary_missing_low)
        else -> stringResource(R.string.capture_summary_missing_mid)
    }
}

private const val KEY_ASKED_NOTIFICATIONS = "askedNotifications"
