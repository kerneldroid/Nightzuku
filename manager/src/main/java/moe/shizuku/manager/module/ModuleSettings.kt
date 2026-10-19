package moe.shizuku.manager.module

import androidx.annotation.StringRes
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings

object ModuleSettings {

    private const val KEY_ACCESS_MODE = "adb_modules_access_mode"
    private const val KEY_ALLOW_BACKGROUND_ACTIONS = "adb_modules_allow_background_actions"
    private const val KEY_BACKGROUND_CHECK_ENABLED = "adb_modules_background_check"
    private const val KEY_LAST_BACKGROUND_CHECK_TIME = "adb_modules_last_background_check_time"
    private const val KEY_LAST_UPDATE_CHECK_TIME = "adb_modules_last_update_check_time"
    private const val KEY_CUSTOM_ACTION = "adb_modules_custom_action"
    private const val KEY_CUSTOM_SERVICE = "adb_modules_custom_service"
    private const val KEY_CUSTOM_WEB_BRIDGE = "adb_modules_custom_web_bridge"
    private const val KEY_CUSTOM_WEB_NETWORK = "adb_modules_custom_web_network"
    private const val KEY_CUSTOM_WEB_DOWNLOAD = "adb_modules_custom_web_download"
    private const val KEY_RECOMMAND_WEBUI = "adb_modules_recommand_webui"
    private const val KEY_RECOMMAND_ACTION = "adb_modules_recommand_action"
    private const val KEY_TRUSTED_MODULES = "adb_modules_trusted_modules"
    private const val KEY_IGNORED_UPDATE_MODULES = "adb_modules_ignored_update_modules"
    private const val KEY_CONNECTOR_ENABLED = "shizuku_connector_enabled"
    private const val KEY_UPDATE_FREQUENCY = "adb_modules_update_frequency"
    private const val KEY_AUTO_UPDATE_ENABLED = "adb_modules_auto_update"
    private const val KEY_UPDATE_WIFI_ONLY = "adb_modules_update_wifi_only"
    private const val KEY_UPDATE_NOTIFICATIONS = "adb_modules_update_notifications"
    private const val KEY_INSTALL_MODE = "adb_modules_install_mode"
    private const val KEY_CATALOG_ENABLED = "adb_modules_catalog_enabled"

    private const val SIX_HOURS_MS = 6L * 60L * 60L * 1000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val WEEK_MS = 7L * DAY_MS

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
        CUSTOM(
            "custom",
            R.string.modules_access_mode_custom,
            R.string.modules_access_mode_custom_summary
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

    data class CustomPermissions(
        val action: Boolean,
        val service: Boolean,
        val webBridge: Boolean,
        val webNetwork: Boolean,
        val webDownload: Boolean
    )

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

    fun isBackgroundCheckEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_BACKGROUND_CHECK_ENABLED, true)
    }

    fun setBackgroundCheckEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_BACKGROUND_CHECK_ENABLED, value).apply()
    }

    fun getLastBackgroundCheckTime(): Long {
        return ShizukuSettings.getPreferences().getLong(KEY_LAST_BACKGROUND_CHECK_TIME, 0L)
    }

    fun setLastBackgroundCheckTime(timestampMs: Long) {
        ShizukuSettings.getPreferences().edit().putLong(KEY_LAST_BACKGROUND_CHECK_TIME, timestampMs).apply()
    }

    fun getLastUpdateCheckTime(): Long {
        return ShizukuSettings.getPreferences().getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L)
    }

    fun setLastUpdateCheckTime(timestampMs: Long) {
        ShizukuSettings.getPreferences().edit().putLong(KEY_LAST_UPDATE_CHECK_TIME, timestampMs).apply()
    }

    fun isUpdateCheckDue(
        nowMs: Long = System.currentTimeMillis(),
        onServerStart: Boolean = false
    ): Boolean {
        val last = getLastUpdateCheckTime()
        return when (getUpdateFrequency()) {
            UpdateFrequency.MANUAL -> false
            UpdateFrequency.ON_START -> onServerStart
            UpdateFrequency.SIX_HOURS -> last <= 0L || (nowMs - last) >= SIX_HOURS_MS
            UpdateFrequency.DAILY -> last <= 0L || (nowMs - last) >= DAY_MS
            UpdateFrequency.WEEKLY -> last <= 0L || (nowMs - last) >= WEEK_MS
        }
    }

    fun isAutoUpdateEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_AUTO_UPDATE_ENABLED, false)
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
    }

    fun isUpdateWifiOnly(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_UPDATE_WIFI_ONLY, true)
    }

    fun setUpdateWifiOnly(wifiOnly: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_UPDATE_WIFI_ONLY, wifiOnly).apply()
    }

    fun isUpdateNotificationsEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_UPDATE_NOTIFICATIONS, true)
    }

    fun setUpdateNotificationsEnabled(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_UPDATE_NOTIFICATIONS, enabled).apply()
    }

    fun isModuleUpdateIgnored(moduleId: String): Boolean {
        return ShizukuSettings.getPreferences()
            .getStringSet(KEY_IGNORED_UPDATE_MODULES, emptySet())
            ?.contains(moduleId)
            ?: false
    }

    fun setModuleUpdateIgnored(moduleId: String, ignored: Boolean) {
        val prefs = ShizukuSettings.getPreferences()
        val current = prefs.getStringSet(KEY_IGNORED_UPDATE_MODULES, emptySet()).orEmpty().toMutableSet()
        if (ignored) {
            current += moduleId
        } else {
            current -= moduleId
        }
        prefs.edit().putStringSet(KEY_IGNORED_UPDATE_MODULES, current).apply()
    }

    fun getCustomPermissions(): CustomPermissions {
        val prefs = ShizukuSettings.getPreferences()
        return CustomPermissions(
            action = prefs.getBoolean(KEY_CUSTOM_ACTION, false),
            service = prefs.getBoolean(KEY_CUSTOM_SERVICE, false),
            webBridge = prefs.getBoolean(KEY_CUSTOM_WEB_BRIDGE, false),
            webNetwork = prefs.getBoolean(KEY_CUSTOM_WEB_NETWORK, false),
            webDownload = prefs.getBoolean(KEY_CUSTOM_WEB_DOWNLOAD, false)
        )
    }

    fun setCustomPermissions(value: CustomPermissions) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_CUSTOM_ACTION, value.action)
            .putBoolean(KEY_CUSTOM_SERVICE, value.service)
            .putBoolean(KEY_CUSTOM_WEB_BRIDGE, value.webBridge)
            .putBoolean(KEY_CUSTOM_WEB_NETWORK, value.webNetwork)
            .putBoolean(KEY_CUSTOM_WEB_DOWNLOAD, value.webDownload)
            .apply()
    }

    fun canRunAction(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().action
        }
    }

    fun canRunAction(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canRunAction()
    }

    fun canRunService(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().service
        }
    }

    fun canRunService(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canRunService()
    }

    fun canExposeWebBridge(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().webBridge
        }
    }

    fun canExposeWebBridge(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canExposeWebBridge()
    }

    fun canUseWebNetwork(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> false
            AccessMode.CUSTOM -> getCustomPermissions().webNetwork
        }
    }

    fun canUseWebNetwork(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canUseWebNetwork()
    }

    fun canDownloadWebFiles(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().webDownload
        }
    }

    fun canDownloadWebFiles(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canDownloadWebFiles()
    }

    fun canRunBackground(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || allowBackgroundActions()
    }

    fun isModuleTrusted(moduleId: String): Boolean {
        return ShizukuSettings.getPreferences()
            .getStringSet(KEY_TRUSTED_MODULES, emptySet())
            ?.contains(moduleId)
            ?: false
    }

    fun setModuleTrusted(moduleId: String, trusted: Boolean) {
        val prefs = ShizukuSettings.getPreferences()
        val current = prefs.getStringSet(KEY_TRUSTED_MODULES, emptySet()).orEmpty().toMutableSet()
        if (trusted) {
            current += moduleId
        } else {
            current -= moduleId
        }
        prefs.edit().putStringSet(KEY_TRUSTED_MODULES, current).apply()
    }

    fun recommandForWebUi(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_RECOMMAND_WEBUI, true)
    }

    fun setRecommandForWebUi(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_RECOMMAND_WEBUI, value).apply()
    }

    fun recommandForAction(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_RECOMMAND_ACTION, true)
    }

    fun setRecommandForAction(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_RECOMMAND_ACTION, value).apply()
    }

    fun isConnectorEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_CONNECTOR_ENABLED, false)
    }

    fun setConnectorEnabled(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_CONNECTOR_ENABLED, value).apply()
    }

    enum class UpdateFrequency(val value: String) {
        MANUAL("manual"),
        ON_START("on_start"),
        SIX_HOURS("6h"),
        DAILY("daily"),
        WEEKLY("weekly");

        companion object {
            fun fromValue(value: String?): UpdateFrequency {
                return entries.firstOrNull { it.value == value } ?: MANUAL
            }
        }
    }

    enum class InstallMode(val value: String) {
        SOURCES("sources"),
        RELEASE("release");

        companion object {
            fun fromValue(value: String?): InstallMode {
                return entries.firstOrNull { it.value == value } ?: SOURCES
            }
        }
    }

    fun getUpdateFrequency(): UpdateFrequency {
        return UpdateFrequency.fromValue(
            ShizukuSettings.getPreferences().getString(KEY_UPDATE_FREQUENCY, UpdateFrequency.MANUAL.value)
        )
    }

    fun setUpdateFrequency(freq: UpdateFrequency) {
        ShizukuSettings.getPreferences().edit().putString(KEY_UPDATE_FREQUENCY, freq.value).apply()
    }

    fun getInstallMode(): InstallMode {
        return InstallMode.fromValue(
            ShizukuSettings.getPreferences().getString(KEY_INSTALL_MODE, InstallMode.SOURCES.value)
        )
    }

    fun setInstallMode(mode: InstallMode) {
        ShizukuSettings.getPreferences().edit().putString(KEY_INSTALL_MODE, mode.value).apply()
    }

    fun isCatalogEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_CATALOG_ENABLED, true)
    }

    fun setCatalogEnabled(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_CATALOG_ENABLED, enabled).apply()
    }
}
