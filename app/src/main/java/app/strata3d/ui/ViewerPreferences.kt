package app.strata3d.ui

import android.content.Context

/** Small persisted viewer-layout choices (not model data). */
class ViewerPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("strata3d_settings", Context.MODE_PRIVATE)

    /** The view rail starts expanded in portrait and collapsed in landscape, then remembers the user's choice. */
    fun isRailExpanded(landscape: Boolean): Boolean =
        prefs.getBoolean(if (landscape) KEY_RAIL_LANDSCAPE else KEY_RAIL_PORTRAIT, !landscape)

    fun setRailExpanded(landscape: Boolean, expanded: Boolean) {
        prefs.edit().putBoolean(if (landscape) KEY_RAIL_LANDSCAPE else KEY_RAIL_PORTRAIT, expanded).apply()
    }

    private companion object {
        const val KEY_RAIL_PORTRAIT = "view_rail_expanded_portrait"
        const val KEY_RAIL_LANDSCAPE = "view_rail_expanded_landscape"
    }
}
