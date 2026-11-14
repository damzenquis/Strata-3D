package app.strata3d.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.strata3d.R
import app.strata3d.model.BoundingBox
import app.strata3d.model.LengthFormat
import app.strata3d.model.ModelScale
import app.strata3d.render.SectionAxis
import app.strata3d.render.SectionPlane

/**
 * The compact Section bar shown above the toolbar while cutting: axis chips, flip, close, and a slider with the
 * plane's position in real units (the same world coordinates and units as measurements).
 */
@Composable
internal fun SectionBar(
    section: SectionState,
    bounds: BoundingBox,
    scale: ModelScale,
    onAxis: (SectionAxis) -> Unit,
    onFraction: (Float) -> Unit,
    onFlip: () -> Unit,
    onClose: () -> Unit,
    /** One line explaining why the cut is not filled (or that it is being checked), or null when it is filled. */
    fillStatus: String?,
    modifier: Modifier = Modifier,
) {
    val plane = SectionPlane.at(bounds, section.axis, section.fraction, section.flipped)
    val positionText = "${section.axis.name} ${LengthFormat.length(scale.toMm(plane.position.toDouble()))}"
    val sliderDesc = stringResource(R.string.section_position_desc)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 480.dp),
    ) {
        BoxWithConstraints {
        // On narrow bars (360 dp phones with the view rail open) the title gives way to the controls; the active
        // Section button in the toolbar already says what this bar is.
        val showTitle = maxWidth >= 300.dp
        Column(Modifier.padding(start = if (showTitle) 14.dp else 8.dp, end = 4.dp, top = 4.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showTitle) {
                    Text(
                        stringResource(R.string.section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (axis in SectionAxis.entries) {
                        val desc = stringResource(R.string.section_axis_desc, axis.name)
                        FilterChip(
                            selected = section.axis == axis,
                            onClick = { onAxis(axis) },
                            label = { Text(axis.name) },
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                contentDescription = desc
                            },
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = onFlip) {
                    Icon(Icons.Outlined.SwapVert, contentDescription = stringResource(R.string.section_flip))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.section_close))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
                Slider(
                    value = section.fraction,
                    onValueChange = onFraction,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = sliderDesc
                            stateDescription = positionText
                        },
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    positionText,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.widthIn(min = 92.dp),
                    maxLines = 1,
                )
            }
            if (fillStatus != null) {
                Text(
                    fillStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(end = 10.dp, bottom = 6.dp),
                )
            }
        }
        }
    }
}

/** The plane for this section state on a model with [bounds], or null when there is no section. */
internal fun SectionState?.toPlane(bounds: BoundingBox): SectionPlane? =
    this?.let { SectionPlane.at(bounds, it.axis, it.fraction, it.flipped) }
