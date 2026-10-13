package moe.shizuku.manager.app

import android.content.Context
import android.os.Build
import androidx.annotation.StyleRes
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.core.util.ResourceUtils

object ThemeHelper {

    private const val THEME_DEFAULT = "DEFAULT"
    private const val THEME_BLACK = "BLACK"

    const val KEY_LIGHT_THEME = "light_theme"
    const val KEY_BLACK_NIGHT_THEME = "black_night_theme"
    const val KEY_USE_SYSTEM_COLOR = "use_system_color"

    @JvmStatic
    fun isBlackNightTheme(context: Context): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_BLACK_NIGHT_THEME, EnvironmentUtils.isWatch(context))
    }

    @JvmStatic
    fun isUsingSystemColor(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ShizukuSettings.getPreferences().getBoolean(KEY_USE_SYSTEM_COLOR, true)
    }

    @JvmStatic
    fun getTheme(context: Context): String {
        if (isBlackNightTheme(context)
                && ResourceUtils.isNightMode(context.resources.configuration)) {
            return THEME_BLACK
        }

        return ShizukuSettings.getPreferences().getString(KEY_LIGHT_THEME, THEME_DEFAULT) ?: THEME_DEFAULT
    }

    @StyleRes
    @JvmStatic
    fun getThemeStyleRes(context: Context): Int {
        return when (getTheme(context)) {
            THEME_BLACK -> R.style.ThemeOverlay_Black
            else -> R.style.ThemeOverlay
        }
    }
}
