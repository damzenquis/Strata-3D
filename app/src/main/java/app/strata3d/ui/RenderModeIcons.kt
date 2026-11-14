package app.strata3d.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Isometric-cube icons for the three render modes (24 × 24, single tint; shading uses alpha so the icon
 * still follows the content colour).
 */
internal object RenderModeIcons {

    // Cube corners in a 24-unit box.
    private const val TX = 12f; private const val TY = 3f      // top
    private const val RX = 20f; private const val RUY = 7.5f; private const val RLY = 16.5f
    private const val LX = 4f
    private const val CX = 12f; private const val CY = 12f     // front vertical edge, top
    private const val BY = 21f                                 // bottom

    private fun PathBuilder.top() { moveTo(TX, TY); lineTo(RX, RUY); lineTo(CX, CY); lineTo(LX, RUY); close() }
    private fun PathBuilder.left() { moveTo(LX, RUY); lineTo(CX, CY); lineTo(CX, BY); lineTo(LX, RLY); close() }
    private fun PathBuilder.right() { moveTo(CX, CY); lineTo(RX, RUY); lineTo(RX, RLY); lineTo(CX, BY); close() }

    private fun builder(name: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)

    /** Shaded solid: three faces at different opacities. */
    val Solid: ImageVector by lazy {
        builder("RenderSolid")
            .path(fill = SolidColor(Color.Black), fillAlpha = 1f) { top() }
            .path(fill = SolidColor(Color.Black), fillAlpha = 0.72f) { left() }
            .path(fill = SolidColor(Color.Black), fillAlpha = 0.48f) { right() }
            .build()
    }

    /** Solid with edges: light faces plus full-strength outlines. */
    val Edges: ImageVector by lazy {
        val stroke = SolidColor(Color.Black)
        builder("RenderEdges")
            .path(fill = SolidColor(Color.Black), fillAlpha = 0.28f) { top() }
            .path(fill = SolidColor(Color.Black), fillAlpha = 0.20f) { left() }
            .path(fill = SolidColor(Color.Black), fillAlpha = 0.12f) { right() }
            .path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(TX, TY); lineTo(RX, RUY); lineTo(RX, RLY); lineTo(CX, BY); lineTo(LX, RLY); lineTo(LX, RUY); close()
                moveTo(LX, RUY); lineTo(CX, CY); lineTo(RX, RUY)
                moveTo(CX, CY); lineTo(CX, BY)
            }
            .build()
    }

    /** Section: a cube cut by a horizontal plane; the removed top half is faint. */
    val Section: ImageVector by lazy {
        val midY = (RUY + RLY) / 2f // 12: the cube's side edges at half height
        val frontMid = (CY + BY) / 2f // 16.5
        builder("Section")
            .path(stroke = SolidColor(Color.Black), strokeAlpha = 0.4f, strokeLineWidth = 1.4f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(TX, TY); lineTo(RX, RUY); lineTo(RX, midY)
                moveTo(LX, midY); lineTo(LX, RUY); lineTo(TX, TY)
                moveTo(CX, CY); lineTo(CX, frontMid)
            }
            .path(fill = SolidColor(Color.Black), fillAlpha = 0.85f) {
                moveTo(LX, midY); lineTo(CX, midY - (RLY - RUY) / 2f); lineTo(RX, midY); lineTo(CX, frontMid); close()
            }
            .path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineJoin = StrokeJoin.Round) {
                moveTo(LX, midY); lineTo(LX, RLY); lineTo(CX, BY); lineTo(RX, RLY); lineTo(RX, midY)
                moveTo(CX, frontMid); lineTo(CX, BY)
            }
            .build()
    }

    /** Wireframe: outlines plus a triangulating diagonal on each face, no fill. */
    val Wire: ImageVector by lazy {
        builder("RenderWire")
            .path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f, strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round) {
                moveTo(TX, TY); lineTo(RX, RUY); lineTo(RX, RLY); lineTo(CX, BY); lineTo(LX, RLY); lineTo(LX, RUY); close()
                moveTo(LX, RUY); lineTo(CX, CY); lineTo(RX, RUY)
                moveTo(CX, CY); lineTo(CX, BY)
            }
            .path(stroke = SolidColor(Color.Black), strokeAlpha = 0.6f, strokeLineWidth = 1f, strokeLineCap = StrokeCap.Round) {
                moveTo(TX, TY); lineTo(CX, CY)       // top face diagonal
                moveTo(LX, RUY); lineTo(CX, BY)      // left face diagonal
                moveTo(RX, RUY); lineTo(CX, BY)      // right face diagonal
            }
            .build()
    }
}
