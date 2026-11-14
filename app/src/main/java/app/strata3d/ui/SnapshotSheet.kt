package app.strata3d.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.strata3d.R
import app.strata3d.render.ModelSurfaceView
import app.strata3d.render.SnapshotException
import app.strata3d.render.SnapshotPixels
import app.strata3d.render.SnapshotRequest
import app.strata3d.snapshot.SnapshotExporter
import app.strata3d.snapshot.SnapshotPlan
import app.strata3d.snapshot.SnapshotPlanning
import app.strata3d.snapshot.SnapshotSize
import app.strata3d.snapshot.SnapshotStyle
import app.strata3d.ui.theme.BrandCyan
import app.strata3d.ui.theme.OnBrandCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

private const val PREVIEW_LONG_EDGE = 640

/** Snapshot & Share sheet: transparent or framed PNG at Screen, 2× or 4K, shared or saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotSheet(
    controller: ViewportController,
    model: LoadedModel,
    background: FloatArray,
    defaultGrid: Boolean,
    isLandscape: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val view = controller.view

    var style by rememberSaveable { mutableStateOf(SnapshotStyle.TRANSPARENT) }
    var size by rememberSaveable { mutableStateOf(SnapshotSize.DOUBLE) }
    var includeGrid by rememberSaveable { mutableStateOf(defaultGrid) }
    var includeCaption by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var pendingSave by remember { mutableStateOf<File?>(null) }

    val base = view?.visibleSizePx() ?: intArrayOf(1, 1)
    val limits = view?.gpuLimits
    val plans = SnapshotSize.entries.associateWith {
        SnapshotPlanning.plan(it, base[0], base[1], style, includeCaption, limits)
    }
    val selected = plans.getValue(size)

    val captionTitle = model.fileName
    val captionSubtitle = SnapshotPlanning.dimensionsText(model.mesh.bounds, model.scale)
    val captionColors = intArrayOf(
        Color(background[0], background[1], background[2]).toArgb(),
        colors.onSurface.toArgb(),
        colors.onSurfaceVariant.toArgb(),
        colors.outlineVariant.toArgb(),
    )

    /** Renders [plan] and returns the final bitmap (caption included). Caller owns the bitmap. */
    suspend fun render(plan: SnapshotPlan, edgeScale: Float): Bitmap {
        val surface = view ?: throw SnapshotException("The 3D view is not ready yet")
        val request = SnapshotRequest(
            width = plan.renderWidth,
            height = plan.renderHeight,
            transparent = style == SnapshotStyle.TRANSPARENT,
            background = background,
            showGrid = includeGrid,
            edgeScale = edgeScale,
        )
        val pixels = surface.capture(request)
        return withContext(Dispatchers.Default) {
            val bitmap = SnapshotExporter.toBitmap(pixels)
            if (plan.captionHeight > 0) {
                SnapshotExporter.addCaption(
                    bitmap, plan.captionHeight, captionTitle, captionSubtitle,
                    captionColors[0], captionColors[1], captionColors[2], captionColors[3],
                )
            } else {
                bitmap
            }
        }
    }

    // Live preview: a small render with exactly the chosen framing, re-made when an option changes.
    LaunchedEffect(style, includeGrid, includeCaption, base[0], base[1]) {
        val scale = min(1f, PREVIEW_LONG_EDGE.toFloat() / max(base[0], base[1]))
        val plan = SnapshotPlanning.planForScale(scale, base[0], base[1], style, includeCaption, limits)
        preview = try {
            render(plan, max(scale, 0.5f)).asImageBitmap()
        } catch (e: SnapshotException) {
            null
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (file == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SnapshotExporter.saveTo(context, file, uri) }
                onMessage(resources.getString(R.string.snapshot_saved))
            } catch (e: SnapshotException) {
                onMessage(e.message ?: resources.getString(R.string.snapshot_failed))
            }
        }
    }

    fun export(share: Boolean) {
        if (busy || !selected.supported) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SnapshotExporter.cleanUp(context)
                    SnapshotExporter.checkMemory(context, selected)
                }
                val bitmap = render(selected, selected.scale)
                val name = SnapshotPlanning.fileName(model.fileName, style, selected.outputWidth, selected.outputHeight)
                val file = withContext(Dispatchers.IO) { SnapshotExporter.writePng(context, bitmap, name) }
                if (share) {
                    try {
                        context.startActivity(SnapshotExporter.shareIntent(context, file, resources.getString(R.string.snapshot_share_title)))
                    } catch (e: ActivityNotFoundException) {
                        onMessage(resources.getString(R.string.snapshot_no_share_target))
                    }
                } else {
                    pendingSave = file
                    saveLauncher.launch(name)
                }
            } catch (e: SnapshotException) {
                onMessage(e.message ?: resources.getString(R.string.snapshot_failed))
            } catch (e: OutOfMemoryError) {
                onMessage(resources.getString(R.string.snapshot_out_of_memory))
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val previewBox: @Composable (Modifier) -> Unit = { modifier ->
            PreviewBox(
                image = preview,
                aspect = selected.outputWidth.toFloat() / selected.outputHeight,
                transparent = style == SnapshotStyle.TRANSPARENT,
                modifier = modifier,
            )
        }
        val options: @Composable ColumnScope.() -> Unit = {
            SnapshotOptions(
                style = style, onStyle = { style = it },
                size = size, onSize = { size = it },
                plans = plans,
                includeGrid = includeGrid, onGrid = { includeGrid = it },
                includeCaption = includeCaption, onCaption = { includeCaption = it },
                limitsKnown = limits != null,
                busy = busy,
                onSave = { export(share = false) },
                onShare = { export(share = true) },
            )
        }
        if (isLandscape) {
            Row(
                Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                previewBox(Modifier.weight(1f).heightIn(max = 260.dp))
                Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), content = options)
            }
        } else {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
            ) {
                previewBox(Modifier.fillMaxWidth().heightIn(max = 240.dp))
                Spacer(Modifier.size(16.dp))
                options()
            }
        }
    }
}

@Composable
private fun PreviewBox(image: ImageBitmap?, aspect: Float, transparent: Boolean, modifier: Modifier) {
    val checkA = MaterialTheme.colorScheme.surfaceContainerHighest
    val checkB = MaterialTheme.colorScheme.surfaceContainerLow
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(aspect.coerceIn(0.2f, 5f), matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (transparent) {
                        // Checkerboard so transparency is visible.
                        Modifier.drawBehind {
                            val cell = 12.dp.toPx()
                            drawRect(checkB)
                            var y = 0
                            while (y * cell < size.height) {
                                var x = y % 2
                                while (x * cell < size.width) {
                                    drawRect(checkA, Offset(x * cell, y * cell), Size(cell, cell))
                                    x += 2
                                }
                                y++
                            }
                        }
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(image, contentDescription = stringResource(R.string.snapshot_preview))
            } else {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.SnapshotOptions(
    style: SnapshotStyle,
    onStyle: (SnapshotStyle) -> Unit,
    size: SnapshotSize,
    onSize: (SnapshotSize) -> Unit,
    plans: Map<SnapshotSize, SnapshotPlan>,
    includeGrid: Boolean,
    onGrid: (Boolean) -> Unit,
    includeCaption: Boolean,
    onCaption: (Boolean) -> Unit,
    limitsKnown: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Text(stringResource(R.string.snapshot_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.size(12.dp))

    Label(stringResource(R.string.snapshot_background))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val entries = listOf(SnapshotStyle.TRANSPARENT to R.string.snapshot_transparent, SnapshotStyle.FRAMED to R.string.snapshot_framed)
        entries.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = style == value,
                onClick = { onStyle(value) },
                shape = SegmentedButtonDefaults.itemShape(i, entries.size),
                icon = {},
            ) { Text(stringResource(label)) }
        }
    }
    if (style == SnapshotStyle.FRAMED) {
        ToggleRow(stringResource(R.string.snapshot_grid), includeGrid, onGrid)
        ToggleRow(stringResource(R.string.snapshot_caption), includeCaption, onCaption)
    } else {
        Text(
            stringResource(R.string.snapshot_transparent_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    Spacer(Modifier.size(12.dp))
    Label(stringResource(R.string.snapshot_size))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val entries = listOf(
            SnapshotSize.SCREEN to R.string.snapshot_size_screen,
            SnapshotSize.DOUBLE to R.string.snapshot_size_double,
            SnapshotSize.UHD to R.string.snapshot_size_uhd,
        )
        entries.forEachIndexed { i, (value, label) ->
            val plan = plans.getValue(value)
            SegmentedButton(
                selected = size == value,
                onClick = { onSize(value) },
                enabled = plan.supported,
                shape = SegmentedButtonDefaults.itemShape(i, entries.size),
                icon = {},
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(label))
                    Text(
                        "${plan.outputWidth} × ${plan.outputHeight}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    val unsupported = plans.filterValues { !it.supported }.keys
    if (!limitsKnown) {
        Hint(stringResource(R.string.snapshot_not_ready))
    } else if (unsupported.isNotEmpty()) {
        Hint(stringResource(R.string.snapshot_size_unsupported))
    }

    Spacer(Modifier.size(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        val selected = plans.getValue(size)
        OutlinedButton(onClick = onSave, enabled = !busy && selected.supported) {
            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.snapshot_save))
        }
        Button(
            onClick = onShare,
            enabled = !busy && selected.supported,
            colors = ButtonDefaults.buttonColors(containerColor = BrandCyan, contentColor = OnBrandCyan),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OnBrandCyan)
            } else {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.snapshot_share))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Suspends until the GL thread has rendered [request]. */
internal suspend fun ModelSurfaceView.capture(request: SnapshotRequest): SnapshotPixels =
    suspendCancellableCoroutine { cont ->
        captureSnapshot(request) { result ->
            if (cont.isActive) result.fold(cont::resume, cont::resumeWithException)
        }
    }
