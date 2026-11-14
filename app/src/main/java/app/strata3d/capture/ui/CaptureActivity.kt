package app.strata3d.capture.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.strata3d.R
import app.strata3d.capture.CameraXCapture
import app.strata3d.ui.theme.StrataTheme
import app.strata3d.ui.theme.ThemePreferences

/**
 * Guided capture: the captures library, the capture screen and the summary. A separate activity, so the camera is
 * only ever opened here, never by the viewer.
 */
class CaptureActivity : ComponentActivity() {

    private val library: CaptureLibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = ThemePreferences(this)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = themePrefs.savedDarkTheme() ?: systemDark
        setTheme(if (dark) R.style.Theme_Strata_Dark else R.style.Theme_Strata_Light)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(CameraXCapture.PREFS, Context.MODE_PRIVATE)
        // Opened with "Capture an object": go straight to a new capture, and closing it returns to the viewer.
        val startNew = savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_NEW, false)
        val returnToViewer = intent.getBooleanExtra(EXTRA_START_NEW, false)
        // From the "Check the box" notification: open that capture's summary.
        val openCapture = if (savedInstanceState == null) intent.getStringExtra(app.strata3d.capture.recon.ReconService.EXTRA_OPEN_CAPTURE) else null

        setContent {
            val darkTheme = themePrefs.savedDarkTheme() ?: isSystemInDarkTheme()
            var screen by rememberSaveable {
                mutableStateOf(if (startNew) Screen.PREFLIGHT else if (openCapture != null) Screen.SUMMARY else Screen.LIBRARY)
            }
            var summaryId by rememberSaveable { mutableStateOf(openCapture) }
            var shutterSettings by remember { mutableStateOf(false) }

            // Light status-bar icons over the camera; theme-matching elsewhere.
            DisposableEffect(screen, darkTheme) {
                val style = if (screen == Screen.CAPTURE || screen == Screen.REVIEW) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            StrataTheme(darkTheme = darkTheme) {
                when (screen) {
                    Screen.LIBRARY -> CaptureLibraryScreen(
                        viewModel = library,
                        onBack = { finish() },
                        onNewCapture = { screen = Screen.PREFLIGHT },
                        onOpen = { id -> summaryId = id; screen = Screen.SUMMARY },
                        onShutterSettings = { shutterSettings = true },
                    )
                    Screen.PREFLIGHT -> Preflight(
                        onReady = { screen = Screen.CAPTURE },
                        onBack = { if (returnToViewer) finish() else screen = Screen.LIBRARY },
                    )
                    Screen.CAPTURE -> CaptureScreen(
                        store = library.store,
                        onFinished = { savedId ->
                            library.refresh()
                            when {
                                savedId != null -> { summaryId = savedId; screen = Screen.SUMMARY }
                                returnToViewer -> finish()
                                else -> screen = Screen.LIBRARY
                            }
                        },
                    )
                    Screen.SUMMARY -> CaptureSummaryScreen(
                        viewModel = library,
                        captureId = summaryId,
                        onDone = { screen = Screen.LIBRARY },
                        onCheckBox = { id -> summaryId = id; screen = Screen.REVIEW },
                    )
                    Screen.REVIEW -> {
                        val id = summaryId
                        if (id == null) { screen = Screen.LIBRARY } else {
                            BoxReviewScreen(
                                captureDir = library.store.dirOf(id),
                                sceneDir = library.reviewScene(id),
                                onBuild = { library.resumeBuild(id); screen = Screen.SUMMARY },
                                onBack = { screen = Screen.SUMMARY },
                            )
                        }
                    }
                }

                if (shutterSettings) {
                    ShutterSettingsDialog(
                        current = prefs.getString(CameraXCapture.KEY_MODE, null) ?: CameraXCapture.Mode.SMART.name,
                        onChoose = { mode -> prefs.edit().putString(CameraXCapture.KEY_MODE, mode).apply(); shutterSettings = false },
                        onDismiss = { shutterSettings = false },
                    )
                }

                LaunchedEffect(screen) {
                    if (screen == Screen.LIBRARY) library.refresh()
                }
            }
        }
    }

    enum class Screen { LIBRARY, PREFLIGHT, CAPTURE, SUMMARY, REVIEW }

    companion object {
        /** Boolean extra: start a new capture immediately instead of showing the library. */
        const val EXTRA_START_NEW = "app.strata3d.capture.START_NEW"
    }
}

/** Asks for the camera permission before the capture screen opens. */
@Composable
private fun Preflight(onReady: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    // Re-check the permission when returning from system settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(granted) {
        if (granted) onReady() else if (!denied) launcher.launch(Manifest.permission.CAMERA)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!denied) {
                CircularProgressIndicator()
            } else {
                Text(stringResource(R.string.capture_permission_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(R.string.capture_permission_body), textAlign = TextAlign.Center)
                Button(onClick = { denied = false; launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.capture_permission_allow)) }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text(stringResource(R.string.capture_permission_settings)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.capture_back)) }
            }
        }
    }
}

/** The shutter setting: Smart (default) or Manual. */
@Composable
private fun ShutterSettingsDialog(current: String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_shutter_setting)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShutterOption(
                    selected = current == CameraXCapture.Mode.SMART.name,
                    title = stringResource(R.string.capture_mode_smart),
                    body = stringResource(R.string.capture_shutter_smart_desc),
                ) { onChoose(CameraXCapture.Mode.SMART.name) }
                ShutterOption(
                    selected = current == CameraXCapture.Mode.MANUAL.name,
                    title = stringResource(R.string.capture_mode_manual),
                    body = stringResource(R.string.capture_shutter_manual_desc),
                ) { onChoose(CameraXCapture.Mode.MANUAL.name) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun ShutterOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
