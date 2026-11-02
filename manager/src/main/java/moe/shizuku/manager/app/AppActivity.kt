package moe.shizuku.manager.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources.Theme
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import java.util.Locale
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import rikka.core.res.isNight
import rikka.material.app.LocaleDelegate
import rikka.material.app.MaterialActivity

abstract class AppActivity : MaterialActivity() {

    override fun attachBaseContext(newBase: Context) {
        val wrapped = runCatching {
            ShizukuSettings.initialize(newBase.applicationContext ?: newBase)
            val locale = ShizukuSettings.getLocale()
            LocaleDelegate.defaultLocale = locale
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            }
            newBase.createConfigurationContext(config)
        }.getOrDefault(newBase)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDark = resources.configuration.isNight()
        val barStyle = if (isDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.activity_open_enter,
                R.anim.activity_open_exit
            )
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.activity_close_enter,
                R.anim.activity_close_exit
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.activity_open_enter, R.anim.activity_open_exit)
        }
    }

    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.activity_open_enter, R.anim.activity_open_exit)
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.activity_close_enter, R.anim.activity_close_exit)
        }
    }

    override fun onApplyTranslucentSystemBars() {
        super.onApplyTranslucentSystemBars()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun computeUserThemeKey(): String {
        val localeTag = runCatching { ShizukuSettings.getLocale().toLanguageTag() }.getOrDefault("")
        return ThemeHelper.getTheme(this) + ThemeHelper.isUsingSystemColor() + localeTag
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(this), true)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
