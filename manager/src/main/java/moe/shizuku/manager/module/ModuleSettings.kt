package moe.shizuku.manager.module

import androidx.annotation.StringRes
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings

object ModuleSettings {

    private const val KEY_ACCESS_MODE = "adb_modules_access_mode"
    private const val KEY_ALLOW_BACKGROUND_ACTIONS = "adb_modules_allow_background_actions"

    enum class AccessMode(
        val value: String,
        @param:StringRes val labelRes: Int,
        @param:StringRes val summaryRes: Int
    ) {
        SAFE(
            "safe",
            R.string.modules_access_mode_safe,
            R.string.modules_access_mode_safe_summary
        ),
        FULL(
            "full",
            R.string.modules_access_mode_full,
            R.string.modules_access_mode_full_summary
        );

        companion object {
            fun fromValue(value: String?): AccessMode {
                return entries.firstOrNull { it.value == value } ?: SAFE
            }
        }
    }

    fun getAccessMode(): AccessMode {
        return AccessMode.fromValue(
            ShizukuSettings.getPreferences().getString(KEY_ACCESS_MODE, AccessMode.SAFE.value)
        )
    }

    fun setAccessMode(mode: AccessMode) {
        ShizukuSettings.getPreferences().edit().putString(KEY_ACCESS_MODE, mode.value).apply()
    }

    fun allowBackgroundActions(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_ALLOW_BACKGROUND_ACTIONS, false)
    }

    fun setAllowBackgroundActions(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_ALLOW_BACKGROUND_ACTIONS, value).apply()
    }
}
