package app.strata3d.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.strata3d.R
import app.strata3d.model.LengthFormat
import app.strata3d.model.LengthUnit
import app.strata3d.model.ModelScale
import app.strata3d.model.UnitSource
import app.strata3d.ui.theme.accentText
import app.strata3d.ui.theme.accentTextButtonColors

/**
 * Measurement card (two points picked) or instruction pill (picking), each with the Snap and Units controls.
 * Distances are measured in model coordinates and converted to millimetres through [scale].
 */
@Composable
internal fun MeasurementOverlay(
    isMeasuring: Boolean,
    measurePoint1: Point3D?,
    measurement: Measurement?,
    scale: ModelScale,
    declaredUnit: LengthUnit?,
    snap: Boolean,
    onToggleSnap: () -> Unit,
    onUnitChoice: (LengthUnit?) -> Unit,
    onClear: () -> Unit,
    onExit: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isMeasuring) return
    val assumedNote = stringResource(R.string.measure_units_assumed_note)

    Box(modifier = modifier) {
        if (measurement != null) {
            val distance = LengthFormat.length(scale.toMm(measurement.distance.toDouble()))
            val dx = LengthFormat.length(scale.toMm(measurement.deltaX.toDouble()))
            val dy = LengthFormat.length(scale.toMm(measurement.deltaY.toDouble()))
            val dz = LengthFormat.length(scale.toMm(measurement.deltaZ.toDouble()))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Straighten,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.measure_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Row {
                            IconButton(
                                onClick = {
                                    val assumed = if (scale.isKnown) "" else " — $assumedNote"
                                    onCopy("Distance: $distance (ΔX: $dx, ΔY: $dy, ΔZ: $dz)$assumed")
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.measure_copy), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.measure_clear), modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = distance,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.accentText,
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeltaBadge(label = "ΔX", value = dx, modifier = Modifier.weight(1f))
                        DeltaBadge(label = "ΔY", value = dy, modifier = Modifier.weight(1f))
                        DeltaBadge(label = "ΔZ", value = dz, modifier = Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(8.dp))
                    MeasureOptions(scale, declaredUnit, snap, onToggleSnap, onUnitChoice)

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onClear, modifier = Modifier.height(36.dp)) {
                            Text(stringResource(R.string.measure_clear))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onExit, modifier = Modifier.height(36.dp)) {
                            Text(stringResource(R.string.measure_done))
                        }
                    }
                }
            }
        } else {
            val instruction = if (measurePoint1 == null) {
                stringResource(R.string.measure_instruction_p1)
            } else {
                stringResource(R.string.measure_instruction_p2)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Straighten,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        // The text gives way (wraps) so Clear and Close always stay whole next to the view rail.
                        Text(
                            text = instruction,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(12.dp))
                        if (measurePoint1 != null) {
                            TextButton(colors = accentTextButtonColors(), onClick = onClear, modifier = Modifier.height(32.dp)) {
                                Text(stringResource(R.string.measure_clear), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        IconButton(onClick = onExit, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.measure_done), modifier = Modifier.size(16.dp))
                        }
                    }
                }
                // Snap and Units are needed before the first tap too (snap decides what the tap picks).
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                ) {
                    Box(Modifier.padding(horizontal = 10.dp)) {
                        MeasureOptions(scale, declaredUnit, snap, onToggleSnap, onUnitChoice)
                    }
                }
            }
        }
    }
}

/** The Snap chip, the Units chip with its menu, and where the units come from. */
@Composable
private fun MeasureOptions(
    scale: ModelScale,
    declaredUnit: LengthUnit?,
    snap: Boolean,
    onToggleSnap: () -> Unit,
    onUnitChoice: (LengthUnit?) -> Unit,
) {
    val source = stringResource(
        when (scale.source) {
            UnitSource.FILE -> R.string.unit_source_file
            UnitSource.USER -> R.string.unit_source_user
            UnitSource.ASSUMED -> R.string.unit_source_assumed
        },
    )
    val snapDesc = stringResource(R.string.measure_snap_desc)
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = snap,
            onClick = onToggleSnap,
            label = { Text(stringResource(R.string.measure_snap)) },
            leadingIcon = if (snap) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
            modifier = Modifier.semantics { contentDescription = snapDesc },
        )
        Box {
            val unitsDesc = stringResource(R.string.measure_units_desc, scale.unit.symbol, source)
            AssistChip(
                onClick = { menuOpen = true },
                label = { Text(stringResource(R.string.measure_units_chip, scale.unit.symbol)) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.semantics { contentDescription = unitsDesc },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val resetLabel = if (declaredUnit != null) {
                    stringResource(R.string.units_as_in_file, declaredUnit.symbol)
                } else {
                    stringResource(R.string.units_not_specified)
                }
                UnitMenuItem(resetLabel, selected = scale.source != UnitSource.USER) {
                    menuOpen = false; onUnitChoice(null)
                }
                for (unit in MENU_UNITS) {
                    UnitMenuItem(stringResource(unit.labelRes()), selected = scale.source == UnitSource.USER && scale.unit == unit) {
                        menuOpen = false; onUnitChoice(unit)
                    }
                }
            }
        }
        Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnitMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.accentText)
            else Spacer(Modifier.size(24.dp))
        },
    )
}

private val MENU_UNITS = listOf(
    LengthUnit.MILLIMETER, LengthUnit.CENTIMETER, LengthUnit.METER, LengthUnit.INCH, LengthUnit.FOOT, LengthUnit.MICRON,
)

private fun LengthUnit.labelRes(): Int = when (this) {
    LengthUnit.MICRON -> R.string.unit_micron
    LengthUnit.MILLIMETER -> R.string.unit_millimeter
    LengthUnit.CENTIMETER -> R.string.unit_centimeter
    LengthUnit.METER -> R.string.unit_meter
    LengthUnit.INCH -> R.string.unit_inch
    LengthUnit.FOOT -> R.string.unit_foot
}

@Composable
private fun DeltaBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            // Long values ("8000.00 mm") shrink to fit instead of losing their unit to truncation.
            val style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BasicText(
                text = value,
                style = style,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize),
            )
        }
    }
}
