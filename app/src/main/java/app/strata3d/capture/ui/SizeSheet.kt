package app.strata3d.capture.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.strata3d.R
import app.strata3d.capture.CameraXCapture
import app.strata3d.capture.recon.ModelSize
import app.strata3d.capture.recon.ModelSize.Axis
import app.strata3d.capture.recon.ModelSize.Request
import app.strata3d.capture.recon.ModelSize.SizeUnit
import java.util.Locale

/**
 * The real-size sheet (approved mockup 2, with the Height | Width switch): one measurement of the object, in cm or
 * in. The other dimensions follow from the model. The unit is remembered for next time; Height is the default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SizeSheet(initial: Request?, onSave: (Request) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var axis by remember { mutableStateOf(initial?.axis ?: Axis.HEIGHT) }
    var unit by remember { mutableStateOf(initial?.unit ?: preferredUnit(context)) }
    var text by remember { mutableStateOf(initial?.let { ModelSize.formatValue(it.value) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val errorNumber = stringResource(R.string.size_error_number)
    val errorRange = stringResource(
        R.string.size_error_range,
        "${ModelSize.formatValue(ModelSize.MIN_MM / unit.mm)} ${unit.symbol}",
        "${ModelSize.formatValue(ModelSize.MAX_MM / unit.mm)} ${unit.symbol}",
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (axis == Axis.HEIGHT) R.string.size_title_height else R.string.size_title_width), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (axis == Axis.HEIGHT) R.string.size_body_height else R.string.size_body_width),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val axes = listOf(Axis.HEIGHT to R.string.size_axis_height, Axis.WIDTH to R.string.size_axis_width)
                axes.forEachIndexed { i, (value, label) ->
                    SegmentedButton(selected = axis == value, onClick = { axis = value }, shape = SegmentedButtonDefaults.itemShape(i, axes.size), icon = {}) {
                        Text(stringResource(label))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(stringResource(R.string.size_value_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { e -> { Text(e) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                SingleChoiceSegmentedButtonRow(Modifier.width(120.dp)) {
                    val units = SizeUnit.entries
                    units.forEachIndexed { i, u ->
                        SegmentedButton(selected = unit == u, onClick = { unit = u; error = null }, shape = SegmentedButtonDefaults.itemShape(i, units.size), icon = {}) {
                            Text(u.symbol)
                        }
                    }
                }
            }
            Text(
                stringResource(if (axis == Axis.HEIGHT) R.string.size_follow_height else R.string.size_follow_width),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.size_skip)) }
                Button(
                    onClick = {
                        val value = ModelSize.parseNumber(text)
                        val request = value?.let { Request(axis, it, unit) }
                        when {
                            request == null -> error = errorNumber
                            !ModelSize.isValid(request) -> error = errorRange
                            else -> { rememberUnit(context, unit); onSave(request) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.size_save)) }
            }
        }
    }
}

/** "Height 25 cm", "Width 6.5 in". */
@Composable
fun sizeRequestText(r: Request): String = stringResource(
    if (r.axis == Axis.HEIGHT) R.string.size_row_height else R.string.size_row_width,
    "${ModelSize.formatValue(r.value)} ${r.unit.symbol}",
)

/** The "Real size" row: ruler icon, label, and the entered size or "Add (optional)". */
@Composable
fun SizeRow(request: Request?, color: Color, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Straighten, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.size_row_label), color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text(request?.let { sizeRequestText(it) } ?: stringResource(R.string.size_add_optional), color = accent, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val KEY_SIZE_UNIT = "sizeUnit"

/** The last unit used, or inches where they are the everyday unit (US, Liberia, Myanmar), else centimetres. */
private fun preferredUnit(context: Context): SizeUnit {
    val stored = context.getSharedPreferences(CameraXCapture.PREFS, Context.MODE_PRIVATE).getString(KEY_SIZE_UNIT, null)
    return SizeUnit.of(stored) ?: if (Locale.getDefault().country in setOf("US", "LR", "MM")) SizeUnit.IN else SizeUnit.CM
}

private fun rememberUnit(context: Context, unit: SizeUnit) =
    context.getSharedPreferences(CameraXCapture.PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SIZE_UNIT, unit.id).apply()
