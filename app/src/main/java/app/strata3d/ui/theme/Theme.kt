package app.strata3d.ui.theme

import android.content.Context
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The cyan from the Strata:3D logo. */
val BrandCyan = Color(0xFF01B2C6)

/** Text/icon colour used on top of [BrandCyan] (dark, for legible contrast on the bright cyan). */
val OnBrandCyan = Color(0xFF00252A)

/*
 * Accent roles (both themes):
 * - primary = the brand cyan itself: switches, filled buttons, active toggles, selection icons.
 * - secondaryContainer / primaryContainer = a soft cyan tint: selected segments, tonal buttons (ruler, camera),
 *   the view rail's highlighted button.
 * - tertiary = accent *text* (see [accentText]). On light surfaces bright cyan text is hard to read (about 2.4:1),
 *   so light mode uses a deeper shade of the same cyan (about 4.6:1); dark mode uses the brand cyan.
 */
private val LightColors = lightColorScheme(
    primary = BrandCyan,
    onPrimary = OnBrandCyan,
    primaryContainer = Color(0xFFB9EEF5),
    onPrimaryContainer = Color(0xFF00363D),
    secondary = Color(0xFF4A6267),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2F3F7),
    onSecondaryContainer = Color(0xFF00363D),
    tertiary = Color(0xFF007C8A),
    onTertiary = Color.White,
    inversePrimary = Color(0xFF7FE5F2),
    surfaceTint = BrandCyan,
    background = Color(0xFFECEFF3),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFDBE4E6),
    onSurfaceVariant = Color(0xFF3F484A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFEBEEF0),
    surfaceContainerHighest = Color(0xFFE5E8EA),
    outline = Color(0xFF6F797A),
    outlineVariant = Color(0xFFBFC8CA),
)

private val DarkColors = darkColorScheme(
    primary = BrandCyan,
    onPrimary = OnBrandCyan,
    primaryContainer = Color(0xFF004E57),
    onPrimaryContainer = Color(0xFFB9F3FA),
    secondary = Color(0xFFB1CBD0),
    onSecondary = Color(0xFF1C3438),
    secondaryContainer = Color(0xFF0F4A52),
    onSecondaryContainer = Color(0xFFC3F4FA),
    tertiary = BrandCyan,
    onTertiary = OnBrandCyan,
    inversePrimary = Color(0xFF007C8A),
    surfaceTint = BrandCyan,
    background = Color(0xFF1C1D21),
    onBackground = Color(0xFFE1E3E3),
    surface = Color(0xFF26282C),
    onSurface = Color(0xFFE1E3E3),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBFC8CA),
    surfaceContainerLowest = Color(0xFF17181B),
    surfaceContainerLow = Color(0xFF1F2124),
    surfaceContainer = Color(0xFF232528),
    surfaceContainerHigh = Color(0xFF2D2F33),
    surfaceContainerHighest = Color(0xFF383A3E),
    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F484A),
)

/** Cyan for accent text: brand cyan on dark surfaces, a deeper (legible) shade of it on light ones. */
val ColorScheme.accentText: Color get() = tertiary

/** Text buttons in the accent text colour. */
@Composable
fun accentTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.accentText)

/** Whether the app is currently showing its dark theme (follows the in-app toggle, not just the system). */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun StrataTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}

/**
 * Stores the theme the user last picked with the sun/moon toggle. Until they pick one, the app follows
 * the system setting.
 */
class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("strata3d_settings", Context.MODE_PRIVATE)

    /** `true` = dark, `false` = light, `null` = never chosen (follow the system). */
    fun savedDarkTheme(): Boolean? = when (prefs.getString(KEY_THEME, null)) {
        THEME_DARK -> true
        THEME_LIGHT -> false
        else -> null
    }

    fun saveDarkTheme(dark: Boolean) {
        prefs.edit().putString(KEY_THEME, if (dark) THEME_DARK else THEME_LIGHT).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
    }
}
