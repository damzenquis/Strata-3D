package app.strata3d.capture.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.strata3d.R
import app.strata3d.capture.recon.ModelExport
import app.strata3d.capture.recon.ModelExport.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface ExportState {
    data object Choosing : ExportState
    data class Writing(val percent: Int) : ExportState
    data class Saved(val name: String, val uri: Uri, val mime: String) : ExportState
    data object Failed : ExportState
}

/**
 * Export model (approved mockups 2–3): GLB (default) or OBJ in a .zip, then Save (Android's file picker) or Share;
 * progress with Cancel; then "Saved" with Open. [sized] says whether the model has a real size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(modelDir: File, baseName: String, sized: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(Format.GLB) }
    var state by remember { mutableStateOf<ExportState>(ExportState.Choosing) }
    var cancelled by remember { mutableStateOf(false) }
    val sizes = remember(modelDir) { Format.entries.associateWith { ModelExport.estimateBytes(modelDir, it) } }
    val fileName = "$baseName.${format.extension}"

    fun run(target: Uri, display: String, share: Boolean) {
        cancelled = false
        state = ExportState.Writing(0)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(target, "wt")!!.use { out ->
                        ModelExport.write(modelDir, format, out) { p -> state = ExportState.Writing(p); !cancelled }
                    }
                    true
                } catch (e: Exception) {
                    android.util.Log.e("ExportSheet", "export", e)
                    if (!share) runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }   // no half-written file
                    false
                }
            }
            when {
                cancelled -> { state = ExportState.Choosing }
                !ok -> state = ExportState.Failed
                share -> {
                    state = ExportState.Choosing
                    val send = Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, target)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                }
                else -> state = ExportState.Saved(display, target, format.mime)
            }
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) run(uri, displayName(context, uri) ?: fileName, share = false)
    }

    ModalBottomSheet(onDismissRequest = { if (state !is ExportState.Writing) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.export_model), style = MaterialTheme.typography.titleMedium)
            when (val s = state) {
                is ExportState.Writing -> {
                    Text(stringResource(R.string.export_writing, format.extension.uppercase(), s.percent), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp))
                    OutlinedButton(onClick = { cancelled = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
                }
                is ExportState.Saved -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.export_saved), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.export_open), color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (e: ActivityNotFoundException) {
                                android.widget.Toast.makeText(context, R.string.export_no_app, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }.padding(8.dp))
                    }
                    Text(s.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_done)) }
                }
                else -> {
                    if (s is ExportState.Failed) Text(stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    FormatOption(Format.GLB, format == Format.GLB, R.string.export_glb, R.string.export_glb_body, sizes.getValue(Format.GLB)) { format = it }
                    FormatOption(Format.OBJ_ZIP, format == Format.OBJ_ZIP, R.string.export_obj, R.string.export_obj_body, sizes.getValue(Format.OBJ_ZIP)) { format = it }
                    Text(stringResource(if (sized) R.string.export_sized else R.string.export_unsized), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { save.launch(fileName) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_save)) }
                    OutlinedButton(onClick = {
                        val dir = File(context.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                        val file = File(dir, fileName).apply { createNewFile() }
                        run(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), fileName, share = true)
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_share)) }
                }
            }
        }
    }
}

@Composable
private fun FormatOption(f: Format, selected: Boolean, title: Int, body: Int, bytes: Long, onSelect: (Format) -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable { onSelect(f) },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.export_about_size, Formatter.formatShortFileSize(context, bytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()
