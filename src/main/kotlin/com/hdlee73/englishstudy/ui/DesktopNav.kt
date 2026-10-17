package com.hdlee73.englishstudy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Desktop-shell bridge (Design A).
 *
 * The native menu bar, the tray icon and the window geometry live in `Main.kt`, outside the Compose tree, while
 * navigation state lives in `MainActivity`/`AppRoot`. This object connects the two and remembers the last tab and
 * the window rectangle, so the Windows app reopens where the user left it instead of off the Compose phone layout.
 */
object DesktopNav {
    private const val PREFS = "desktop_shell"
    private const val KEY_TAB = "last_tab"
    private const val KEY_W = "win_w"
    private const val KEY_H = "win_h"
    private const val KEY_X = "win_x"
    private const val KEY_Y = "win_y"
    private const val KEY_MAX = "win_max"

    /** Window size/position in dp, and whether the window was maximised. */
    data class WindowGeometry(val width: Float, val height: Float, val x: Float?, val y: Float?, val maximized: Boolean)

    private var context: Context? = null

    /** The tab the shell is currently showing. */
    var current: AppTab by mutableStateOf(AppTab.DICTIONARY)
        private set

    /** Bumped to ask `AppRoot` to open the app-info dialog (menu bar / tray "앱 정보"). */
    var aboutRequest by mutableIntStateOf(0)
        private set

    /** Set by the composition each frame, so menu/tray actions always act on fresh state. */
    var onSelect: ((AppTab) -> Unit)? = null

    fun attach(ctx: Context) { context = ctx }

    fun select(tab: AppTab) {
        onSelect?.invoke(tab) ?: run { current = tab }
    }

    fun markCurrent(tab: AppTab) {
        current = tab
        context?.let { prefs(it).edit().putString(KEY_TAB, tab.name).apply() }
    }

    fun requestAbout() { aboutRequest++ }

    fun restoreTab(default: AppTab): AppTab {
        val name = context?.let { prefs(it).getString(KEY_TAB, null) } ?: return default
        return runCatching { AppTab.valueOf(name!!) }.getOrDefault(default)
    }

    /** The saved window geometry, or null when nothing was saved yet. */
    fun restoreWindow(): WindowGeometry? {
        val p = context?.let { prefs(it) } ?: return null
        val w = p.getFloat(KEY_W, 0f)
        val h = p.getFloat(KEY_H, 0f)
        if (w <= 0f || h <= 0f) return null
        val hasPos = p.contains(KEY_X) && p.contains(KEY_Y)
        return WindowGeometry(
            width = w,
            height = h,
            x = if (hasPos) p.getFloat(KEY_X, 0f) else null,
            y = if (hasPos) p.getFloat(KEY_Y, 0f) else null,
            maximized = p.getBoolean(KEY_MAX, false)
        )
    }

    fun saveWindow(width: Float, height: Float, x: Float?, y: Float?, maximized: Boolean) {
        context?.let { ctx ->
            val editor = prefs(ctx).edit()
                .putFloat(KEY_W, width).putFloat(KEY_H, height)
                .putBoolean(KEY_MAX, maximized)
            if (x != null && y != null) editor.putFloat(KEY_X, x).putFloat(KEY_Y, y)
            else editor.remove(KEY_X).remove(KEY_Y)
            editor.apply()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
