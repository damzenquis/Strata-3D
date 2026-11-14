package app.strata3d.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.strata3d.R

/** The information pages opened from the settings menu. */
enum class InfoPage(val title: Int) {
    SUPPORT(R.string.info_support), HELP(R.string.info_help), ABOUT(R.string.info_about),
    CREDITS(R.string.info_credits), LICENSE(R.string.info_license),
}

/** One credited project: "name" by author (link) = what it is used for here. */
private class Credit(val name: String, val author: String, val url: String, val use: Int)

private val CREDITS = listOf(
    Credit("3D Model Viewer", "nini1294", "https://github.com/nini1294/3d-model-viewer", R.string.credit_viewer),
    Credit("Openize.Drako for Java", "Openize", "https://github.com/openize-drako/Openize.Drako-for-Java", R.string.credit_drako),
    Credit("meshoptimizer", "Arseny Kapoulkine", "https://github.com/zeux/meshoptimizer", R.string.credit_meshopt),
    Credit("MVE (Multi-View Environment)", "Simon Fuhrmann and others", "https://github.com/simonfuhrmann/mve", R.string.credit_mve),
    Credit("mvs-texturing", "Michael Waechter, Nils Moehrle and others", "https://github.com/nmoehrle/mvs-texturing", R.string.credit_texturing),
    Credit("mapMAP", "Daniel Thuerck", "https://github.com/dthuerck/mapmap_cpu", R.string.credit_mapmap),
    Credit("rayint", "Nils Moehrle", "https://github.com/nmoehrle/rayint", R.string.credit_rayint),
    Credit("OpenMVS", "cdcseacave and contributors", "https://github.com/cdcseacave/openMVS", R.string.credit_openmvs),
    Credit("OpenCV", "OpenCV team and contributors", "https://github.com/opencv/opencv", R.string.credit_opencv),
    Credit("Boost C++ Libraries", "Boost contributors", "https://github.com/boostorg/boost", R.string.credit_boost),
    Credit("CGAL", "The CGAL Project", "https://github.com/CGAL/cgal", R.string.credit_cgal),
    Credit("VCGLib", "Visual Computing Lab, ISTI-CNR", "https://github.com/cdcseacave/VCG", R.string.credit_vcg),
    Credit("nanoflann", "Jose Luis Blanco-Claraco and contributors", "https://github.com/jlblancoc/nanoflann", R.string.credit_nanoflann),
    Credit("Eigen", "Eigen contributors", "https://gitlab.com/libeigen/eigen", R.string.credit_eigen),
    Credit("oneTBB", "UXL Foundation and contributors", "https://github.com/uxlfoundation/oneTBB", R.string.credit_tbb),
    Credit("libjpeg-turbo", "libjpeg-turbo contributors", "https://github.com/libjpeg-turbo/libjpeg-turbo", R.string.credit_libjpeg),
    Credit("libpng", "libpng contributors", "https://github.com/pnggroup/libpng", R.string.credit_libpng),
    Credit("LLVM OpenMP", "The LLVM Project", "https://github.com/llvm/llvm-project", R.string.credit_llvm_omp),
    Credit("AndroidX (Jetpack Compose, CameraX)", "Google and the Android Open Source Project", "https://github.com/androidx/androidx", R.string.credit_androidx),
)

/** Full-screen page with a title, a close button and scrollable content. */
@Composable
fun InfoPageDialog(page: InfoPage, onDismiss: () -> Unit) {
    var subpage by remember { mutableStateOf<String?>(null) }   // an asset file shown from the License page
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(page.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.info_close)) }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (page) {
                        InfoPage.SUPPORT -> Body(stringResource(R.string.support_body))
                        InfoPage.HELP -> Body(stringResource(R.string.help_body))
                        InfoPage.ABOUT -> {
                            Text(appVersion(LocalContext.current), style = MaterialTheme.typography.titleMedium)
                            Body(stringResource(R.string.about_body))
                            Body(stringResource(R.string.about_thanks))
                        }
                        InfoPage.CREDITS -> CREDITS.forEach { c ->
                            Body("•  \"${c.name}\" by ${c.author} (${c.url}) = ${stringResource(c.use)}")
                        }
                        InfoPage.LICENSE -> {
                            Text(appVersion(LocalContext.current), style = MaterialTheme.typography.titleMedium)
                            Body(stringResource(R.string.license_copyright))
                            Body(stringResource(R.string.license_body))
                            OutlinedButton(onClick = { subpage = LICENSE_ASSET }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.license_full)) }
                            OutlinedButton(onClick = { subpage = NOTICES_ASSET }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.license_notices)) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
    subpage?.let { asset -> AssetTextDialog(asset, onDismiss = { subpage = null }) }
}

/** A plain-text file from the app's assets (licence texts), full screen, in a monospace font. */
@Composable
private fun AssetTextDialog(asset: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(asset) { runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (asset == LICENSE_ASSET) R.string.license_full else R.string.license_notices), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.info_close)) }
                }
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

/** Body text with web addresses made tappable. */
@Composable
private fun Body(text: String) {
    val link = MaterialTheme.colorScheme.primary
    Text(linkify(text, link), style = MaterialTheme.typography.bodyMedium)
}

private fun linkify(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in URL.findAll(text)) {
        append(text.substring(last, m.range.first))
        val url = m.value
        pushLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))))
        append(url)
        pop()
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/** "0.9.0 (build 9)" with the app's name. */
fun appVersion(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    return "Strata:3D " + context.getString(R.string.info_version, info.versionName ?: "", code)
}

private val URL = Regex("https?://[^\\s)]+")
private const val LICENSE_ASSET = "LICENSE.txt"
private const val NOTICES_ASSET = "THIRD_PARTY_NOTICES.txt"
