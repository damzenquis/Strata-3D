package app.strata3d.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import app.strata3d.diagnostics.DiagnosticOverlay
import app.strata3d.model.BoundingBox
import app.strata3d.model.Mesh
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

enum class RenderMode(internal val shaderValue: Int) { SHADED(0), SHADED_EDGES(1), WIREFRAME(2) }

/**
 * Draws a [Mesh] plus a build-plate grid. Public setters may be called from any thread; all GL work happens on
 * the GL thread inside the [GLSurfaceView.Renderer] callbacks.
 */
class ModelRenderer(private val camera: OrbitCamera) : GLSurfaceView.Renderer {

    @Volatile var renderMode = RenderMode.SHADED
    /** Show photo colours for models that have them (otherwise the plain model colour). */
    @Volatile var photoColours = true
    @Volatile var showGrid = true
    @Volatile var modelColor = floatArrayOf(0.36f, 0.60f, 0.90f)
    @Volatile var backgroundColor = floatArrayOf(0.12f, 0.12f, 0.14f)
    @Volatile var darkBackground = true

    /** Invoked on the GL thread when the GPU cannot hold the model. */
    @Volatile var onError: ((String) -> Unit)? = null

    /** Invoked on the GL thread when a camera transition needs another frame. */
    @Volatile var onNeedsFrame: (() -> Unit)? = null

    /** Called on the GL thread once the surface exists, with whether section cuts can be filled. */
    @Volatile var onStencilSupport: ((Boolean) -> Unit)? = null

    /** GPU size limits, available once the surface has been created. */
    @Volatile var gpuLimits: GpuLimits? = null
        private set

    private var surfaceWidth = 1
    private var surfaceHeight = 1

    private val lock = Any()
    private var mesh: Mesh? = null
    private var meshDirty = false

    /** A linked mesh program and its uniform locations. */
    private class MeshProgram(val id: Int) {
        val view = GLES30.glGetUniformLocation(id, "uView")
        val mvp = GLES30.glGetUniformLocation(id, "uMvp")
        val color = GLES30.glGetUniformLocation(id, "uColor")
        val edgeColor = GLES30.glGetUniformLocation(id, "uEdgeColor")
        val mode = GLES30.glGetUniformLocation(id, "uMode")
        val ortho = GLES30.glGetUniformLocation(id, "uOrtho")
        val edgeScale = GLES30.glGetUniformLocation(id, "uEdgeScale")
        val textured = GLES30.glGetUniformLocation(id, "uTextured")
        val tex = GLES30.glGetUniformLocation(id, "uTex")
        val sectionPlane = GLES30.glGetUniformLocation(id, "uSectionPlane")
    }

    private var meshProgram: MeshProgram? = null
    /** Created on first use of a section, so normal viewing never pays for it. */
    private var sectionProgram: MeshProgram? = null
    /** Line program clipped by the section, for mesh-check highlights; created on first use. */
    private var sectionLineProgram = 0
    private var uSectionLineMvp = 0
    private var uSectionLineColor = 0
    private var uSectionLinePlane = 0

    /** Minimal program for the capping count pass (see [drawCap]); created on first use. */
    private var countProgram = 0
    private var uCountMvp = 0
    private var uCountPlane = 0

    /** Section plane equation (see [SectionPlane.equation]), or null for none. Set from any thread. */
    @Volatile
    private var sectionPlane: FloatArray? = null

    /** Cap quad on the section plane (see [SectionCap]), or null when the cut is not filled. Set from any thread. */
    @Volatile
    private var capQuad: FloatArray? = null
    private var uploadedCap: FloatArray? = null
    private var capVao = 0
    private var capVbo = 0

    /** True when the surface has a stencil buffer, so section cuts can be filled. Known after surface creation. */
    @Volatile
    var hasStencil: Boolean = false
        private set

    private var lineProgram = 0
    private var clipProgram = 0
    private var uClipColor = 0

    /** CPU copy of the grid line vertices (x, y, z per vertex), used to build snapshot grid quads. */
    private var gridLines: FloatArray? = null
    private var uLineMvp = 0
    private var uLineColor = 0

    private var meshVao = 0
    private var meshVbo = 0
    private var uvVbo = 0
    /** GL textures of the photo colours, one per [colourParts] entry (same order); empty for plain models. */
    private var colourTextures = IntArray(0)
    private var colourParts: List<app.strata3d.model.MeshColours.ColourPart> = emptyList()
    private var meshVertexCount = 0

    private var gridVao = 0
    private var gridVbo = 0
    private var gridMinorVertices = 0
    private var gridMajorVertices = 0

    private var measureVao = 0
    private var measureVbo = 0
    private var measureVertexCount = 0
    /** Vertices of filled (snapped-vertex) marker triangles, stored after the [measureVertexCount] line vertices. */
    private var measureFillCount = 0
    private var measurePoint1: FloatArray? = null

    // Mesh-check highlights: [boundary lines | non-manifold lines | flipped triangles] in one buffer.
    private var diagVao = 0
    private var diagVbo = 0
    private var diagBoundaryVertices = 0
    private var diagNonManifoldVertices = 0
    private var diagFlippedVertices = 0
    private var diagOverlay: DiagnosticOverlay? = null
    private var diagDirty = false
    private var lineWidth = 1f
    private var measurePoint2: FloatArray? = null
    private var measureSnapped1 = false
    private var measureSnapped2 = false
    private var measureDirty = false

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val mvp = FloatArray(16)
    private val eye = FloatArray(3)

    fun setMesh(newMesh: Mesh?) {
        synchronized(lock) {
            mesh = newMesh
            meshDirty = true
        }
    }

    /** Measurement points; a snapped point (on a vertex) gets a filled marker, a free surface point a hollow one. */
    fun setMeasurement(p1: FloatArray?, p2: FloatArray?, snapped1: Boolean = false, snapped2: Boolean = false) {
        synchronized(lock) {
            measurePoint1 = p1?.clone()
            measurePoint2 = p2?.clone()
            measureSnapped1 = snapped1
            measureSnapped2 = snapped2
            measureDirty = true
        }
    }

    /**
     * Cuts the model with [plane] (null = whole model) and, when [fill] is true and the device has a stencil
     * buffer, fills the cut face. The mesh data is untouched; snapshots show the same cut.
     */
    fun setSection(plane: SectionPlane?, fill: Boolean = false) {
        val bounds = synchronized(lock) { mesh?.bounds }
        capQuad = if (plane != null && fill && bounds != null) SectionCap.quad(bounds, plane) else null
        sectionPlane = plane?.equation()
    }

    /** Highlights for mesh-check findings, or null for none. Drawn over the model; never part of snapshots. */
    fun setDiagnosticOverlay(overlay: DiagnosticOverlay?) {
        synchronized(lock) {
            if (diagOverlay === overlay) return
            diagOverlay = overlay
            diagDirty = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context invalidates every previous GL object handle.
        meshVao = 0; meshVbo = 0; meshVertexCount = 0
        uvVbo = 0; colourTextures = IntArray(0); colourParts = emptyList()
        gridVao = 0; gridVbo = 0; gridMinorVertices = 0; gridMajorVertices = 0
        measureVao = 0; measureVbo = 0; measureVertexCount = 0
        diagVao = 0; diagVbo = 0; diagBoundaryVertices = 0; diagNonManifoldVertices = 0; diagFlippedVertices = 0
        capVao = 0; capVbo = 0; uploadedCap = null

        clipProgram = 0 // recreated lazily for snapshots in the new context
        meshProgram = MeshProgram(GlUtil.createProgram(Shaders.MESH_VERTEX, Shaders.MESH_FRAGMENT))
        sectionProgram = null // recreated on demand in the new context
        countProgram = 0
        sectionLineProgram = 0

        lineProgram = GlUtil.createProgram(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT)
        uLineMvp = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        uLineColor = GLES30.glGetUniformLocation(lineProgram, "uColor")

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE) // STL winding is often unreliable: render both sides
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val v = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, v, 0)
        val maxRenderbuffer = v[0]
        GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, v, 0)
        val maxViewportW = v[0]
        val maxViewportH = v[1]
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, v, 0)
        gpuLimits = GpuLimits(maxRenderbuffer, maxViewportW, maxViewportH, v[0])
        GLES30.glGetIntegerv(GLES30.GL_STENCIL_BITS, v, 0)
        hasStencil = v[0] >= 8
        onStencilSupport?.invoke(hasStencil)
        val widths = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_LINE_WIDTH_RANGE, widths, 0)
        lineWidth = HIGHLIGHT_LINE_WIDTH.coerceIn(1f, maxOf(1f, widths[1]))

        synchronized(lock) {
            meshDirty = true
            measureDirty = true
            diagDirty = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadMeshIfDirty()
        uploadMeasurementIfDirty()
        uploadDiagnosticsIfDirty()

        val bg = backgroundColor
        GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
        val cap = capQuad.takeIf { hasStencil && sectionPlane != null && renderMode != RenderMode.WIREFRAME }
        GLES30.glClear(
            GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or (if (cap != null) GLES30.GL_STENCIL_BUFFER_BIT else 0),
        )

        val stillAnimating = camera.advance()
        camera.computeMatrices(view, projection, eye)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        if (meshVertexCount > 0) {
            drawMesh()
            if (cap != null) drawCap(cap)
        }
        if (showGrid && gridMinorVertices + gridMajorVertices > 0) drawGrid()
        if (diagBoundaryVertices + diagNonManifoldVertices + diagFlippedVertices > 0) drawDiagnostics()
        if (measureVertexCount > 0) drawMeasurement()
        if (stillAnimating) onNeedsFrame?.invoke()
    }

    private fun drawMesh(edgeScale: Float = 1f) {
        val mode = renderMode
        val plane = sectionPlane
        val program = if (plane == null) meshProgram!! else sectionProgram ?: MeshProgram(
            GlUtil.createProgram(Shaders.withSection(Shaders.MESH_VERTEX), Shaders.withSection(Shaders.MESH_FRAGMENT)),
        ).also { sectionProgram = it }
        GLES30.glUseProgram(program.id)
        GLES30.glUniformMatrix4fv(program.view, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(program.mvp, 1, false, mvp, 0)
        val c = modelColor
        GLES30.glUniform3f(program.color, c[0], c[1], c[2])
        GLES30.glUniform3f(program.edgeColor, 0.08f, 0.09f, 0.11f)
        GLES30.glUniform1i(program.mode, mode.shaderValue)
        GLES30.glUniform1i(program.ortho, if (camera.projection == Projection.ORTHOGRAPHIC) 1 else 0)
        GLES30.glUniform1f(program.edgeScale, edgeScale)
        if (plane != null) GLES30.glUniform4f(program.sectionPlane, plane[0], plane[1], plane[2], plane[3])
        if (mode == RenderMode.WIREFRAME) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(meshVao)
        if (photoColours && colourTextures.isNotEmpty()) {
            GLES30.glUniform1i(program.textured, 1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glUniform1i(program.tex, 0)
            colourParts.forEachIndexed { i, part ->
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colourTextures[i])
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, part.first, part.count)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        } else {
            GLES30.glUniform1i(program.textured, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, meshVertexCount)
        }
        GLES30.glBindVertexArray(0)
    }

    /**
     * Fills the cut face (see [SectionCap]). Call right after the clipped mesh has been drawn, with the stencil
     * buffer cleared to 0.
     */
    private fun drawCap(quad: FloatArray) {
        val plane = sectionPlane ?: return // read once, before any GL state changes
        // 1. Winding count of the kept surfaces behind every pixel: front faces +1, back faces -1, at all depths.
        GLES30.glEnable(GLES30.GL_STENCIL_TEST)
        GLES30.glColorMask(false, false, false, false)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glStencilFunc(GLES30.GL_ALWAYS, 0, 0xFF)
        GLES30.glStencilOpSeparate(GLES30.GL_FRONT, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_INCR_WRAP)
        GLES30.glStencilOpSeparate(GLES30.GL_BACK, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_DECR_WRAP)
        if (countProgram == 0) {
            countProgram = GlUtil.createProgram(Shaders.SECTION_COUNT_VERTEX, Shaders.SECTION_COUNT_FRAGMENT)
            uCountMvp = GLES30.glGetUniformLocation(countProgram, "uMvp")
            uCountPlane = GLES30.glGetUniformLocation(countProgram, "uSectionPlane")
        }
        GLES30.glUseProgram(countProgram)
        GLES30.glUniformMatrix4fv(uCountMvp, 1, false, mvp, 0)
        GLES30.glUniform4f(uCountPlane, plane[0], plane[1], plane[2], plane[3])
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(meshVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, meshVertexCount)
        GLES30.glBindVertexArray(0)

        // 2. The cap quad where the count is not zero, depth-tested so nearer kept geometry still hides it.
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glStencilFunc(GLES30.GL_NOTEQUAL, 0, 0xFF)
        GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_KEEP)
        if (uploadedCap !== quad || capVao == 0) {
            if (capVao == 0) {
                val ids = IntArray(1)
                GLES30.glGenVertexArrays(1, ids, 0); capVao = ids[0]
                GLES30.glGenBuffers(1, ids, 0); capVbo = ids[0]
            }
            val buffer = java.nio.ByteBuffer.allocateDirect(quad.size * 4).order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer().put(quad).apply { position(0) }
            uploadPositions(capVao, capVbo, buffer, quad.size / 3)
            uploadedCap = quad
        }
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        val c = SectionCap.color(modelColor)
        GLES30.glUniform4f(uLineColor, c[0], c[1], c[2], 1f)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(capVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, quad.size / 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
    }

    private fun drawGrid() {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        GLES30.glBindVertexArray(gridVao)
        val v = if (darkBackground) 1f else 0f
        GLES30.glUniform4f(uLineColor, v, v, v, 0.10f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridMinorVertices)
        GLES30.glUniform4f(uLineColor, v, v, v, 0.28f)
        GLES30.glDrawArrays(GLES30.GL_LINES, gridMinorVertices, gridMajorVertices)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun uploadMeshIfDirty() {
        val current: Mesh?
        synchronized(lock) {
            if (!meshDirty) return
            meshDirty = false
            current = mesh
        }
        deleteBuffers()
        if (current == null) return

        val ids = IntArray(2)
        GLES30.glGenVertexArrays(2, ids, 0)
        meshVao = ids[0]; gridVao = ids[1]
        GLES30.glGenBuffers(2, ids, 0)
        meshVbo = ids[0]; gridVbo = ids[1]

        var drained = 0
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR && drained++ < 16) { /* clear stale errors */ }
        val data = current.vertices.duplicate().apply { position(0) }
        uploadPositions(meshVao, meshVbo, data, current.vertexCount)
        if (GLES30.glGetError() == GLES30.GL_OUT_OF_MEMORY) {
            deleteBuffers()
            onError?.invoke("Not enough GPU memory to display this model")
            return
        }
        meshVertexCount = current.vertexCount
        current.colours?.let { uploadColours(it) }

        val (grid, minorVertices) = buildGrid(current.bounds)
        gridLines = grid
        val gridBuffer = java.nio.ByteBuffer.allocateDirect(grid.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(grid).apply { position(0) }
        uploadPositions(gridVao, gridVbo, gridBuffer, grid.size / 3)
        gridMinorVertices = minorVertices
        gridMajorVertices = grid.size / 3 - minorVertices
    }

    private fun uploadMeasurementIfDirty() {
        val p1: FloatArray?
        val p2: FloatArray?
        val s1: Boolean
        val s2: Boolean
        val radius: Float
        synchronized(lock) {
            if (!measureDirty) return
            measureDirty = false
            p1 = measurePoint1
            p2 = measurePoint2
            s1 = measureSnapped1
            s2 = measureSnapped2
            radius = mesh?.bounds?.radius ?: 50f
        }

        deleteMeasureBuffers()
        if (p1 == null && p2 == null) return

        val lines = buildMeasurementGeometry(p1, p2, radius)
        val fill = buildMarkerFill(listOfNotNull(p1?.takeIf { s1 }, p2?.takeIf { s2 }), radius)
        val geometry = lines + fill
        if (geometry.isEmpty()) return

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        measureVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        measureVbo = ids[0]

        val buffer = java.nio.ByteBuffer.allocateDirect(geometry.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(geometry).apply { position(0) }
        uploadPositions(measureVao, measureVbo, buffer, geometry.size / 3)
        measureVertexCount = lines.size / 3
        measureFillCount = fill.size / 3
    }

    private fun uploadDiagnosticsIfDirty() {
        val overlay: DiagnosticOverlay?
        synchronized(lock) {
            if (!diagDirty) return
            diagDirty = false
            overlay = diagOverlay
        }
        deleteDiagnosticBuffers()
        if (overlay == null || overlay.isEmpty) return
        val total = overlay.boundaryLines.size + overlay.nonManifoldLines.size + overlay.flippedTriangles.size
        val buffer = java.nio.ByteBuffer.allocateDirect(total * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            .put(overlay.boundaryLines).put(overlay.nonManifoldLines).put(overlay.flippedTriangles).apply { position(0) }
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); diagVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); diagVbo = ids[0]
        uploadPositions(diagVao, diagVbo, buffer, total / 3)
        diagBoundaryVertices = overlay.boundaryLines.size / 3
        diagNonManifoldVertices = overlay.nonManifoldLines.size / 3
        diagFlippedVertices = overlay.flippedTriangles.size / 3
    }

    /** Colour uniform of the program [drawDiagnostics] uses (plain lines, or lines clipped by the section). */
    private var uDiagColor = 0

    private fun drawDiagnostics() {
        // Highlights follow the section like the model: problems in the cut-away half are not shown.
        val plane = sectionPlane
        if (plane == null) {
            GLES30.glUseProgram(lineProgram)
            GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
            uDiagColor = uLineColor
        } else {
            if (sectionLineProgram == 0) {
                sectionLineProgram = GlUtil.createProgram(Shaders.withSection(Shaders.LINE_VERTEX), Shaders.withSection(Shaders.LINE_FRAGMENT))
                uSectionLineMvp = GLES30.glGetUniformLocation(sectionLineProgram, "uMvp")
                uSectionLineColor = GLES30.glGetUniformLocation(sectionLineProgram, "uColor")
                uSectionLinePlane = GLES30.glGetUniformLocation(sectionLineProgram, "uSectionPlane")
            }
            GLES30.glUseProgram(sectionLineProgram)
            GLES30.glUniformMatrix4fv(uSectionLineMvp, 1, false, mvp, 0)
            GLES30.glUniform4f(uSectionLinePlane, plane[0], plane[1], plane[2], plane[3])
            uDiagColor = uSectionLineColor
        }
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(diagVao)
        val lines = diagBoundaryVertices + diagNonManifoldVertices

        // Flipped triangles: tinted on top of their own surface (polygon offset avoids z-fighting).
        if (diagFlippedVertices > 0) {
            GLES30.glDepthMask(false)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-1f, -2f)
            val f = DiagnosticColors.FLIPPED
            GLES30.glUniform4f(uDiagColor, f[0], f[1], f[2], 0.8f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, lines, diagFlippedVertices)
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glDepthMask(true)
        }
        if (lines > 0) {
            GLES30.glLineWidth(lineWidth)
            // Pass 1: faint through the model, so hidden problems are still noticeable.
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            drawDiagnosticLines(0.3f)
            // Pass 2: full colour where visible.
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            drawDiagnosticLines(1f)
            GLES30.glLineWidth(1f)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawDiagnosticLines(alpha: Float) {
        val b = DiagnosticColors.BOUNDARY
        if (diagBoundaryVertices > 0) {
            GLES30.glUniform4f(uDiagColor, b[0], b[1], b[2], alpha)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, diagBoundaryVertices)
        }
        val n = DiagnosticColors.NON_MANIFOLD
        if (diagNonManifoldVertices > 0) {
            GLES30.glUniform4f(uDiagColor, n[0], n[1], n[2], alpha)
            GLES30.glDrawArrays(GLES30.GL_LINES, diagBoundaryVertices, diagNonManifoldVertices)
        }
    }

    private fun deleteDiagnosticBuffers() {
        if (diagVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(diagVbo), 0)
        if (diagVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(diagVao), 0)
        diagVao = 0; diagVbo = 0; diagBoundaryVertices = 0; diagNonManifoldVertices = 0; diagFlippedVertices = 0
    }

    private fun drawMeasurement() {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(measureVao)

        // Pass 1: Semi-transparent guide through the model (X-ray pass)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUniform4f(uLineColor, 1.0f, 0.72f, 0.12f, 0.35f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, measureVertexCount)
        if (measureFillCount > 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, measureVertexCount, measureFillCount)

        // Pass 2: Solid bright line in front of the model
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glUniform4f(uLineColor, 1.0f, 0.76f, 0.15f, 1.0f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, measureVertexCount)
        if (measureFillCount > 0) GLES30.glDrawArrays(GLES30.GL_TRIANGLES, measureVertexCount, measureFillCount)

        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /**
     * Renders the current view into an off-screen image of exactly [request]'s size. GL thread only. The visible
     * surface, its viewport and its framebuffer are left untouched; every GL object created here is released,
     * whether rendering succeeds or fails.
     */
    fun renderSnapshot(request: SnapshotRequest): SnapshotPixels {
        val limits = gpuLimits ?: throw SnapshotException("The 3D view is not ready yet")
        val w = request.width
        val h = request.height
        if (!limits.fits(w, h)) {
            throw SnapshotException("$w × $h is larger than this device's GPU supports (${limits.maxWidth} × ${limits.maxHeight} max)")
        }
        uploadMeshIfDirty()
        if (meshVertexCount == 0) throw SnapshotException("There is no model to capture")

        val pixels = try {
            java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        } catch (e: OutOfMemoryError) {
            throw SnapshotException("Not enough memory for a $w × $h image")
        }

        val fbo = IntArray(2) // [multisampled, resolved]
        val rbo = IntArray(3) // [MSAA colour, MSAA depth, resolved colour]
        try {
            var drained = 0
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR && drained++ < 16) { /* clear stale errors */ }
            GLES30.glGenFramebuffers(2, fbo, 0)
            GLES30.glGenRenderbuffers(3, rbo, 0)
            val samples = minOf(4, limits.maxSamples).coerceAtLeast(0)

            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo[0])
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, w, h)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo[1])
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH24_STENCIL8, w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rbo[0])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT, GLES30.GL_RENDERBUFFER, rbo[1])
            checkFramebuffer(w, h)

            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo[2])
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[1])
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rbo[2])
            checkFramebuffer(w, h)

            // Draw the scene.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glViewport(0, 0, w, h)
            val bg = request.background
            if (request.transparent) GLES30.glClearColor(0f, 0f, 0f, 0f) else GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
            camera.computeSnapshotMatrices(view, projection, eye)
            Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
            // Keep destination alpha correct (premultiplied) when blending onto a transparent background.
            GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            drawMesh(request.edgeScale)
            // Filled exactly when it is filled on screen (the off-screen buffer always has a stencil, the screen may not).
            capQuad.takeIf { hasStencil && sectionPlane != null && renderMode != RenderMode.WIREFRAME }?.let { drawCap(it) }
            if (!request.transparent && request.showGrid && gridMinorVertices + gridMajorVertices > 0) {
                drawSnapshotGrid(w, h, request.edgeScale, bg)
            }
            // Measurement guides are interaction overlays and are never exported.

            // Resolve multisampling and read back.
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[0])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo[1])
            GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[1])
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            when (GLES30.glGetError()) {
                GLES30.GL_NO_ERROR -> Unit
                GLES30.GL_OUT_OF_MEMORY -> throw SnapshotException("The GPU ran out of memory rendering a $w × $h image")
                else -> throw SnapshotException("The GPU could not render a $w × $h image")
            }
            pixels.position(0)
            return SnapshotPixels(w, h, pixels)
        } finally {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
            GLES30.glDeleteFramebuffers(2, fbo, 0)
            GLES30.glDeleteRenderbuffers(3, rbo, 0)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }
    }

    /**
     * Grid for snapshots, drawn as thin screen-space quads instead of GL lines. Line rasterisation into
     * multisampled off-screen buffers is unreliable on some GPUs (lines go missing), and most GPUs cannot draw
     * lines wider than 1 px, which nearly vanish at 4K. Triangles avoid both problems: each grid segment is
     * projected on the CPU, clipped at the near plane, and widened to [scale] pixels. Colours are pre-blended
     * over the solid background and drawn opaque, so the result matches the on-screen grid.
     */
    private fun drawSnapshotGrid(width: Int, height: Int, scale: Float, background: FloatArray) {
        val lines = gridLines ?: return
        val v = if (darkBackground) 1f else 0f
        fun mixed(alpha: Float) = FloatArray(3) { background[it] * (1f - alpha) + v * alpha }
        val halfWidthPx = 0.5f * scale.coerceAtLeast(1f)

        if (clipProgram == 0) {
            clipProgram = GlUtil.createProgram(Shaders.CLIP_VERTEX, Shaders.LINE_FRAGMENT)
            uClipColor = GLES30.glGetUniformLocation(clipProgram, "uColor")
        }
        GLES30.glUseProgram(clipProgram)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(0)
        for ((first, count, alpha) in listOf(
            Triple(0, gridMinorVertices, 0.10f),
            Triple(gridMinorVertices, gridMajorVertices, 0.28f),
        )) {
            val quads = buildGridQuads(lines, first, count, mvp, width, height, halfWidthPx)
            if (quads.isEmpty()) continue
            val c = mixed(alpha)
            GLES30.glUniform4f(uClipColor, c[0], c[1], c[2], 1f)
            val buffer = java.nio.ByteBuffer.allocateDirect(quads.size * 4).order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer().put(quads).apply { position(0) }
            GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, buffer)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, quads.size / 4)
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDepthMask(true)
    }

    private fun checkFramebuffer(w: Int, h: Int) {
        val error = GLES30.glGetError()
        if (error == GLES30.GL_OUT_OF_MEMORY) throw SnapshotException("The GPU does not have enough memory for a $w × $h image")
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw SnapshotException("This device's GPU cannot render a $w × $h image")
        }
    }

    private fun uploadPositions(vao: Int, vbo: Int, data: java.nio.FloatBuffer, vertexCount: Int) {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexCount * 3 * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Texture coordinates (attribute 1 of the mesh VAO) and one GL texture per colour image. Images larger than the
     * GPU allows are decoded at a smaller size. If anything fails, the model shows in its plain colour.
     */
    private fun uploadColours(colours: app.strata3d.model.MeshColours) {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        uvVbo = ids[0]
        GLES30.glBindVertexArray(meshVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVbo)
        val uvs = colours.uvs.duplicate().apply { position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, uvs.remaining() * 4, uvs, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        val maxSize = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0].coerceAtLeast(2048)
        val textures = IntArray(colours.parts.size)
        GLES30.glGenTextures(textures.size, textures, 0)
        for ((i, part) in colours.parts.withIndex()) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(part.image, 0, part.image.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(part.image, 0, part.image.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp == null) {
                GLES30.glDeleteTextures(textures.size, textures, 0)
                colourTextures = IntArray(0); colourParts = emptyList()
                return
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        colourTextures = textures
        colourParts = colours.parts
    }

    private fun deleteBuffers() {
        if (uvVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(uvVbo), 0)
        uvVbo = 0
        if (colourTextures.isNotEmpty()) GLES30.glDeleteTextures(colourTextures.size, colourTextures, 0)
        colourTextures = IntArray(0); colourParts = emptyList()
        if (meshVbo != 0 || gridVbo != 0) GLES30.glDeleteBuffers(2, intArrayOf(meshVbo, gridVbo), 0)
        if (meshVao != 0 || gridVao != 0) GLES30.glDeleteVertexArrays(2, intArrayOf(meshVao, gridVao), 0)
        meshVao = 0; meshVbo = 0; meshVertexCount = 0
        gridVao = 0; gridVbo = 0; gridMinorVertices = 0; gridMajorVertices = 0
        deleteMeasureBuffers()
        deleteDiagnosticBuffers()
        if (capVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(capVbo), 0)
        if (capVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(capVao), 0)
        capVao = 0; capVbo = 0; uploadedCap = null
        synchronized(lock) { diagDirty = true } // re-upload highlights that are still switched on
    }

    private fun deleteMeasureBuffers() {
        if (measureVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(measureVbo), 0)
        if (measureVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(measureVao), 0)
        measureVao = 0; measureVbo = 0; measureVertexCount = 0; measureFillCount = 0
    }

    companion object {
        /** Pixel width of highlighted problem edges (clamped to what the GPU supports). */
        const val HIGHLIGHT_LINE_WIDTH = 3f

        /**
         * Builds GL_LINES vertices for a grid lying just under the model. Minor lines come first; returns the
         * vertex array and the number of minor-line vertices.
         */
        internal fun buildGrid(b: BoundingBox): Pair<FloatArray, Int> {
            val half = max(max(b.sizeX, b.sizeY) * 0.5f * 1.5f, b.radius)
            if (!half.isFinite() || half <= 0f) return FloatArray(0) to 0
            val step = niceStep(half * 2f / 20f)
            val cx = (b.centerX / step).roundToLong() * step
            val cy = (b.centerY / step).roundToLong() * step
            // About 10 lines either side by construction; the cap guards against any pathological bounds.
            val n = ceil(half / step).toInt().coerceIn(1, MAX_GRID_HALF_LINES)
            val z = b.minZ - b.radius * 0.002f
            val extent = n * step

            val minor = ArrayList<Float>()
            val major = ArrayList<Float>()
            for (i in -n..n) {
                val x = cx + i * step
                val y = cy + i * step
                val isMajor = ((x / step).roundToLong() % 5L == 0L)
                val isMajorY = ((y / step).roundToLong() % 5L == 0L)
                (if (isMajor) major else minor).addAll(listOf(x, cy - extent, z, x, cy + extent, z))
                (if (isMajorY) major else minor).addAll(listOf(cx - extent, y, z, cx + extent, y, z))
            }
            val out = FloatArray(minor.size + major.size)
            minor.forEachIndexed { i, f -> out[i] = f }
            major.forEachIndexed { i, f -> out[minor.size + i] = f }
            return out to minor.size / 3
        }

        private const val MAX_GRID_HALF_LINES = 64

        /** Rounds [raw] up to 1, 2 or 5 × 10^k. */
        /**
         * Expands GL_LINES segments [first, first + count) of [lines] into clip-space triangles [halfWidthPx]
         * pixels either side of each segment, for a [width] × [height] target. Segments are clipped against
         * the near plane first, so lines that pass behind the camera stay correct. Returns x, y, z, w per vertex.
         */
        internal fun buildGridQuads(
            lines: FloatArray,
            first: Int,
            count: Int,
            mvp: FloatArray,
            width: Int,
            height: Int,
            halfWidthPx: Float,
        ): FloatArray {
            val out = FloatArray((count / 2) * 6 * 4)
            var o = 0
            val a = FloatArray(4); val b = FloatArray(4)
            var i = first
            while (i + 1 < first + count) {
                transform(mvp, lines, i * 3, a)
                transform(mvp, lines, (i + 1) * 3, b)
                i += 2
                // Clip against the near plane (z + w >= 0).
                val da = a[2] + a[3]
                val db = b[2] + b[3]
                if (da < 0f && db < 0f) continue
                if (da < 0f) lerpInto(a, b, da / (da - db))
                else if (db < 0f) lerpInto(b, a, db / (db - da))
                if (a[3] <= 1e-6f || b[3] <= 1e-6f) continue
                // Direction in pixels, then a perpendicular offset expressed in NDC.
                val dx = (b[0] / b[3] - a[0] / a[3]) * width * 0.5f
                val dy = (b[1] / b[3] - a[1] / a[3]) * height * 0.5f
                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                if (len < 1e-6f) continue
                val nx = -dy / len * halfWidthPx * 2f / width
                val ny = dx / len * halfWidthPx * 2f / height
                // Two triangles: a+, a-, b+ and b+, a-, b- (offsets scaled by w to stay in clip space).
                for ((p, sign) in listOf(a to 1f, a to -1f, b to 1f, b to 1f, a to -1f, b to -1f)) {
                    out[o++] = p[0] + nx * sign * p[3]
                    out[o++] = p[1] + ny * sign * p[3]
                    out[o++] = p[2]
                    out[o++] = p[3]
                }
            }
            return out.copyOf(o)
        }

        private fun transform(m: FloatArray, v: FloatArray, offset: Int, out: FloatArray) {
            val x = v[offset]; val y = v[offset + 1]; val z = v[offset + 2]
            for (row in 0 until 4) out[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row]
        }

        /** Moves [p] to the point at parameter [t] along p→q. */
        private fun lerpInto(p: FloatArray, q: FloatArray, t: Float) {
            for (k in 0 until 4) p[k] = p[k] + (q[k] - p[k]) * t
        }

        internal fun niceStep(raw: Float): Float {
            if (raw <= 0f || !raw.isFinite()) return 1f
            val magnitude = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
            val normalized = raw / magnitude
            val nice = when {
                normalized <= 1f -> 1f
                normalized <= 2f -> 2f
                normalized <= 5f -> 5f
                else -> 10f
            }
            return nice * magnitude
        }

        /** Marker half-size in world units: proportional to the model, so it reads the same at any scale. */
        internal fun markerSize(radius: Float): Float =
            if (radius > 0f && radius.isFinite()) radius * 0.025f else 1f

        /** Filled octahedra (8 triangles each) for snapped-vertex markers. */
        internal fun buildMarkerFill(points: List<FloatArray>, radius: Float): FloatArray {
            val s = markerSize(radius)
            val out = FloatArray(points.size * 8 * 9)
            var i = 0
            for (p in points) {
                val ring = arrayOf(
                    floatArrayOf(p[0] + s, p[1], p[2]), floatArrayOf(p[0], p[1] + s, p[2]),
                    floatArrayOf(p[0] - s, p[1], p[2]), floatArrayOf(p[0], p[1] - s, p[2]),
                )
                for (apex in arrayOf(floatArrayOf(p[0], p[1], p[2] + s), floatArrayOf(p[0], p[1], p[2] - s))) {
                    for (k in 0 until 4) {
                        for (v in arrayOf(apex, ring[k], ring[(k + 1) % 4])) {
                            out[i++] = v[0]; out[i++] = v[1]; out[i++] = v[2]
                        }
                    }
                }
            }
            return out
        }

        internal fun buildMeasurementGeometry(p1: FloatArray?, p2: FloatArray?, radius: Float): FloatArray {
            val list = ArrayList<Float>()
            val s = markerSize(radius)

            fun addPointMarker(p: FloatArray) {
                val x = p[0]; val y = p[1]; val z = p[2]
                // 3-axis crosshair
                list.addAll(listOf(
                    x - s, y, z,   x + s, y, z,
                    x, y - s, z,   x, y + s, z,
                    x, y, z - s,   x, y, z + s
                ))
                // Octahedron diamond edges
                val top = floatArrayOf(x, y, z + s)
                val bot = floatArrayOf(x, y, z - s)
                val r0 = floatArrayOf(x + s, y, z)
                val r1 = floatArrayOf(x, y + s, z)
                val r2 = floatArrayOf(x - s, y, z)
                val r3 = floatArrayOf(x, y - s, z)
                val ring = arrayOf(r0, r1, r2, r3)

                for (i in 0 until 4) {
                    val cur = ring[i]
                    val nxt = ring[(i + 1) % 4]
                    list.addAll(listOf(top[0], top[1], top[2], cur[0], cur[1], cur[2]))
                    list.addAll(listOf(bot[0], bot[1], bot[2], cur[0], cur[1], cur[2]))
                    list.addAll(listOf(cur[0], cur[1], cur[2], nxt[0], nxt[1], nxt[2]))
                }
            }

            p1?.let { addPointMarker(it) }
            p2?.let { addPointMarker(it) }

            if (p1 != null && p2 != null) {
                // Connecting line
                list.addAll(listOf(
                    p1[0], p1[1], p1[2],
                    p2[0], p2[1], p2[2]
                ))

                // End-cap tick marks
                val dx = p2[0] - p1[0]
                val dy = p2[1] - p1[1]
                val dz = p2[2] - p1[2]
                val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (len > 1e-4f) {
                    val ndx = dx / len
                    val ndy = dy / len
                    val ndz = dz / len
                    val ux: Float; val uy: Float; val uz: Float
                    if (kotlin.math.abs(ndz) < 0.9f) {
                        val cx = -ndy; val cy = ndx; val cz = 0f
                        val clen = kotlin.math.sqrt(cx * cx + cy * cy)
                        ux = cx / clen; uy = cy / clen; uz = 0f
                    } else {
                        val cx = ndz; val cy = 0f; val cz = -ndx
                        val clen = kotlin.math.sqrt(cx * cx + cz * cz)
                        ux = cx / clen; uy = 0f; uz = cz / clen
                    }
                    val capS = s * 0.75f
                    // Ticks at p1
                    list.addAll(listOf(
                        p1[0] - ux * capS, p1[1] - uy * capS, p1[2] - uz * capS,
                        p1[0] + ux * capS, p1[1] + uy * capS, p1[2] + uz * capS
                    ))
                    // Ticks at p2
                    list.addAll(listOf(
                        p2[0] - ux * capS, p2[1] - uy * capS, p2[2] - uz * capS,
                        p2[0] + ux * capS, p2[1] + uy * capS, p2[2] + uz * capS
                    ))
                }
            }

            val out = FloatArray(list.size)
            list.forEachIndexed { i, f -> out[i] = f }
            return out
        }
    }
}

/** Colours of the mesh-check highlights (shared by the renderer and the legend). */
object DiagnosticColors {
    val BOUNDARY = floatArrayOf(1.0f, 0.55f, 0.0f)
    val NON_MANIFOLD = floatArrayOf(0.93f, 0.13f, 0.2f)
    val FLIPPED = floatArrayOf(0.86f, 0.2f, 0.86f)
}
