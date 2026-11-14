package app.strata3d.capture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.strata3d.R
import app.strata3d.capture.recon.ModelSize
import app.strata3d.capture.recon.ReconPlan.Stage
import app.strata3d.capture.recon.ReconService
import app.strata3d.capture.recon.ReconStatus
import app.strata3d.capture.recon.ReconStatus.Error
import app.strata3d.capture.recon.ReconStatus.State
import kotlinx.coroutines.delay
import java.text.NumberFormat

/**
 * The "Build a 3D model" card on the capture summary (approved mockups A–D):
 * - A: nothing built yet (or the last build was cancelled): explanation and **Build model**;
 * - B: building: step, progress, elapsed time, finished stages, **Cancel**;
 * - C: model ready: a picture of the model, honest stats (photos used), the real size (or "Size not set"),
 *   **Open in viewer**, **Export model**, the review switch and **Build again**;
 * - D: couldn't build: plain reason, technical detail underneath, **Try again**;
 * - F: paused for the user's check of the object box: **Check the box**, **Cancel**.
 * The "Review the box before building" switch (mockup 2) sits in A and C; it starts off every time.
 *
 * [status] is the capture's last reconstruction; [interrupted] is true when it says "running" but the
 * reconstruction process is gone (the app was closed or the phone restarted).
 */
@Composable
fun BuildModelCard(
    status: ReconStatus?,
    interrupted: Boolean,
    buildingElsewhere: Boolean,
    review: Boolean,
    onReviewChange: (Boolean) -> Unit,
    onBuild: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onCheckBox: () -> Unit,
    size: ModelSize.Record? = null,
    onSize: () -> Unit = {},
    preview: androidx.compose.ui.graphics.ImageBitmap? = null,
    onExport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state = when {
        status == null -> null
        status.state == State.RUNNING && interrupted -> State.FAILED
        else -> status.state
    }
    when (state) {
        State.RUNNING -> Building(status!!, onCancel, modifier)
        State.DONE -> Ready(status!!, size, onSize, preview, onOpen, onExport, review, onReviewChange, onBuild, modifier)
        State.FAILED -> Failed(if (interrupted) status!!.copy(error = Error.INTERRUPTED) else status!!, onBuild, modifier)
        State.REVIEW -> Paused(status!!, onCheckBox, onCancel, modifier)
        State.CANCELLED, null -> Start(cancelled = state == State.CANCELLED, enabled = !buildingElsewhere, review, onReviewChange, onBuild, modifier)
    }
}

@Composable
private fun Panel(modifier: Modifier, error: Boolean = false, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth()
            .background(c.surfaceContainerLowest, RoundedCornerShape(12.dp))
            .then(if (error) Modifier.border(1.dp, c.error, RoundedCornerShape(12.dp)) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Start(cancelled: Boolean, enabled: Boolean, review: Boolean, onReviewChange: (Boolean) -> Unit, onBuild: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(Modifier) {
            Text(stringResource(R.string.recon_build_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.recon_build_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (cancelled) Text(stringResource(R.string.recon_cancelled_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReviewSwitch(review, onReviewChange)
        Button(onClick = onBuild, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recon_build)) }
    }
}

/** "Review the box before building" (mockup 2): before the first build and next to Build again; off every time. */
@Composable
private fun ReviewSwitch(review: Boolean, onReviewChange: (Boolean) -> Unit) {
    Panel(Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recon_review_switch), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = review, onCheckedChange = onReviewChange)
        }
        Text(stringResource(R.string.recon_review_switch_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Building(s: ReconStatus, onCancel: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val stages = Stage.entries
    val index = s.stage?.let { stages.indexOf(it) } ?: 0
    // Progress: whole stages done, plus the fraction of the depth batches.
    val within = if (s.steps > 0) (s.step - 1).coerceAtLeast(0).toFloat() / s.steps else 0f
    val progress = ((index + within) / stages.size).coerceIn(0f, 1f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(Modifier) {
            Text(stringResource(R.string.recon_building), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.recon_step, index + 1, stages.size, ReconService.stageText(context, s)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp))
            Text(stringResource(R.string.recon_elapsed, duration(now - s.startedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            stages.take(index + 1).forEachIndexed { i, st ->
                val done = i < index
                val name = ReconService.stageText(context, ReconStatus(s.captureId, State.RUNNING, st))
                val label = if (st == Stage.CAMERAS && done && s.photos > 0) stringResource(R.string.recon_placed_detail, name, s.placed, s.photos) else name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (done) "✓  " else "●  ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(stringResource(R.string.recon_leave_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun Paused(s: ReconStatus, onCheckBox: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(Modifier) {
            Text(stringResource(R.string.recon_review_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.recon_review_body, s.placed, s.photos), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (st in listOf(Stage.IMPORT, Stage.CAMERAS, Stage.BOX)) {
                val name = ReconService.stageText(context, ReconStatus(s.captureId, State.RUNNING, st))
                val label = if (st == Stage.CAMERAS && s.photos > 0) stringResource(R.string.recon_placed_detail, name, s.placed, s.photos) else name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓  ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(onClick = onCheckBox, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recon_review_title)) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun Ready(
    s: ReconStatus, size: ModelSize.Record?, onSize: () -> Unit, preview: androidx.compose.ui.graphics.ImageBitmap?,
    onOpen: () -> Unit, onExport: () -> Unit, review: Boolean, onReviewChange: (Boolean) -> Unit, onBuild: () -> Unit, modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(Modifier) {
            Text(stringResource(R.string.recon_ready), style = MaterialTheme.typography.titleSmall)
            // The picture of the model rendered when it was built; a plain icon for models built before pictures.
            Box(
                Modifier.fillMaxWidth().height(if (preview != null) 160.dp else 90.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    androidx.compose.foundation.Image(
                        preview, contentDescription = stringResource(R.string.recon_preview_description),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(160.dp).padding(6.dp),
                    )
                } else {
                    Icon(Icons.Outlined.ViewInAr, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // A status written before photo counts were recorded shows the time only, never "0 of 0".
            Text(
                if (s.photos > 0 && s.vertices > 0) stringResource(R.string.recon_ready_stats, duration(s.updatedAt - s.startedAt), s.placed, s.photos, NumberFormat.getIntegerInstance().format(s.vertices))
                else stringResource(R.string.recon_ready_time, duration(s.updatedAt - s.startedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.photos > 0 && s.photos > s.placed) {
                Text(stringResource(R.string.recon_ready_gaps, s.photos - s.placed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Real size: the model's height x width x depth once sized, otherwise an honest "Size not set".
            val dims = size?.dimensionsMm
            Row(Modifier.fillMaxWidth().clickable(onClick = onSize).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Straighten, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(
                    if (dims != null) ModelSize.format(dims, size.request.unit) else stringResource(R.string.size_not_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dims != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Text(stringResource(if (dims != null) R.string.size_edit else R.string.size_add), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recon_open)) }
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.export_model), modifier = Modifier.padding(start = 8.dp))
        }
        ReviewSwitch(review, onReviewChange)
        OutlinedButton(onClick = onBuild, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recon_build_again)) }
    }
}

@Composable
private fun Failed(s: ReconStatus, onBuild: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val message = when (s.error) {
        Error.UNSUPPORTED_DEVICE -> R.string.recon_err_unsupported
        Error.LOW_STORAGE -> R.string.recon_err_storage
        Error.LOW_MEMORY -> R.string.recon_err_memory
        Error.TOO_FEW_PHOTOS_PLACED -> R.string.recon_err_placed
        Error.NO_OBJECT -> R.string.recon_err_no_object
        Error.TAP_OUTSIDE -> R.string.recon_err_tap
        Error.INTERRUPTED -> R.string.recon_err_interrupted
        Error.TOOL_FAILED, null -> R.string.recon_err_tool
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(Modifier, error = true) {
            Text(stringResource(R.string.recon_failed), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
            val detail = listOfNotNull(s.stage?.let { ReconService.stageText(context, s.copy(steps = 0)) }, s.detail).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (s.error != Error.UNSUPPORTED_DEVICE) Button(onClick = onBuild, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recon_try_again)) }
    }
}

@Composable
private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 60) stringResource(R.string.recon_minutes_seconds, (s / 60).toInt(), (s % 60).toInt())
    else stringResource(R.string.recon_seconds, s.toInt())
}
