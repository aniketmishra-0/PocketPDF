package com.renameapk.pdfzip

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object AppThemeStore {

    enum class ThemeMode(val preferenceValue: String, val nightModeValue: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun fromPreferenceValue(value: String?): ThemeMode {
                return values().firstOrNull { mode ->
                    mode.preferenceValue == value
                } ?: SYSTEM
            }
        }
    }

    fun applySavedTheme(context: Context) {
        val savedMode = getThemeMode(context)
        if (AppCompatDelegate.getDefaultNightMode() != savedMode.nightModeValue) {
            AppCompatDelegate.setDefaultNightMode(savedMode.nightModeValue)
        }
    }

    fun getThemeMode(context: Context): ThemeMode {
        val savedValue = prefs(context).getString(KEY_THEME_MODE, ThemeMode.SYSTEM.preferenceValue)
        return ThemeMode.fromPreferenceValue(savedValue)
    }

    fun setThemeMode(context: Context, themeMode: ThemeMode) {
        prefs(context)
            .edit()
            .putString(KEY_THEME_MODE, themeMode.preferenceValue)
            .apply()
        if (AppCompatDelegate.getDefaultNightMode() != themeMode.nightModeValue) {
            AppCompatDelegate.setDefaultNightMode(themeMode.nightModeValue)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "app_theme_store"
    private const val KEY_THEME_MODE = "theme_mode"
}
