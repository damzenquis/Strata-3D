package app.strata3d.capture.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.strata3d.R
import app.strata3d.capture.CameraXCapture
import app.strata3d.capture.CameraXCapture.Mode
import app.strata3d.capture.CameraXCapture.Notice
import app.strata3d.capture.CameraXCapture.Phase
import app.strata3d.capture.format.CaptureStore
import kotlinx.coroutines.delay

/** The app's brand cyan and the dark ink used on it (see ui/theme). */
private val Cyan = Color(0xFF01B2C6)
private val OnCyan = Color(0xFF00252A)
private val Teal = Color(0xFF5DCAA5)
private val Scrim = Color(0x99000000)
private val OnScrim = Color(0xFFF1EFE8)
private val Track = Color(0xFF565C63)

/** The live capture screen. [onFinished] gets the saved capture's id, or null when nothing was kept. */
@Composable
fun CaptureScreen(store: CaptureStore, onFinished: (String?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val engine = remember {
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { null }
        CameraXCapture(context.applicationContext, store, version ?: "")
    }
    val state by engine.state.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }

    // Leaving the screen releases the camera and sensors, and keeps an unfinished capture.
    DisposableEffect(Unit) { onDispose { engine.shutdown() } }
    // Sensors follow the activity's lifecycle (CameraX does the same for the camera).
    val lifecycle = lifecycleOwner.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> engine.onResume()
                Lifecycle.Event.ON_PAUSE -> engine.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs) // replays ON_RESUME when already resumed
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.SAVED) onFinished(state.savedCaptureId)
    }

    val leave = { if (state.photoCount > 0) confirmLeave = true else engine.discard() }
    BackHandler(enabled = state.phase != Phase.SAVING) { leave() }

    // Brief cyan frame when a photo is saved.
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(state.flashCount) {
        if (state.flashCount > 0) { flash = true; delay(250); flash = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // The whole photo is shown (letterboxed), so a tap maps exactly onto the photo.
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    engine.bind(lifecycleOwner, this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Taps over the camera mark the object (before the first photo).
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { p -> engine.onTap(p.x, p.y) }
            },
        )
        // Tap marker until the first photo.
        val tx = state.tapX; val ty = state.tapY
        if (tx != null && ty != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Cyan, radius = 22.dp.toPx(), center = Offset(tx, ty), style = Stroke(width = 3.dp.toPx()))
                drawCircle(Cyan, radius = 4.dp.toPx(), center = Offset(tx, ty))
            }
        }
        if (flash) Box(Modifier.fillMaxSize().border(4.dp, Cyan))

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Top bar: close, mode chip, photo count.
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.capture_close), tint = OnScrim)
                }
                Spacer(Modifier.weight(1f))
                ModeChip(state.mode, enabled = state.smartAvailable) {
                    engine.setMode(if (state.mode == Mode.SMART) Mode.MANUAL else Mode.SMART)
                }
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.capture_count, state.photoCount))
            }
            Row(Modifier.fillMaxWidth().padding(end = 12.dp)) {
                Spacer(Modifier.weight(1f))
                CoverageDome(listOf(state.lowerRing, state.upperRing), null, Modifier.size(52.dp))
            }
            Spacer(Modifier.weight(1f))
            noticeText(state)?.let { msg ->
                val strong = state.notice == Notice.HOLD_STEADY
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        msg,
                        color = if (strong) OnCyan else OnScrim,
                        fontSize = 14.sp,
                        fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.background(if (strong) Cyan else Scrim, RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.size(20.dp))
            // Bottom: pause (smart), shutter, done.
            val armed = state.phase == Phase.CAPTURING
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterStart) {
                    if (armed && state.mode == Mode.SMART) {
                        TextButton(onClick = { engine.togglePause() }) {
                            Text(stringResource(if (state.paused) R.string.capture_resume else R.string.capture_pause_short), color = OnScrim)
                        }
                    }
                }
                Shutter(
                    locked = !armed,
                    smart = state.mode == Mode.SMART,
                    saving = state.photoInFlight,
                    paused = state.paused,
                    progress = state.progress,
                    onClick = { if (state.mode == Mode.SMART) engine.togglePause() else engine.manualShutter() },
                )
                Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { engine.finish() }, enabled = state.photoCount > 0) {
                        Text(stringResource(R.string.capture_done), color = OnScrim, modifier = Modifier.alpha(if (state.photoCount > 0) 1f else 0.4f))
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.capture_leave_title)) },
            text = { Text(stringResource(R.string.capture_leave_body, state.photoCount)) },
            confirmButton = { Button(onClick = { confirmLeave = false; engine.finish() }) { Text(stringResource(R.string.capture_keep)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; engine.discard() }) { Text(stringResource(R.string.capture_discard)) } },
        )
    }
    if (state.phase == Phase.ERROR) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.capture_error_title)) },
            text = { Text(state.errorMessage ?: "") },
            confirmButton = {
                Button(onClick = { if (state.photoCount > 0) engine.finish() else onFinished(null) }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun noticeText(s: CameraXCapture.UiState): String? = when (s.notice) {
    Notice.NONE -> null
    Notice.TAP_OBJECT -> stringResource(R.string.capture_tap_object)
    Notice.KEEP_MOVING -> stringResource(R.string.capture_keep_moving)
    Notice.HOLD_STEADY -> stringResource(R.string.capture_hold_steady)
    Notice.SAVING_PHOTO -> stringResource(R.string.capture_saving_photo)
    Notice.PAUSED -> stringResource(R.string.guide_paused)
    Notice.MANUAL -> stringResource(R.string.capture_manual_hint)
    Notice.HOT -> stringResource(R.string.guide_hot)
    Notice.LOW_STORAGE -> stringResource(R.string.guide_low_storage)
    Notice.PHOTO_FAILED -> stringResource(R.string.capture_photo_failed)
    Notice.NO_SENSORS -> stringResource(R.string.capture_no_sensors)
}

@Composable
private fun Pill(text: String) {
    Text(
        text, color = OnScrim, fontSize = 12.sp,
        modifier = Modifier.background(Scrim, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun ModeChip(mode: Mode, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (mode == Mode.SMART) R.string.capture_mode_smart else R.string.capture_mode_manual)
    Text(
        label, color = OnScrim, fontSize = 12.sp,
        modifier = Modifier
            .background(Scrim, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * The shutter. Locked (before the object is marked): a lock. Smart: an "A" disc with the progress ring toward the
 * next photo; tapping pauses or resumes. Manual: a plain disc that takes a photo. Grey while a photo is saving.
 */
@Composable
private fun Shutter(locked: Boolean, smart: Boolean, saving: Boolean, paused: Boolean, progress: Double, onClick: () -> Unit) {
    val desc = stringResource(
        when {
            locked -> R.string.capture_shutter_locked
            smart -> if (paused) R.string.capture_resume else R.string.capture_pause_short
            else -> R.string.capture_shutter_desc
        },
    )
    val ring by animateFloatAsState(if (smart && !locked) progress.toFloat().coerceIn(0f, 1f) else 0f, tween(120), label = "ring")
    Box(
        Modifier.size(76.dp).semantics { contentDescription = desc }
            .clickable(enabled = !locked && !(saving && !smart), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val r = size.minDimension / 2 - stroke
            val tl = Offset(center.x - r, center.y - r)
            drawCircle(if (locked) Color(0xFF6F7479) else Track, radius = r, style = Stroke(stroke))
            if (ring > 0f) drawArc(Cyan, -90f, 360f * ring, false, tl, Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
            val disc = when {
                locked -> Color.Transparent
                saving -> Color(0xFF9AA0A6)
                else -> Color.White
            }
            drawCircle(disc, radius = r - 6.dp.toPx())
        }
        when {
            locked -> Icon(Icons.Outlined.Lock, contentDescription = null, tint = Color(0xFF9AA0A6), modifier = Modifier.size(24.dp))
            smart -> Text("A", color = OnCyan.copy(alpha = if (paused) 0.4f else 1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Coverage dial: one ring per entry of [rings] (outer first), cells filled where a photo was taken. With
 * [completeness] the percentage is shown in the middle.
 */
@Composable
fun CoverageDome(rings: List<List<Boolean>>, completeness: Double?, modifier: Modifier = Modifier) {
    val covered = rings.sumOf { r -> r.count { it } }
    val total = rings.sumOf { it.size }.coerceAtLeast(1)
    val percent = ((completeness ?: (covered.toDouble() / total)) * 100).toInt()
    val desc = stringResource(R.string.capture_coverage_desc, percent)
    Box(modifier.semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Scrim)
            val stroke = size.minDimension * 0.11f
            val radii = listOf(0.40f, 0.25f, 0.12f).map { it * size.minDimension }
            rings.forEachIndexed { ring, cells ->
                val r = radii.getOrElse(ring) { return@forEachIndexed }
                val n = cells.size
                if (n == 0) return@forEachIndexed
                val sweep = 360f / n
                cells.forEachIndexed { i, on ->
                    drawArc(
                        color = if (on) Teal else Color(0x66888780),
                        startAngle = -90f + i * sweep + 1f,
                        sweepAngle = sweep - 2f,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(2 * r, 2 * r),
                        style = Stroke(width = stroke),
                    )
                }
            }
        }
        if (completeness != null) Text("$percent%", color = OnScrim, fontSize = 11.sp)
    }
}
