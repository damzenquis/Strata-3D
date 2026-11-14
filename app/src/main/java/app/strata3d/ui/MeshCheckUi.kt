package app.strata3d.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.strata3d.R
import app.strata3d.diagnostics.Certainty
import app.strata3d.diagnostics.DiagnosticOverlay
import app.strata3d.diagnostics.IssueType
import app.strata3d.diagnostics.Severity
import app.strata3d.render.DiagnosticColors
import app.strata3d.ui.theme.accentText
import app.strata3d.ui.theme.accentTextButtonColors
import java.text.NumberFormat

private fun rgb(c: FloatArray) = Color(c[0], c[1], c[2])

/** Colour swatch used in 3D for a finding type, or null when that type is not drawn. */
private fun IssueType.swatch(): Color? = when (this) {
    IssueType.BOUNDARY_EDGES -> rgb(DiagnosticColors.BOUNDARY)
    IssueType.NON_MANIFOLD_EDGES -> rgb(DiagnosticColors.NON_MANIFOLD)
    IssueType.FLIPPED_TRIANGLES -> rgb(DiagnosticColors.FLIPPED)
    else -> null
}

private fun IssueType.titleRes(): Int = when (this) {
    IssueType.BOUNDARY_EDGES -> R.string.diag_open_edges
    IssueType.NON_MANIFOLD_EDGES -> R.string.diag_non_manifold
    IssueType.FLIPPED_TRIANGLES -> R.string.diag_flipped
    IssueType.DEGENERATE_TRIANGLES -> R.string.diag_degenerate
    IssueType.NON_ORIENTABLE -> R.string.diag_non_orientable
    IssueType.INVERTED_SHELL -> R.string.diag_inverted
    IssueType.INTERNAL_CAVITY -> R.string.diag_cavity
}

/** The "Mesh check" section of Model info: status, findings and the 3D highlight switch. */
@Composable
internal fun MeshCheckSection(
    state: DiagnosticsState,
    showIn3d: Boolean,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onToggleIn3d: () -> Unit,
) {
    Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    when (state) {
        DiagnosticsState.NotAnalyzed -> {
            Text(stringResource(R.string.diag_not_analyzed), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.diag_not_analyzed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onAnalyze) { Text(stringResource(R.string.diag_analyze)) }
        }
        DiagnosticsState.Analyzing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.diag_analyzing), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(colors = accentTextButtonColors(), onClick = onCancel) { Text(stringResource(R.string.diag_cancel)) }
        }
        is DiagnosticsState.Failed -> {
            Text(stringResource(R.string.diag_failed), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(colors = accentTextButtonColors(), onClick = onAnalyze) { Text(stringResource(R.string.diag_retry)) }
        }
        is DiagnosticsState.Done -> {
            val r = state.report
            val number = NumberFormat.getIntegerInstance()
            Text(
                stringResource(if (r.hasIssues) R.string.diag_issues_found else R.string.diag_no_issues),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (r.hasIssues) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.accentText,
            )
            Text(
                stringResource(
                    R.string.diag_summary,
                    pluralStringResource(R.plurals.diag_parts, r.shells.size, r.shells.size),
                    pluralStringResource(R.plurals.diag_vertices, r.vertexCount, number.format(r.vertexCount)),
                    r.elapsedMs.toInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (f in r.findings) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val swatch = f.type.swatch()
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(swatch ?: Color.Transparent, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(f.type.titleRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (f.severity == Severity.INFO) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (f.certainty == Certainty.UNCERTAIN) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Text(
                                stringResource(R.string.diag_possible),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(number.format(f.count), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    f.explanation + if (f.type == IssueType.DEGENERATE_TRIANGLES) " " + stringResource(R.string.diag_not_highlighted) + "." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp),
                )
            }
            if (state.overlay != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.diag_show_in_3d), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = showIn3d, onCheckedChange = { onToggleIn3d() })
                }
                if (state.overlay.truncated) {
                    Text(stringResource(R.string.diag_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Small legend for the 3D highlights, listing only the kinds that are drawn. */
@Composable
internal fun DiagnosticsLegend(overlay: DiagnosticOverlay, modifier: Modifier = Modifier) {
    val items = buildList {
        if (overlay.boundaryLines.isNotEmpty()) add(IssueType.BOUNDARY_EDGES)
        if (overlay.nonManifoldLines.isNotEmpty()) add(IssueType.NON_MANIFOLD_EDGES)
        if (overlay.flippedTriangles.isNotEmpty()) add(IssueType.FLIPPED_TRIANGLES)
    }
    if (items.isEmpty()) return
    val desc = stringResource(R.string.diag_legend_desc)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        modifier = modifier.semantics { contentDescription = desc },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (type in items) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 4.dp).background(type.swatch()!!, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(type.titleRes()), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The warning dot on the Model info button when the mesh check found issues. */
@Composable
internal fun IssueDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
}
