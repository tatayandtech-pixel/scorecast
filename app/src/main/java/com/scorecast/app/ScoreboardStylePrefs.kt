package com.scorecast.app

import android.content.Context

/** Persists the fullscreen "Edit style" light/dark choice across app restarts — a local display
 *  preference only, never synced to Firebase (only this device renders the burned-in overlay). */
object ScoreboardStylePrefs {
    private const val PREFS_NAME = "scoreboard_style"
    private const val KEY_LIGHT = "light"

    fun isLight(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_LIGHT, false)

    fun setLight(context: Context, light: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIGHT, light).apply()
    }
}
