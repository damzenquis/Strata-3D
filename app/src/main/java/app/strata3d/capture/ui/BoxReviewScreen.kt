package app.strata3d.capture.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strata3d.R
import app.strata3d.capture.format.CaptureStore
import app.strata3d.capture.recon.BoxReview
import app.strata3d.capture.recon.BoxReview.Handle
import app.strata3d.capture.recon.BoxReview.ObjectBox
import app.strata3d.capture.recon.ModelSize
import app.strata3d.capture.recon.ReconRunner
import app.strata3d.capture.recon.SizeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.hypot

private val Cyan = Color(0xFF01B2C6)
private val OnCyan = Color(0xFF00252A)
private val Scrim = Color(0x99000000)

/** One photo for the review: the undistorted image (upright), its camera and size as stored. */
private class ReviewPhoto(val number: Int, val bitmap: Bitmap, val camera: BoxReview.Camera, val w: Int, val h: Int, val rotation: Int)

private class ReviewData(val photos: List<ReviewPhoto>, val total: Int, val box: ObjectBox, val auto: ObjectBox)

/**
 * The box review (approved mockup 3): the object box drawn on three of the user's photos from different sides, with
 * handles on the top, the bottom and the sides. Dragging a handle moves that side (top, bottom) or the width (sides);
 * the strip switches photos. **Reset** returns to the box the app found; **Build model** saves the box and continues
 * the paused build ([onBuild]). The optional **Real size** row opens the size sheet; the size is saved with the capture
 * and applied to the model when the build finishes.
 */
@Composable
fun BoxReviewScreen(captureDir: File, sceneDir: File, onBuild: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var data by remember { mutableStateOf<ReviewData?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(sceneDir) {
        val d = withContext(Dispatchers.IO) { loadReview(captureDir, sceneDir) }
        if (d == null) failed = true else data = d
    }
    var box by remember(data) { mutableStateOf(data?.box) }
    var selected by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf<ModelSize.Request?>(null) }
    var sizing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(captureDir) { size = withContext(Dispatchers.IO) { SizeStore.read(captureDir)?.request } }
    LaunchedEffect(saving) {
        if (!saving) return@LaunchedEffect
        val b = box ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { saveBox(sceneDir, b) }
        onBuild()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val d = data
        when {
            failed -> Text(stringResource(R.string.recon_review_unavailable), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            d == null || box == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cyan)
            else -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val photo = d.photos[selected.coerceIn(0, d.photos.lastIndex)]
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PhotoWithBox(photo, box!!, minSize = (d.auto.top - d.auto.bottom) * 0.05) { box = it }
                    Text(
                        stringResource(R.string.recon_review_photo, photo.number, d.total),
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp).background(Scrim, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.photos.forEachIndexed { i, p ->
                        Image(
                            p.bitmap.asImageBitmap(), contentDescription = stringResource(R.string.recon_review_photo, p.number, d.total),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).height(56.dp)
                                .border(if (i == selected) 2.dp else 0.dp, if (i == selected) Cyan else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { selected = i },
                        )
                    }
                }
                SizeRow(size, color = Color.White, accent = Cyan, onClick = { sizing = true }, modifier = Modifier.padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { box = d.auto }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.recon_review_reset), color = Color.White) }
                    Button(
                        onClick = { saving = true }, enabled = !saving, modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = OnCyan),
                    ) { Text(stringResource(R.string.recon_build)) }
                }
            }
        }
    }
    if (sizing) {
        SizeSheet(size, onSave = { r ->
            sizing = false; size = r
            scope.launch { withContext(Dispatchers.IO) { SizeStore.write(captureDir, ModelSize.Record(r)) } }
        }, onDismiss = { sizing = false })
    }
}

/** The photo fitted in the available space, the box edges over it, and draggable handles. */
@Composable
private fun PhotoWithBox(photo: ReviewPhoto, box: ObjectBox, minSize: Double, onChange: (ObjectBox) -> Unit) {
    val density = LocalDensity.current
    val grabRadius = with(density) { 40.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = constraints.maxWidth.toFloat(); val vh = constraints.maxHeight.toFloat()
        val (uw, uh) = BoxReview.uprightSize(photo.w, photo.h, photo.rotation)
        val scale = minOf(vw / uw, vh / uh)
        val ox = (vw - uw * scale) / 2; val oy = (vh - uh * scale) / 2
        // World point -> upright photo pixel -> view pixel.
        val toUpright: (BoxReview.V3) -> Pair<Double, Double>? = { p ->
            BoxReview.project(photo.camera, photo.w, photo.h, p)?.let { (px, py) -> BoxReview.toUpright(px, py, photo.w, photo.h, photo.rotation) }
        }
        fun toView(p: BoxReview.V3): Offset? = toUpright(p)?.let { (u, v) -> Offset(ox + (u * scale).toFloat(), oy + (v * scale).toFloat()) }
        // Handles: top, bottom, and the two side handles furthest left and right on screen.
        val sides = listOf(Handle.SIDE_POS_X, Handle.SIDE_NEG_X, Handle.SIDE_POS_Y, Handle.SIDE_NEG_Y)
            .mapNotNull { h -> toView(BoxReview.handlePoint(box, h))?.let { h to it } }
        val shown = listOfNotNull(Handle.TOP, Handle.BOTTOM).mapNotNull { h -> toView(BoxReview.handlePoint(box, h))?.let { h to it } } +
            listOfNotNull(sides.minByOrNull { it.second.x }, sides.maxByOrNull { it.second.x }).distinct()
        var active by remember { mutableStateOf<Handle?>(null) }
        // The drag handler is created once per photo: it reads the latest box, handles and scale through these.
        val current by rememberUpdatedState(box)
        val latestShown by rememberUpdatedState(shown)
        val latestScale by rememberUpdatedState(scale)
        val latestProject by rememberUpdatedState(toUpright)

        Image(photo.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(photo) {
                detectDragGestures(
                    onDragStart = { pos ->
                        active = latestShown.minByOrNull { (_, p) -> hypot((p.x - pos.x).toDouble(), (p.y - pos.y).toDouble()) }
                            ?.takeIf { (_, p) -> hypot((p.x - pos.x).toDouble(), (p.y - pos.y).toDouble()) <= grabRadius }?.first
                    },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                    onDrag = { change, drag ->
                        val h = active ?: return@detectDragGestures
                        change.consume()
                        val delta = BoxReview.dragDelta(current, h, drag.x / latestScale.toDouble(), drag.y / latestScale.toDouble(), latestProject)
                        onChange(BoxReview.adjust(current, h, delta, minSize))
                    },
                )
            },
        ) {
            val corners = box.corners().map { toView(it) }
            for ((a, b) in box.edges) {
                val pa = corners[a] ?: continue; val pb = corners[b] ?: continue
                drawLine(Cyan, pa, pb, strokeWidth = 2.dp.toPx())
            }
            for ((h, p) in shown) {
                drawCircle(Cyan, radius = (if (h == active) 9 else 7).dp.toPx(), center = p)
                drawCircle(Color.White, radius = (if (h == active) 9 else 7).dp.toPx(), center = p, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Reads the paused scene: cameras, undistorted photos (three spread around), rotation, and the boxes. */
private fun loadReview(captureDir: File, sceneDir: File): ReviewData? {
    val box = File(sceneDir, "objbox.txt").takeIf { it.isFile }?.readText()?.let { ObjectBox.parse(it) } ?: return null
    val auto = File(sceneDir, ReconRunner.AUTO_BOX).takeIf { it.isFile }?.readText()?.let { ObjectBox.parse(it) } ?: box
    val rotations = rotationsByImage(captureDir)
    val views = File(sceneDir, "views").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
    val cams = views.mapNotNull { v ->
        val cam = File(v, "meta.ini").takeIf { it.isFile }?.readText()?.let { BoxReview.Camera.parseMetaIni(it) } ?: return@mapNotNull null
        val img = File(v, "undistorted.png").takeIf { it.isFile } ?: return@mapNotNull null
        val name = File(v, "meta.ini").readLines().firstOrNull { it.trimStart().startsWith("name") }?.substringAfter('=')?.trim() ?: return@mapNotNull null
        Triple(name, cam, img)
    }
    if (cams.isEmpty()) return null
    val picked = BoxReview.pickViews(cams.map { it.second.centre }, 3)
    val photos = picked.mapNotNull { i ->
        val (name, cam, img) = cams[i]
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(img.path, it) }
        if (bounds.outWidth <= 0) return@mapNotNull null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
        val bmp = BitmapFactory.decodeFile(img.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@mapNotNull null
        val rot = rotations[name] ?: 0
        val upright = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        ReviewPhoto(name.toIntOrNull() ?: (i + 1), upright, cam, bounds.outWidth, bounds.outHeight, rot)
    }
    if (photos.isEmpty()) return null
    return ReviewData(photos, views.size, box, auto)
}

/** Image base name ("000012") -> rotationDegrees, from the capture's frames.jsonl. */
private fun rotationsByImage(captureDir: File): Map<String, Int> {
    val f = File(captureDir, CaptureStore.FRAMES).takeIf { it.isFile } ?: return emptyMap()
    return f.readLines().mapNotNull { line ->
        val img = Regex("\"image\":\"[^\"]*?([^/\"]+)\\.jpg\"").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
        val rot = Regex("\"rotationDegrees\":(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        img to rot
    }.toMap()
}

/** Writes the reviewed box atomically to the scene, where the resumed build reads it. */
private fun saveBox(sceneDir: File, box: ObjectBox) {
    val tmp = File(sceneDir, "objbox.txt.tmp")
    tmp.writeText(box.encode() + "\n")
    Files.move(tmp.toPath(), File(sceneDir, "objbox.txt").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
