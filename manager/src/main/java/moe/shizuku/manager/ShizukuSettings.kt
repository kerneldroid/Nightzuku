package moe.shizuku.manager

import android.app.ActivityThread
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.IntDef
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import moe.shizuku.manager.utils.EmptySharedPreferencesImpl
import moe.shizuku.manager.utils.EnvironmentUtils

object ShizukuSettings {

    const val NAME = "settings"
    const val NIGHT_MODE = "night_mode"
    const val LANGUAGE = "language"
    const val KEEP_START_ON_BOOT = "start_on_boot"

    private var sPreferences: SharedPreferences? = null

    @JvmStatic
    fun getPreferences(): SharedPreferences {
        return sPreferences!!
    }

    private fun getSettingsStorageContext(context: Context): Context {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

        return object : ContextWrapper(storageContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                return try {
                    super.getSharedPreferences(name, mode)
                } catch (e: IllegalStateException) {
                    EmptySharedPreferencesImpl()
                }
            }
        }
    }

    @JvmStatic
    fun initialize(context: Context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    @IntDef(value = [LaunchMethod.UNKNOWN, LaunchMethod.ROOT, LaunchMethod.ADB])
    @Retention(AnnotationRetention.SOURCE)
    annotation class LaunchMethod {
        companion object {
            const val UNKNOWN = -1
            const val ROOT = 0
            const val ADB = 1
        }
    }

    @JvmStatic
    @LaunchMethod
    fun getLastLaunchMode(): Int {
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN)
    }

    @JvmStatic
    fun setLastLaunchMode(@LaunchMethod method: Int) {
        getPreferences().edit().putInt("mode", method).apply()
    }

    @JvmStatic
    @AppCompatDelegate.NightMode
    fun getNightMode(): Int {
        var defValue = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        if (EnvironmentUtils.isWatch(ActivityThread.currentActivityThread().application)) {
            defValue = AppCompatDelegate.MODE_NIGHT_YES
        }
        return getPreferences().getInt(NIGHT_MODE, defValue)
    }

    @JvmStatic
    fun getLocale(): Locale {
        val tag = getPreferences().getString(LANGUAGE, null)
        if (tag.isNullOrEmpty() || "SYSTEM" == tag) {
            return Locale.getDefault()
        }
        return Locale.forLanguageTag(tag)
    }
}
