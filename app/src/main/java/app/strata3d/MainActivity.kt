package app.strata3d

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.strata3d.capture.ui.CaptureActivity
import app.strata3d.ui.ViewerScreen
import app.strata3d.ui.ViewerViewModel
import app.strata3d.ui.theme.StrataTheme
import app.strata3d.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {

    private val viewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePrefs = ThemePreferences(this)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Pick the window theme before anything draws, so the saved theme is used from the first frame.
        setTheme(if (themePrefs.savedDarkTheme() ?: systemDark) R.style.Theme_Strata_Dark else R.style.Theme_Strata_Light)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val followSystemDark = isSystemInDarkTheme()
            var savedDark by remember { mutableStateOf(themePrefs.savedDarkTheme()) }
            val dark = savedDark ?: followSystemDark

            // Status/navigation bar icons and the window background follow the in-app theme.
            DisposableEffect(dark) {
                val barStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                val windowColor = if (dark) R.color.window_background_dark else R.color.window_background_light
                window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this@MainActivity, windowColor)))
                onDispose { }
            }

            StrataTheme(darkTheme = dark) {
                ViewerScreen(
                    viewModel = viewModel,
                    onToggleTheme = {
                        val next = !dark
                        themePrefs.saveDarkTheme(next)
                        savedDark = next
                    },
                    onOpenCapture = { startNew ->
                        startActivity(
                            Intent(this@MainActivity, CaptureActivity::class.java)
                                .putExtra(CaptureActivity.EXTRA_START_NEW, startNew),
                        )
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Opens files passed via "Open with…" (ACTION_VIEW) or the share sheet (ACTION_SEND / ACTION_SEND_MULTIPLE), or a
     * model this app built ([EXTRA_MODEL_PATH], a file in its own storage).
     */
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_MODEL_PATH)?.let { path ->
            val f = java.io.File(path)
            if (f.isFile && f.canonicalPath.startsWith(filesDir.canonicalPath)) viewModel.load(Uri.fromFile(f))
            return
        }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) viewModel.load(uris)
    }

    companion object {
        /** A model built by this app's reconstruction (absolute path inside the app's own files). */
        const val EXTRA_MODEL_PATH = "app.strata3d.MODEL_PATH"
    }
}
