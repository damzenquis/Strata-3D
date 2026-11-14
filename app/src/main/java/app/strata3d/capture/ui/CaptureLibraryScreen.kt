package app.strata3d.capture.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.strata3d.R
import app.strata3d.capture.format.CaptureState
import app.strata3d.capture.format.StoredCapture
import app.strata3d.ui.theme.accentText
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The captures on this phone, newest first. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CaptureLibraryScreen(
    viewModel: CaptureLibraryViewModel,
    onBack: () -> Unit,
    onNewCapture: () -> Unit,
    onOpen: (String) -> Unit,
    onShutterSettings: () -> Unit,
) {
    val captures by viewModel.captures.collectAsState()
    val export by viewModel.export.collectAsState()
    val message by viewModel.messages.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<StoredCapture?>(null) }
    var renaming by remember { mutableStateOf<StoredCapture?>(null) }
    var deleting by remember { mutableStateOf<StoredCapture?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    val exporter = rememberCaptureExporter(viewModel)

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(resources.getString(it)); viewModel.messageShown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.captures_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.capture_back)) }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.capture_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.capture_device_report)) },
                            leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                            onClick = { menuOpen = false; scope.launch { report = viewModel.deviceReport(context) } },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.capture_shutter_setting)) },
                            leadingIcon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                            onClick = { menuOpen = false; onShutterSettings() },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewCapture,
                icon = { Icon(Icons.Outlined.AddAPhoto, contentDescription = null) },
                text = { Text(stringResource(R.string.capture_new)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = captures
        Column(Modifier.fillMaxSize().padding(padding)) {
            export?.let { e ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.capture_exporting, e.percent), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { e.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyLibrary(Modifier.fillMaxSize())
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(list, key = { it.id }) { c ->
                        Box {
                            CaptureRow(
                                capture = c,
                                viewModel = viewModel,
                                onClick = { onOpen(c.id) },
                                onLongClick = { actionsFor = c },
                                onShare = { exporter(c) },
                            )
                            DropdownMenu(expanded = actionsFor?.id == c.id, onDismissRequest = { actionsFor = null }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.capture_rename)) },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                    onClick = { actionsFor = null; renaming = c },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.capture_export)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    onClick = { actionsFor = null; exporter(c) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.capture_delete)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    onClick = { actionsFor = null; deleting = c },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    renaming?.let { c -> RenameDialog(c, onDismiss = { renaming = null }, onSave = { viewModel.rename(c, it); renaming = null }) }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.capture_delete_title)) },
            text = { Text(stringResource(R.string.capture_delete_body, c.manifest.frameCount)) },
            confirmButton = { Button(onClick = { viewModel.delete(c); deleting = null }) { Text(stringResource(R.string.capture_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(stringResource(R.string.capture_device_report)) },
            text = {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(R.string.close)) } },
            dismissButton = {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Strata:3D device report", text))
                }) { Text(stringResource(R.string.capture_copy)) }
            },
        )
    }
}

/** Opens the system "save as" picker for a capture's .zip and exports it there. */
@Composable
fun rememberCaptureExporter(viewModel: CaptureLibraryViewModel): (StoredCapture) -> Unit {
    var pending by remember { mutableStateOf<StoredCapture?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val c = pending
        pending = null
        if (uri != null && c != null) viewModel.export(c, uri, R.string.capture_exported, R.string.capture_export_failed)
    }
    return { c ->
        pending = c
        val base = c.manifest.name.ifBlank { "strata3d-capture-${c.id}" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        launcher.launch("$base.zip")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureRow(
    capture: StoredCapture,
    viewModel: CaptureLibraryViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val m = capture.manifest
    val thumb by produceState<Bitmap?>(null, capture.id) { value = viewModel.thumbnail(capture) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
            thumb?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(captureTitle(capture), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.state == CaptureState.INTERRUPTED) {
                Text(stringResource(R.string.capture_interrupted, m.frameCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    stringResource(R.string.capture_stats, m.frameCount, (m.coverage * 100).toInt(), Formatter.formatShortFileSize(context, capture.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.capture_export)) }
    }
}

@Composable
fun captureTitle(c: StoredCapture): String =
    c.manifest.name.ifBlank {
        stringResource(R.string.capture_untitled, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.manifest.createdAt)))
    }

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AddAPhoto, contentDescription = null, tint = MaterialTheme.colorScheme.accentText, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.captures_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.captures_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun RenameDialog(capture: StoredCapture, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(capture.manifest.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_rename_title)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true, label = { Text(stringResource(R.string.capture_name_hint)) }) },
        confirmButton = { Button(onClick = { onSave(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
