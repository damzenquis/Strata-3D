package app.strata3d.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.outlined.CameraAlt
import app.strata3d.ui.theme.accentText
import app.strata3d.ui.theme.accentTextButtonColors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.History
import android.net.Uri
import app.strata3d.recent.RecentEntry
import app.strata3d.render.SnapshotException
import app.strata3d.render.SnapshotRequest
import app.strata3d.snapshot.SnapshotExporter
import kotlinx.coroutines.delay
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.selection.selectableGroup
import app.strata3d.render.Projection
import app.strata3d.render.SectionCap
import app.strata3d.render.SectionPlane
import app.strata3d.render.ViewPreset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import app.strata3d.ui.theme.BrandCyan
import app.strata3d.ui.theme.LocalDarkTheme
import app.strata3d.ui.theme.OnBrandCyan
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.strata3d.R
import app.strata3d.diagnostics.DiagnosticOverlay
import app.strata3d.model.LengthFormat
import app.strata3d.model.Mesh
import app.strata3d.model.UnitSource
import app.strata3d.render.ModelSurfaceView
import app.strata3d.render.RenderMode
import java.text.NumberFormat

private val DarkViewportBackground = floatArrayOf(0.110f, 0.114f, 0.129f)

/** Long edge of Recent Files thumbnails, in pixels. */
private const val THUMBNAIL_LONG_EDGE = 256

/** Width of the view rail (52 dp buttons + 4 dp padding each side). */
private val RAIL_WIDTH = 60.dp

/** Width of the two-column rail used on short (landscape) screens. */
private val RAIL_WIDTH_COMPACT = 114.dp

/** Height reserved for the bottom toolbar (48 dp controls + 16 dp padding above and below). */
private val BOTTOM_CONTROLS_HEIGHT = 80.dp
private val LightViewportBackground = floatArrayOf(0.925f, 0.937f, 0.953f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onToggleTheme: () -> Unit,
    /** Opens capture: true = start a new capture right away, false = the Captures library. */
    onOpenCapture: (startNew: Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Multiple selection lets a model be opened together with the files it references (e.g. glTF + .bin).
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.load(uris)
    }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let(viewModel::downloadModel)
    }
    // STL has no universally registered MIME type, so accept everything and let the parser decide.
    val launchPicker = { openDocument.launch(arrayOf("*/*")) }
    val launchSave: () -> Unit = {
        state.model?.fileName?.let { name -> saveDocument.launch(name) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val controller = remember { ViewportController() }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showLocation by rememberSaveable { mutableStateOf(false) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var showSnapshot by rememberSaveable { mutableStateOf(false) }
    var showRecents by rememberSaveable { mutableStateOf(false) }
    val thumbnails = remember { ThumbnailCache(viewModel::loadThumbnail) }

    // Reopen: the picker starts at the original document so the user can select it again.
    val reopenContract = remember { ReopenDocuments() }
    var reopenTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val reopenLauncher = rememberLauncherForActivityResult(reopenContract) { uris ->
        val id = reopenTargetId
        reopenTargetId = null
        if (id != null && uris.isNotEmpty()) {
            showRecents = false
            viewModel.reopenRecent(id, uris)
        }
    }
    val reopen: (RecentEntry) -> Unit = { entry ->
        reopenTargetId = entry.file.id
        reopenContract.initialUri = Uri.parse(entry.file.uri)
        reopenLauncher.launch(arrayOf("*/*"))
    }
    val openRecent: (RecentEntry) -> Unit = { entry ->
        showRecents = false
        viewModel.openRecent(entry)
    }

    val context = LocalContext.current
    val resources = LocalResources.current
    val dark = LocalDarkTheme.current
    val background = if (dark) DarkViewportBackground else LightViewportBackground
    val backgroundColor = Color(background[0], background[1], background[2])

    // Landscape gets its own layout decisions: a shorter app bar, a rail that starts collapsed, constrained
    // overlays, and scrollable sheets. Orientation changes do not recreate the activity (configChanges).
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val compactHeight = configuration.screenHeightDp < 480
    val viewerPrefs = remember { ViewerPreferences(context) }
    var railExpanded by remember(isLandscape) { mutableStateOf(viewerPrefs.isRailExpanded(isLandscape)) }

    // Screen areas the floating controls cover, so framing keeps the model clear of them (pixels).
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeEndPx = WindowInsets.safeDrawing.getRight(density, layoutDirection).toFloat()
    val navBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val railWidth = if (compactHeight) RAIL_WIDTH_COMPACT else RAIL_WIDTH
    val obstructedRightPx = if (railExpanded) with(density) { (railWidth + 8.dp).toPx() } + safeEndPx else 0f
    val obstructedBottomPx = with(density) { BOTTOM_CONTROLS_HEIGHT.toPx() } + navBottomPx

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfoMessage()
        }
    }

    val model = state.model

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = backgroundColor,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState, Modifier.navigationBarsPadding()) },
            topBar = {
                Column {
                    // Status bar strip in the page colour, with the app bar as a separate solid band below it.
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(backgroundColor),
                    )
                    TopAppBar(
                        expandedHeight = if (compactHeight) 52.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                        windowInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StrataLogo(Modifier.size(width = 36.dp, height = 34.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = model?.fileName ?: stringResource(R.string.app_name),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                        actions = {
                            if (model != null) {
                                val issues = (state.diagnostics as? DiagnosticsState.Done)?.report?.hasIssues == true
                                IconButton(onClick = { showInfo = true }) {
                                    Box {
                                        Icon(
                                            Icons.Outlined.Info,
                                            contentDescription = stringResource(if (issues) R.string.diag_issues_badge else R.string.model_info),
                                        )
                                        if (issues) IssueDot(Modifier.align(Alignment.TopEnd))
                                    }
                                }
                            }
                            ThemeToggleButton(dark = dark, onToggle = onToggleTheme)
                            IconButton(onClick = { panelOpen = true }) {
                                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                            }
                        },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                // Highlights are shown only for a finished check of the geometry on screen, when switched on.
                val diagnosticOverlay = (state.diagnostics as? DiagnosticsState.Done)?.overlay?.takeIf { state.showDiagnostics }
                val capReliability = SectionCap.reliability(state.stencilSupported, (state.diagnostics as? DiagnosticsState.Done)?.report)
                val fillStatus = when {
                    state.diagnostics == DiagnosticsState.Analyzing -> stringResource(R.string.section_fill_checking)
                    capReliability == SectionCap.Reliability.NO_STENCIL -> stringResource(R.string.section_fill_no_stencil)
                    state.diagnostics is DiagnosticsState.Failed -> stringResource(R.string.section_fill_failed)
                    capReliability == SectionCap.Reliability.UNKNOWN_GEOMETRY -> stringResource(R.string.section_fill_unchecked)
                    capReliability == SectionCap.Reliability.UNSUITABLE_GEOMETRY -> stringResource(R.string.section_fill_open)
                    state.renderMode == RenderMode.WIREFRAME -> stringResource(R.string.section_fill_wireframe)
                    else -> null
                }
                if (model != null) {
                    ModelViewport(
                        mesh = model.mesh,
                        renderMode = state.renderMode,
                        photoColours = state.photoColours,
                        projection = state.projection,
                        obstructedRightPx = obstructedRightPx,
                        obstructedBottomPx = obstructedBottomPx,
                        showGrid = state.showGrid,
                        modelColor = state.modelColor.rgb,
                        background = background,
                        darkBackground = dark,
                        isMeasuring = state.isMeasuring,
                        measurePoint1 = state.measurePoint1,
                        measurePoint2 = state.measurePoint2,
                        snapToVertices = state.snapToVertices,
                        diagnosticOverlay = diagnosticOverlay,
                        section = state.section.toPlane(model.mesh.bounds),
                        // Fill the cut only for geometry the mesh check confirmed closed and consistently wound.
                        fillSection = capReliability == SectionCap.Reliability.RELIABLE,
                        onStencilSupport = viewModel::setStencilSupported,
                        onPointPicked = { pt, snapped -> viewModel.onMeasurePointPicked(pt[0], pt[1], pt[2], snapped) },
                        controller = controller,
                        onRenderError = viewModel::reportError,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = padding.calculateTopPadding()),
                    )
                    MeasurementOverlay(
                        isMeasuring = state.isMeasuring,
                        measurePoint1 = state.measurePoint1,
                        measurement = state.measurement,
                        scale = model.scale,
                        declaredUnit = model.mesh.unit,
                        snap = state.snapToVertices,
                        onToggleSnap = viewModel::toggleSnap,
                        onUnitChoice = viewModel::setUnitChoice,
                        onClear = viewModel::clearMeasurement,
                        onExit = viewModel::toggleMeasureMode,
                        onCopy = { summary ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Measurement", summary))
                            scope.launch {
                                snackbarHostState.showSnackbar(resources.getString(R.string.measure_copied))
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(padding)
                            .padding(top = 8.dp, end = if (railExpanded) railWidth + 8.dp else 0.dp)
                            .widthIn(max = 520.dp),
                    )
                    // Above the toolbar: the highlight legend (start) and the Section bar (centre).
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                            .padding(start = 16.dp, end = if (railExpanded) railWidth + 8.dp else 16.dp, bottom = 8.dp + BOTTOM_CONTROLS_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (diagnosticOverlay != null) DiagnosticsLegend(diagnosticOverlay)
                        state.section?.let { section ->
                            SectionBar(
                                section = section,
                                bounds = model.mesh.bounds,
                                scale = model.scale,
                                onAxis = viewModel::setSectionAxis,
                                onFraction = viewModel::setSectionFraction,
                                onFlip = viewModel::flipSection,
                                onClose = viewModel::toggleSection,
                                fillStatus = fillStatus,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                    BottomControls(
                        renderMode = state.renderMode,
                        isMeasuring = state.isMeasuring,
                        isSectioning = state.section != null,
                        onRenderMode = viewModel::setRenderMode,
                        onToggleMeasure = viewModel::toggleMeasureMode,
                        onToggleSection = viewModel::toggleSection,
                        onSnapshot = { showSnapshot = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                            .padding(16.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(top = padding.calculateTopPadding())
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                            .padding(top = 8.dp, end = 8.dp, bottom = BOTTOM_CONTROLS_HEIGHT),
                    ) {
                        ViewRail(
                            expanded = railExpanded,
                            onToggleExpanded = {
                                railExpanded = !railExpanded
                                viewerPrefs.setRailExpanded(isLandscape, railExpanded)
                            },
                            projection = state.projection,
                            onToggleProjection = viewModel::toggleProjection,
                            onPreset = controller::applyPreset,
                            onFit = controller::fitView,
                            hasPhotoColours = model?.mesh?.colours != null,
                            photoColours = state.photoColours,
                            onTogglePhotoColours = viewModel::togglePhotoColours,
                            compact = compactHeight,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                } else if (!state.loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyLayout(
                            isLandscape = isLandscape,
                            hasRecents = state.recentFiles.isNotEmpty(),
                            intro = { m -> EmptyState(onOpen = launchPicker, onCapture = { onOpenCapture(true) }, modifier = m) },
                            recents = { m ->
                                RecentSection(
                                    entries = state.recentFiles,
                                    thumbnails = thumbnails,
                                    onOpen = openRecent,
                                    onReopen = reopen,
                                    onViewAll = { showRecents = true },
                                    modifier = m.widthIn(max = 480.dp),
                                )
                            },
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = if (compactHeight) 12.dp else 32.dp),
                        )
                    }
                }
                if (state.loading) LoadingOverlay(state.loadingName)
            }
        }

        SettingsPanel(
            visible = panelOpen,
            onDismiss = { panelOpen = false },
            state = state,
            onOpenModel = launchPicker,
            onShowLocation = { showLocation = true },
            onDownload = launchSave,
            onToggleGrid = viewModel::toggleGrid,
            onColor = viewModel::setModelColor,
            onClose = viewModel::closeModel,
            onShowRecents = { showRecents = true },
            onOpenCapture = { onOpenCapture(false) },
        )

        if (showRecents) {
            RecentFilesScreen(
                entries = state.recentFiles,
                thumbnails = thumbnails,
                onBack = { showRecents = false },
                onOpen = openRecent,
                onReopen = reopen,
                onRemove = { viewModel.removeRecent(it.file.id) },
                onClear = viewModel::clearRecents,
            )
        }
    }

    // A recent file could not be opened: say so, with a Reopen action wherever the entry is in the list.
    LaunchedEffect(state.recentUnavailableId) {
        val id = state.recentUnavailableId ?: return@LaunchedEffect
        viewModel.clearRecentUnavailable()
        val result = snackbarHostState.showSnackbar(
            message = resources.getString(R.string.recent_unavailable),
            actionLabel = resources.getString(R.string.recent_reopen),
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            state.recentFiles.firstOrNull { it.file.id == id }?.let(reopen)
        }
    }

    // After a successful load, capture a small transparent thumbnail of what is on screen for Recent Files.
    // It reuses the off-screen snapshot renderer and never reloads the model.
    LaunchedEffect(state.thumbnailRequest) {
        val id = state.thumbnailRequest ?: return@LaunchedEffect
        delay(400) // let the first frame of the new model settle
        val view = controller.view
        if (view != null && state.model != null) {
            val base = view.visibleSizePx()
            val scale = THUMBNAIL_LONG_EDGE.toFloat() / maxOf(base[0], base[1], 1)
            val request = SnapshotRequest(
                width = maxOf(1, (base[0] * scale).toInt()),
                height = maxOf(1, (base[1] * scale).toInt()),
                transparent = true,
                background = background,
                showGrid = false,
                edgeScale = 1f,
            )
            try {
                val pixels = view.capture(request)
                viewModel.saveThumbnail(id, SnapshotExporter.toBitmap(pixels))
            } catch (e: SnapshotException) {
                // No thumbnail this time; the row shows a placeholder.
            }
        }
        viewModel.consumeThumbnailRequest()
    }

    if (showInfo && model != null) {
        ModelInfoSheet(
            model = model,
            onDismiss = { showInfo = false },
            onDownload = launchSave,
            onCopied = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            meshCheck = {
                MeshCheckSection(
                    state = state.diagnostics,
                    showIn3d = state.showDiagnostics,
                    onAnalyze = viewModel::analyzeMesh,
                    onCancel = viewModel::cancelMeshAnalysis,
                    onToggleIn3d = viewModel::toggleDiagnosticsOverlay,
                )
            },
        )
    }

    if (showSnapshot && model != null) {
        SnapshotSheet(
            controller = controller,
            model = model,
            background = background,
            defaultGrid = state.showGrid,
            isLandscape = isLandscape,
            onDismiss = { showSnapshot = false },
            onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
        )
    }

    if (showLocation && model != null) {
        FileLocationDialog(
            model = model,
            onDismiss = { showLocation = false },
            onDownload = launchSave,
            onCopied = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
        )
    }
}

/** The Strata:3D logo; its dark strokes use the theme's text colour so it reads on light and dark bars. */
@Composable
fun StrataLogo(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.logo_layer_ink),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ink),
            modifier = Modifier.matchParentSize(),
        )
        Image(
            painter = painterResource(R.drawable.logo_layer_cyan),
            contentDescription = null,
            colorFilter = ColorFilter.tint(BrandCyan),
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** Sun/moon button: shows the moon in light mode (tap for dark) and the sun in dark mode (tap for light). */
@Composable
private fun ThemeToggleButton(dark: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Crossfade(targetState = dark, label = "themeIcon") { isDark ->
            if (isDark) {
                Icon(Icons.Outlined.LightMode, contentDescription = stringResource(R.string.theme_switch_to_light))
            } else {
                Icon(Icons.Outlined.DarkMode, contentDescription = stringResource(R.string.theme_switch_to_dark))
            }
        }
    }
}

/** Lets Compose UI (e.g. the reset button) talk to the underlying GL view. */
class ViewportController {
    internal var view: ModelSurfaceView? = null
    fun resetCamera() {
        view?.resetCamera()
    }

    fun applyPreset(preset: ViewPreset) {
        view?.applyPreset(preset)
    }

    fun fitView() {
        view?.fitView()
    }
}

@Composable
private fun ModelViewport(
    mesh: Mesh,
    renderMode: RenderMode,
    photoColours: Boolean,
    projection: Projection,
    obstructedRightPx: Float,
    obstructedBottomPx: Float,
    showGrid: Boolean,
    modelColor: FloatArray,
    background: FloatArray,
    darkBackground: Boolean,
    isMeasuring: Boolean,
    measurePoint1: Point3D?,
    measurePoint2: Point3D?,
    snapToVertices: Boolean,
    diagnosticOverlay: DiagnosticOverlay?,
    section: SectionPlane?,
    fillSection: Boolean,
    onStencilSupport: (Boolean) -> Unit,
    onPointPicked: (FloatArray, Boolean) -> Unit,
    controller: ViewportController,
    onRenderError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnError by rememberUpdatedState(onRenderError)
    val currentOnPointPicked by rememberUpdatedState(onPointPicked)
    val currentOnStencil by rememberUpdatedState(onStencilSupport)
    var surfaceView by remember { mutableStateOf<ModelSurfaceView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ModelSurfaceView(context).also { view ->
                view.onRenderError = { message -> currentOnError(message) }
                view.onStencilSupport = { supported -> currentOnStencil(supported) }
                controller.view = view
                surfaceView = view
            }
        },
        update = { view ->
            view.setColors(modelColor, background, darkBackground)
            view.setDisplayMode(renderMode)
            view.setPhotoColours(photoColours)
            view.setProjection(projection)
            view.setObstructedInsets(0f, 0f, obstructedRightPx, obstructedBottomPx)
            view.setShowGrid(showGrid)
            view.setMesh(mesh)
            view.isMeasureMode = isMeasuring
            view.snapToVertices = snapToVertices
            view.setDiagnosticOverlay(diagnosticOverlay)
            view.setSection(section, fillSection)
            view.onPointPicked = { point, snapped -> currentOnPointPicked(point, snapped) }
            view.setMeasurementPoints(
                measurePoint1?.toFloatArray(),
                measurePoint2?.toFloatArray(),
                measurePoint1?.snapped == true,
                measurePoint2?.snapped == true,
            )
        },
        onRelease = { view ->
            if (controller.view === view) controller.view = null
        },
    )

    // GLSurfaceView must be told when the host is paused/resumed so its render thread can release the GPU.
    DisposableEffect(lifecycleOwner, surfaceView) {
        val view = surfaceView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControls(
    renderMode: RenderMode,
    isMeasuring: Boolean,
    isSectioning: Boolean,
    onRenderMode: (RenderMode) -> Unit,
    onToggleMeasure: () -> Unit,
    onToggleSection: () -> Unit,
    onSnapshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Compact icon selector (fits 360 dp phones with every tool button); each option is announced by name.
    val modes = listOf(
        Triple(RenderMode.SHADED, RenderModeIcons.Solid, stringResource(R.string.mode_solid_desc)),
        Triple(RenderMode.SHADED_EDGES, RenderModeIcons.Edges, stringResource(R.string.mode_edges_desc)),
        Triple(RenderMode.WIREFRAME, RenderModeIcons.Wire, stringResource(R.string.mode_wire_desc)),
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
        ) {
            Row(
                Modifier
                    .padding(4.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                modes.forEach { (mode, icon, description) ->
                    val selected = mode == renderMode
                    Surface(
                        selected = selected,
                        onClick = { onRenderMode(mode) },
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(width = 48.dp, height = 40.dp)
                            .semantics {
                                role = Role.RadioButton
                                contentDescription = description
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // Tool toggles: brand cyan while active, and announced as on/off.
        ToolToggle(isMeasuring, onToggleMeasure) {
            Icon(Icons.Outlined.Straighten, contentDescription = stringResource(R.string.measure))
        }
        Spacer(Modifier.width(8.dp))
        ToolToggle(isSectioning, onToggleSection) {
            Icon(RenderModeIcons.Section, contentDescription = stringResource(R.string.section), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = onSnapshot, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = stringResource(R.string.snapshot_title))
        }
    }
}

@Composable
private fun ToolToggle(checked: Boolean, onToggle: () -> Unit, icon: @Composable () -> Unit) {
    FilledTonalIconToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.size(48.dp),
        content = icon,
    )
}

/**
 * Right-hand side panel opened by the gear button. It holds the options from the old overflow menu,
 * plus "Open model" (the old folder button).
 */
@Composable
private fun SettingsPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: ViewerUiState,
    onOpenModel: () -> Unit,
    onShowLocation: () -> Unit,
    onDownload: () -> Unit,
    onToggleGrid: () -> Unit,
    onColor: (ModelColor) -> Unit,
    onClose: () -> Unit,
    onShowRecents: () -> Unit,
    onOpenCapture: () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    var infoPage by remember { mutableStateOf<InfoPage?>(null) }
    infoPage?.let { InfoPageDialog(it, onDismiss = { infoPage = null }) }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight(),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.82f),
            ) {
                Column(
                    Modifier
                        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_panel_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                    PanelItem(
                        icon = Icons.Outlined.FolderOpen,
                        label = stringResource(R.string.open_file),
                        onClick = {
                            onDismiss()
                            onOpenModel()
                        },
                    )
                    PanelItem(
                        icon = Icons.Outlined.CameraAlt,
                        label = stringResource(R.string.capture_entry),
                        iconTint = MaterialTheme.colorScheme.accentText,
                        onClick = {
                            onDismiss()
                            onOpenCapture()
                        },
                    )
                    PanelItem(
                        icon = Icons.Outlined.History,
                        label = stringResource(R.string.recent_files),
                        onClick = {
                            onDismiss()
                            onShowRecents()
                        },
                    )
                    if (state.model != null) {
                        PanelItem(
                            icon = Icons.Outlined.Folder,
                            label = stringResource(R.string.file_location),
                            onClick = {
                                onDismiss()
                                onShowLocation()
                            },
                        )
                        PanelItem(
                            icon = Icons.Outlined.FileDownload,
                            label = stringResource(R.string.download_as_new_file),
                            onClick = {
                                onDismiss()
                                onDownload()
                            },
                        )
                        PanelItem(
                            icon = Icons.Outlined.Close,
                            label = stringResource(R.string.close_model),
                            onClick = {
                                onDismiss()
                                onClose()
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                    PanelItem(
                        icon = Icons.Outlined.GridOn,
                        label = stringResource(R.string.show_grid),
                        onClick = onToggleGrid,
                        trailing = { Switch(checked = state.showGrid, onCheckedChange = { onToggleGrid() }) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                    Text(
                        text = stringResource(R.string.model_color),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    ModelColor.entries.forEach { color ->
                        PanelItem(
                            icon = Icons.Filled.Circle,
                            iconTint = Color(color.rgb[0], color.rgb[1], color.rgb[2]),
                            label = color.name,
                            onClick = { onColor(color) },
                            trailing = {
                                if (state.modelColor == color) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                    listOf(
                        InfoPage.SUPPORT to Icons.Outlined.VolunteerActivism, InfoPage.HELP to Icons.AutoMirrored.Outlined.HelpOutline,
                        InfoPage.ABOUT to Icons.Outlined.Info, InfoPage.CREDITS to Icons.Outlined.Groups,
                        InfoPage.LICENSE to Icons.Outlined.Gavel,
                    ).forEach { (page, icon) ->
                        PanelItem(icon = icon, label = stringResource(page.title), onClick = { infoPage = page })
                    }
                }
            }
        }
    }
}

/**
 * Collapsible right-edge rail: projection toggle, standard views and Fit. Scrolls when the available height is
 * short (landscape), and collapses to a single "Views" button.
 */
@Composable
private fun ViewRail(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    projection: Projection,
    onToggleProjection: () -> Unit,
    onPreset: (ViewPreset) -> Unit,
    onFit: () -> Unit,
    hasPhotoColours: Boolean,
    photoColours: Boolean,
    onTogglePhotoColours: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (expanded) {
                RailButton(Icons.Outlined.ChevronRight, null, stringResource(R.string.view_rail_collapse), compact, onToggleExpanded)
                val ortho = projection == Projection.ORTHOGRAPHIC
                val projectionButton: @Composable () -> Unit = {
                    RailButton(
                        icon = if (ortho) Icons.Outlined.Architecture else Icons.Outlined.Landscape,
                        label = stringResource(if (ortho) R.string.view_ortho else R.string.view_persp),
                        description = stringResource(if (ortho) R.string.view_switch_to_persp else R.string.view_switch_to_ortho),
                        compact = compact,
                        onClick = onToggleProjection,
                        selected = ortho,
                    )
                }
                val top: @Composable () -> Unit = { RailButton(Icons.Outlined.VerticalAlignBottom, stringResource(R.string.view_top), stringResource(R.string.view_top_desc), compact, { onPreset(ViewPreset.TOP) }) }
                val front: @Composable () -> Unit = { RailButton(Icons.Outlined.CropSquare, stringResource(R.string.view_front), stringResource(R.string.view_front_desc), compact, { onPreset(ViewPreset.FRONT) }) }
                val side: @Composable () -> Unit = { RailButton(Icons.AutoMirrored.Outlined.ViewSidebar, stringResource(R.string.view_side), stringResource(R.string.view_side_desc), compact, { onPreset(ViewPreset.SIDE) }) }
                val iso: @Composable () -> Unit = { RailButton(Icons.Outlined.ViewInAr, stringResource(R.string.view_iso), stringResource(R.string.view_iso_desc), compact, { onPreset(ViewPreset.ISO) }) }
                val fit: @Composable () -> Unit = { RailButton(Icons.Outlined.CenterFocusStrong, stringResource(R.string.view_fit), stringResource(R.string.view_fit_desc), compact, onFit) }
                // Photo colours: a separate option (own divider, below Fit), only for models that have colours.
                val photo: @Composable () -> Unit = {
                    RailButton(
                        Icons.Outlined.Photo, stringResource(R.string.view_photo),
                        stringResource(if (photoColours) R.string.view_photo_hide else R.string.view_photo_show),
                        compact, onTogglePhotoColours, selected = photoColours,
                    )
                }
                if (compact) {
                    // Short screens (landscape): two columns so every control is visible without scrolling.
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { projectionButton(); fit() }
                    HorizontalDivider(Modifier.width(88.dp).padding(vertical = 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { top(); front() }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { side(); iso() }
                    if (hasPhotoColours) {
                        HorizontalDivider(Modifier.width(88.dp).padding(vertical = 2.dp))
                        photo()
                    }
                } else {
                    projectionButton()
                    HorizontalDivider(Modifier.width(32.dp).padding(vertical = 2.dp))
                    top(); front(); side(); iso()
                    HorizontalDivider(Modifier.width(32.dp).padding(vertical = 2.dp))
                    fit()
                    if (hasPhotoColours) {
                        HorizontalDivider(Modifier.width(32.dp).padding(vertical = 2.dp))
                        photo()
                    }
                }
            } else {
                RailButton(Icons.Outlined.ChevronLeft, stringResource(R.string.view_rail), stringResource(R.string.view_rail_expand), compact, onToggleExpanded)
            }
        }
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String?,
    description: String,
    compact: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(width = 52.dp, height = if (label == null) 36.dp else if (compact) 44.dp else 52.dp)
            .semantics { contentDescription = description },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PanelItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit, onCapture: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onOpen,
            colors = ButtonDefaults.buttonColors(containerColor = BrandCyan, contentColor = OnBrandCyan),
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.open_file))
        }
        OutlinedButton(
            onClick = onCapture,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.accentText),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.accentText),
        ) {
            Icon(Icons.Outlined.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.capture_object))
        }
    }
}

@Composable
private fun LoadingOverlay(name: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (name != null) stringResource(R.string.loading_named, name) else stringResource(R.string.loading),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelInfoSheet(
    model: LoadedModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCopied: (String) -> Unit,
    meshCheck: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val mesh = model.mesh
    val b = mesh.bounds
    val stats = mesh.stats
    val scale = model.scale
    val unitSource = stringResource(
        when (scale.source) {
            UnitSource.FILE -> R.string.unit_source_file
            UnitSource.USER -> R.string.unit_source_user
            UnitSource.ASSUMED -> R.string.unit_source_assumed
        },
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(model.fileName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            InfoRow(
                label = stringResource(R.string.info_location),
                value = model.fileLocation,
                onCopy = {
                    copyToClipboard(context, model.fileLocation)
                    onCopied(resources.getString(R.string.location_copied))
                },
            )
            InfoRow(stringResource(R.string.info_triangles), NumberFormat.getIntegerInstance().format(stats.triangleCount))
            InfoRow(stringResource(R.string.info_units), "${scale.unit.symbol} · $unitSource")
            InfoRow(
                stringResource(R.string.info_dimensions),
                LengthFormat.size(scale.toMm(b.sizeX.toDouble()), scale.toMm(b.sizeY.toDouble()), scale.toMm(b.sizeZ.toDouble())),
            )
            InfoRow(stringResource(R.string.info_volume), LengthFormat.volume(scale.toMm3(stats.volume)))
            InfoRow(stringResource(R.string.info_area), LengthFormat.area(scale.toMm2(stats.surfaceArea)))
            if (model.fileSize >= 0) {
                InfoRow(stringResource(R.string.info_file_size), Formatter.formatShortFileSize(context, model.fileSize))
            }
            InfoRow(stringResource(R.string.info_load_time), "${model.loadTimeMs} ms")
            Spacer(Modifier.height(20.dp))
            meshCheck()
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onDismiss()
                    onDownload()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download_as_new_file))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.info_units_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.gestures_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.End,
                maxLines = if (onCopy != null) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onCopy != null) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp).padding(start = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.copy_path),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileLocationDialog(
    model: LoadedModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCopied: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        title = {
            Text(
                text = stringResource(R.string.file_location),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = model.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.info_location),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.accentText,
                        )
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = model.fileLocation,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (model.rawUri != null && model.rawUri != model.fileLocation) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.source_uri),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(
                                    text = model.rawUri.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onDownload()
                },
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.download_as_new_file))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(colors = accentTextButtonColors(), onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, model.fileLocation)
                        onCopied(resources.getString(R.string.location_copied))
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy_path))
                }
            }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Location", text)
    clipboard?.setPrimaryClip(clip)
}

