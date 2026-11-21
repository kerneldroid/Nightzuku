@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package moe.shizuku.manager.settings

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LANGUAGE
import moe.shizuku.manager.ShizukuSettings.NIGHT_MODE
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.module.update.ModuleUpdateScheduler
import moe.shizuku.manager.module.update.UpdateSettingsScreen
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.receiver.AdbBootStartScheduler
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.ui.compose.SettingsItem
import moe.shizuku.manager.ui.compose.SettingsSection
import moe.shizuku.manager.ui.compose.SettingsSwitchItem
import moe.shizuku.manager.ui.compose.AnimatedScreenContent
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.htmlToPlainText
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku
import rikka.shizuku.manager.ShizukuLocales
import kotlinx.coroutines.delay
import java.util.Locale

private enum class SettingsCategory(
    @param:StringRes val title: Int,
    @param:StringRes val subtitle: Int,
    @param:DrawableRes val icon: Int
) {
    STARTUP(
        R.string.settings_startup,
        R.string.settings_category_startup_summary,
        R.drawable.ic_server_restart
    ),
    SECURITY(
        R.string.settings_security,
        R.string.settings_category_security_summary,
        R.drawable.ic_shield_24
    ),
    APPEARANCE(
        R.string.settings_user_interface,
        R.string.settings_category_appearance_summary,
        R.drawable.ic_outline_dark_mode_24
    ),
    LANGUAGE(
        R.string.settings_language,
        R.string.settings_category_language_summary,
        R.drawable.ic_outline_translate_24
    ),
    MODULES(
        R.string.modules_settings_title,
        R.string.settings_category_modules_summary,
        R.drawable.ic_adb_24dp
    )
}

private sealed interface PhoneSettingsRoute {
    val depth: Int

    data object Root : PhoneSettingsRoute {
        override val depth: Int = 0
    }

    data class Category(val category: SettingsCategory) : PhoneSettingsRoute {
        override val depth: Int = 1
    }

    data object UpdateSettings : PhoneSettingsRoute {
        override val depth: Int = 2
    }
}

private enum class SecondarySubscreen(val depth: Int) {
    ROOT(0),
    LAB_FEATURES(1)
}

private val PhoneSettingsRouteSaver = Saver<PhoneSettingsRoute, String>(
    save = { route ->
        when (route) {
            PhoneSettingsRoute.Root -> "root"
            is PhoneSettingsRoute.Category -> "category:${route.category.name}"
            PhoneSettingsRoute.UpdateSettings -> "update"
        }
    },
    restore = { value ->
        when {
            value == "root" -> PhoneSettingsRoute.Root
            value == "update" -> PhoneSettingsRoute.UpdateSettings
            value.startsWith("category:") -> {
                val name = value.substringAfter("category:")
                SettingsCategory.entries.firstOrNull { it.name == name }
                    ?.let { PhoneSettingsRoute.Category(it) }
                    ?: PhoneSettingsRoute.Root
            }
            else -> PhoneSettingsRoute.Root
        }
    }
)

@Composable
private fun settingsCategoryAccent(index: Int): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (index % 3) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
}

class SettingsActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val componentName = ComponentName(packageName, BootCompleteReceiver::class.java.name)

        setContent {
            val prefs = ShizukuSettings.getPreferences()
            var startOnBoot by remember {
                mutableStateOf(packageManager.isComponentEnabled(componentName))
            }
            var languageTag by remember {
                mutableStateOf(prefs.getString(LANGUAGE, "SYSTEM") ?: "SYSTEM")
            }
            var nightMode by remember {
                mutableIntStateOf(ShizukuSettings.getNightMode())
            }
            var blackNightTheme by remember {
                mutableStateOf(ThemeHelper.isBlackNightTheme(this))
            }
            var useSystemColor by remember {
                mutableStateOf(ThemeHelper.isUsingSystemColor())
            }
            var showLanguageDialog by remember { mutableStateOf(false) }
            var showNightDialog by remember { mutableStateOf(false) }
            var showModuleModeDialog by remember { mutableStateOf(false) }
            var showCustomPermissionsDialog by remember { mutableStateOf(false) }
            var showDeliveryModeDialog by remember { mutableStateOf(false) }
            val isRooted = remember { EnvironmentUtils.isRooted() }
            var bootLaunchMethod by remember {
                mutableIntStateOf(ShizukuSettings.resolveBootLaunchMethod(isRooted))
            }
            var bootDiscoveryTimeoutSec by remember {
                mutableIntStateOf(ShizukuSettings.getBootDiscoveryTimeoutSeconds())
            }
            var bootMaxRetries by remember {
                mutableIntStateOf(ShizukuSettings.getBootMaxRetries())
            }
            var bootFallbackNotification by remember {
                mutableStateOf(ShizukuSettings.isBootFallbackNotificationEnabled())
            }
            var showBootMethodDialog by remember { mutableStateOf(false) }
            var showBootTimeoutDialog by remember { mutableStateOf(false) }
            var showBootRetriesDialog by remember { mutableStateOf(false) }
            var serverRunning by remember { mutableStateOf(SecurityPolicyClient.isServerAvailable()) }
            var securityPolicy by remember {
                mutableStateOf(SecurityPolicyClient.getSecurityPolicy() ?: SecurityPolicyState())
            }
            var deviceUsers by remember {
                mutableStateOf(SecurityPolicyClient.getDeviceUsers())
            }

            fun refreshSecurityState() {
                val running = SecurityPolicyClient.isServerAvailable()
                serverRunning = running
                if (running) {
                    SecurityPolicyClient.getSecurityPolicy()?.let { securityPolicy = it }
                    deviceUsers = SecurityPolicyClient.getDeviceUsers()
                } else {
                    deviceUsers = emptyList()
                }
            }

            fun updateSecurityPolicy(transform: (SecurityPolicyState) -> SecurityPolicyState) {
                val updated = transform(securityPolicy)
                if (SecurityPolicyClient.setSecurityPolicy(updated)) {
                    securityPolicy = updated
                    deviceUsers = SecurityPolicyClient.getDeviceUsers()
                } else {
                    refreshSecurityState()
                }
            }

            DisposableEffect(Unit) {
                val receivedListener = Shizuku.OnBinderReceivedListener { refreshSecurityState() }
                val deadListener = Shizuku.OnBinderDeadListener { refreshSecurityState() }
                Shizuku.addBinderReceivedListenerSticky(receivedListener)
                Shizuku.addBinderDeadListener(deadListener)
                onDispose {
                    Shizuku.removeBinderReceivedListener(receivedListener)
                    Shizuku.removeBinderDeadListener(deadListener)
                }
            }
            var phoneRoute by rememberSaveable(stateSaver = PhoneSettingsRouteSaver) {
                mutableStateOf<PhoneSettingsRoute>(PhoneSettingsRoute.Root)
            }
            var secondarySubscreen by rememberSaveable {
                mutableStateOf(SecondarySubscreen.ROOT)
            }

            var moduleAccessMode by remember {
                mutableStateOf(ModuleSettings.getAccessMode())
            }
            var customPermissions by remember {
                mutableStateOf(ModuleSettings.getCustomPermissions())
            }
            var allowBackgroundActions by remember {
                mutableStateOf(ModuleSettings.allowBackgroundActions())
            }
            var backgroundCheckEnabled by remember {
                mutableStateOf(ModuleSettings.isBackgroundCheckEnabled())
            }
            var recommandWebUi by remember {
                mutableStateOf(ModuleSettings.recommandForWebUi())
            }
            var recommandAction by remember {
                mutableStateOf(ModuleSettings.recommandForAction())
            }
            var recreateTick by remember { mutableIntStateOf(0) }

            val localeOptions = remember(languageTag) {
                buildLocaleOptions(languageTag)
            }
            val languageSummary = localeOptions.firstOrNull { it.tag == languageTag }?.title
                ?: stringResource(R.string.follow_system)
            val nightValues = resources.getIntArray(R.array.night_mode_value).toList()
            val nightLabels = stringArrayResource(R.array.night_mode).toList()
            val nightSummary = nightLabels.getOrElse(nightValues.indexOf(nightMode)) {
                stringResource(R.string.follow_system)
            }
            val contributors = htmlToPlainText(getString(R.string.translation_contributors))

            LaunchedEffect(recreateTick) {
                if (recreateTick > 0) {
                    delay(260)
                    recreate()
                }
            }

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@SettingsActivity)
            val isTv = moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@SettingsActivity)

            fun refreshModuleToggles() {
                allowBackgroundActions = ModuleSettings.allowBackgroundActions()
                backgroundCheckEnabled = ModuleSettings.isBackgroundCheckEnabled()
            }

            fun updateStartOnBoot(enabled: Boolean) {
                packageManager.setComponentEnabled(componentName, enabled)
                ShizukuSettings.setStartOnBootEnabled(enabled)
                if (!enabled) {
                    AdbBootStartScheduler.cancel(this@SettingsActivity)
                }
                startOnBoot = packageManager.isComponentEnabled(componentName)
            }

            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                if (isWatch) {
                    BackHandler(enabled = secondarySubscreen != SecondarySubscreen.ROOT) {
                        refreshModuleToggles()
                        secondarySubscreen = SecondarySubscreen.ROOT
                    }
                    moe.shizuku.manager.ui.compose.WearShizukuTheme {
                        AnimatedScreenContent(
                            targetState = secondarySubscreen,
                            label = "settingsWatchRoute",
                            depth = { it.depth }
                        ) { subscreen ->
                            when (subscreen) {
                                else -> {
                                    WearSettingsScreen(
                                        startOnBoot = startOnBoot,
                                        onStartOnBootChange = { updateStartOnBoot(it) },
                                        languageSummary = languageSummary,
                                        onLanguageClick = { showLanguageDialog = true },
                                        nightModeSummary = nightSummary,
                                        onNightModeClick = { showNightDialog = true },
                                        blackNightTheme = blackNightTheme,
                                        onBlackNightThemeChange = { enabled ->
                                            prefs.edit().putBoolean(KEY_BLACK_NIGHT_THEME, enabled).apply()
                                            blackNightTheme = enabled
                                            if (rikka.core.util.ResourceUtils.isNightMode(resources.configuration)) {
                                                recreateTick++
                                            }
                                        },
                                        useSystemColor = useSystemColor,
                                        onUseSystemColorChange = { enabled ->
                                            prefs.edit().putBoolean(KEY_USE_SYSTEM_COLOR, enabled).apply()
                                            useSystemColor = enabled
                                            recreateTick++
                                        },
                                        onLabFeaturesClick = {
                                            startActivity(android.content.Intent(this@SettingsActivity, LabFeaturesActivity::class.java))
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else if (isTv) {
                    BackHandler(enabled = secondarySubscreen != SecondarySubscreen.ROOT) {
                        refreshModuleToggles()
                        secondarySubscreen = SecondarySubscreen.ROOT
                    }
                    moe.shizuku.manager.ui.compose.TvShizukuTheme {
                        AnimatedScreenContent(
                            targetState = secondarySubscreen,
                            label = "settingsTvRoute",
                            depth = { it.depth }
                        ) { subscreen ->
                            when (subscreen) {
                                SecondarySubscreen.LAB_FEATURES -> {
                                    TvLabMenuScreen(
                                        onNavigateUp = {
                                            refreshModuleToggles()
                                            secondarySubscreen = SecondarySubscreen.ROOT
                                        }
                                    )
                                }
                                else -> {
                                    TvSettingsScreen(
                                        onNavigateUp = { finish() },
                                        startOnBoot = startOnBoot,
                                        onStartOnBootChange = { updateStartOnBoot(it) },
                                        languageSummary = languageSummary,
                                        onLanguageClick = { showLanguageDialog = true },
                                        nightSummary = nightSummary,
                                        onNightModeClick = { showNightDialog = true },
                                        blackNightTheme = blackNightTheme,
                                        onBlackNightThemeChange = { enabled ->
                                            prefs.edit().putBoolean(KEY_BLACK_NIGHT_THEME, enabled).apply()
                                            blackNightTheme = enabled
                                            if (rikka.core.util.ResourceUtils.isNightMode(resources.configuration)) {
                                                recreateTick++
                                            }
                                        },
                                        useSystemColor = useSystemColor,
                                        onUseSystemColorChange = { enabled ->
                                            prefs.edit().putBoolean(KEY_USE_SYSTEM_COLOR, enabled).apply()
                                            useSystemColor = enabled
                                            recreateTick++
                                        },
                                        onLabFeaturesClick = {
                                            secondarySubscreen = SecondarySubscreen.LAB_FEATURES
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    ShizukuExpressiveTheme {
                        BackHandler(enabled = phoneRoute != PhoneSettingsRoute.Root) {
                            refreshModuleToggles()
                            phoneRoute = when (phoneRoute) {
                                is PhoneSettingsRoute.UpdateSettings -> PhoneSettingsRoute.Category(SettingsCategory.MODULES)
                                else -> PhoneSettingsRoute.Root
                            }
                        }

                        AnimatedScreenContent(
                            targetState = phoneRoute,
                            label = "settingsPhoneRoute",
                            depth = { it.depth }
                        ) { route ->
                            when (route) {
                                is PhoneSettingsRoute.UpdateSettings -> {
                                    UpdateSettingsScreen(
                                        onNavigateUp = {
                                            refreshModuleToggles()
                                            phoneRoute = PhoneSettingsRoute.Category(SettingsCategory.MODULES)
                                        }
                                    )
                                }

                                is PhoneSettingsRoute.Root -> {
                                    ShizukuLazyScaffold(
                                        title = stringResource(R.string.settings_title),
                                        onNavigateUp = { finish() }
                                    ) {
                                        item {
                                            val categoryCount = SettingsCategory.entries.size + 1
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                                            ) {
                                                SettingsCategory.entries.forEachIndexed { index, category ->
                                                    val (container, onContainer) = settingsCategoryAccent(index)
                                                    SegmentedListItem(
                                                        onClick = { phoneRoute = PhoneSettingsRoute.Category(category) },
                                                        shapes = ListItemDefaults.segmentedShapes(
                                                            index = index,
                                                            count = categoryCount
                                                        ),
                                                        colors = ListItemDefaults.segmentedColors(
                                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                                        ),
                                                        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 14.dp),
                                                        leadingContent = {
                                                            ShizukuIcon(
                                                                icon = category.icon,
                                                                contentDescription = null,
                                                                tint = onContainer,
                                                                modifier = Modifier
                                                                    .background(container, CircleShape)
                                                                    .padding(10.dp)
                                                            )
                                                        },
                                                        supportingContent = { Text(stringResource(category.subtitle)) }
                                                    ) {
                                                        Text(stringResource(category.title))
                                                    }
                                                }
                                                val labIndex = SettingsCategory.entries.size
                                                val (labContainer, labOnContainer) = settingsCategoryAccent(labIndex)
                                                SegmentedListItem(
                                                    onClick = {
                                                        startActivity(android.content.Intent(this@SettingsActivity, LabFeaturesActivity::class.java))
                                                    },
                                                    shapes = ListItemDefaults.segmentedShapes(
                                                        index = labIndex,
                                                        count = categoryCount
                                                    ),
                                                    colors = ListItemDefaults.segmentedColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                                    ),
                                                    contentPadding = PaddingValues(vertical = 14.dp, horizontal = 14.dp),
                                                    leadingContent = {
                                                        ShizukuIcon(
                                                            icon = R.drawable.ic_code_24dp,
                                                            contentDescription = null,
                                                            tint = labOnContainer,
                                                            modifier = Modifier
                                                                .background(labContainer, CircleShape)
                                                                .padding(10.dp)
                                                        )
                                                    },
                                                    supportingContent = { Text(stringResource(R.string.lab_features_summary)) }
                                                ) {
                                                    Text(stringResource(R.string.lab_features_title))
                                                }
                                            }
                                        }
                                    }
                                }

                                is PhoneSettingsRoute.Category -> {
                                    val activeCategory = route.category
                                    ShizukuLazyScaffold(
                                        title = stringResource(activeCategory.title),
                                        onNavigateUp = { phoneRoute = PhoneSettingsRoute.Root }
                                    ) {
                                        when (activeCategory) {
                                            SettingsCategory.STARTUP -> item {
                                                val methodSummary = if (bootLaunchMethod == LaunchMethod.ROOT && isRooted) {
                                                    stringResource(R.string.settings_boot_method_root)
                                                } else if (!isRooted) {
                                                    stringResource(R.string.settings_boot_method_wireless_only_summary)
                                                } else {
                                                    stringResource(R.string.settings_boot_method_wireless)
                                                }

                                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    SettingsSection(stringResource(R.string.settings_startup)) {
                                                        SettingsSwitchItem(
                                                            index = 0,
                                                            count = 2,
                                                            icon = R.drawable.ic_server_restart,
                                                            title = stringResource(R.string.settings_start_on_boot),
                                                            summary = stringResource(R.string.settings_start_on_boot_summary),
                                                            checked = startOnBoot,
                                                            onCheckedChange = { updateStartOnBoot(it) }
                                                        )
                                                        SettingsItem(
                                                            index = 1,
                                                            count = 2,
                                                            icon = if (bootLaunchMethod == LaunchMethod.ROOT && isRooted) {
                                                                R.drawable.ic_root_24dp
                                                            } else {
                                                                R.drawable.ic_wadb_24
                                                            },
                                                            title = stringResource(R.string.settings_boot_method_title),
                                                            summary = methodSummary,
                                                            enabled = startOnBoot,
                                                            onClick = { showBootMethodDialog = true }
                                                        )
                                                    }

                                                    if (startOnBoot && bootLaunchMethod != LaunchMethod.ROOT) {
                                                        SettingsSection(stringResource(R.string.settings_startup_wireless_options)) {
                                                            SettingsItem(
                                                                index = 0,
                                                                count = 3,
                                                                icon = R.drawable.ic_wadb_24,
                                                                title = stringResource(R.string.settings_boot_timeout_title),
                                                                summary = stringResource(R.string.settings_boot_timeout_summary, bootDiscoveryTimeoutSec),
                                                                onClick = { showBootTimeoutDialog = true }
                                                            )
                                                            SettingsItem(
                                                                index = 1,
                                                                count = 3,
                                                                icon = R.drawable.ic_server_restart,
                                                                title = stringResource(R.string.settings_boot_retries_title),
                                                                summary = stringResource(R.string.settings_boot_retries_summary, bootMaxRetries),
                                                                onClick = { showBootRetriesDialog = true }
                                                            )
                                                            SettingsSwitchItem(
                                                                index = 2,
                                                                count = 3,
                                                                icon = R.drawable.ic_outline_notifications_active_24,
                                                                title = stringResource(R.string.settings_boot_fallback_notification_title),
                                                                summary = stringResource(R.string.settings_boot_fallback_notification_summary),
                                                                checked = bootFallbackNotification,
                                                                onCheckedChange = { enabled ->
                                                                    ShizukuSettings.setBootFallbackNotificationEnabled(enabled)
                                                                    bootFallbackNotification = enabled
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            SettingsCategory.SECURITY -> item {
                                                LaunchedEffect(Unit) { refreshSecurityState() }
                                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    if (!serverRunning) {
                                                        SettingsSection(stringResource(R.string.settings_security)) {
                                                            SettingsItem(
                                                                index = 0,
                                                                count = 1,
                                                                icon = R.drawable.ic_warning_24,
                                                                title = stringResource(R.string.settings_security_server_stopped_title),
                                                                summary = stringResource(R.string.settings_security_server_stopped_summary),
                                                                showChevron = false,
                                                                onClick = { refreshSecurityState() }
                                                            )
                                                        }
                                                    } else {
                                                        SettingsSection(stringResource(R.string.settings_security_section_protection)) {
                                                            SettingsSwitchItem(
                                                                index = 0,
                                                                count = 2,
                                                                icon = R.drawable.ic_shield_24,
                                                                title = stringResource(R.string.settings_security_pause_access_title),
                                                                summary = stringResource(R.string.settings_security_pause_access_summary),
                                                                checked = securityPolicy.accessPaused,
                                                                onCheckedChange = { paused ->
                                                                    updateSecurityPolicy { it.copy(accessPaused = paused) }
                                                                }
                                                            )
                                                            SettingsSwitchItem(
                                                                index = 1,
                                                                count = 2,
                                                                icon = R.drawable.ic_shield_24,
                                                                title = stringResource(R.string.settings_security_block_isolated_title),
                                                                summary = stringResource(R.string.settings_security_block_isolated_summary),
                                                                checked = securityPolicy.blockIsolatedProfiles,
                                                                onCheckedChange = { blocked ->
                                                                    updateSecurityPolicy { it.copy(blockIsolatedProfiles = blocked) }
                                                                }
                                                            )
                                                        }

                                                        val deliverySummary = if (
                                                            securityPolicy.binderDeliveryMode == SecurityPolicyState.BINDER_DELIVERY_AUTHORIZED_ONLY
                                                        ) {
                                                            stringResource(R.string.settings_security_delivery_authorized_only)
                                                        } else {
                                                            stringResource(R.string.settings_security_delivery_all_declared)
                                                        }

                                                        SettingsSection(stringResource(R.string.settings_security_section_delivery)) {
                                                            SettingsItem(
                                                                index = 0,
                                                                count = 2,
                                                                icon = R.drawable.ic_settings_outline_24dp,
                                                                title = stringResource(R.string.settings_security_delivery_mode_title),
                                                                summary = deliverySummary,
                                                                onClick = { showDeliveryModeDialog = true }
                                                            )
                                                            SettingsSwitchItem(
                                                                index = 1,
                                                                count = 2,
                                                                icon = R.drawable.ic_baseline_link_24,
                                                                title = stringResource(R.string.settings_security_legacy_wire_title),
                                                                summary = stringResource(R.string.settings_security_legacy_wire_summary),
                                                                checked = securityPolicy.allowLegacyWire,
                                                                onCheckedChange = { allowed ->
                                                                    updateSecurityPolicy { it.copy(allowLegacyWire = allowed) }
                                                                }
                                                            )
                                                        }

                                                        SettingsSection(stringResource(R.string.settings_security_section_users)) {
                                                            SettingsSwitchItem(
                                                                index = 0,
                                                                count = 2,
                                                                icon = R.drawable.ic_system_icon,
                                                                title = stringResource(R.string.settings_security_allow_work_profile_title),
                                                                summary = stringResource(R.string.settings_security_allow_work_profile_summary),
                                                                checked = securityPolicy.allowWorkProfile,
                                                                onCheckedChange = { allowed ->
                                                                    updateSecurityPolicy { it.copy(allowWorkProfile = allowed) }
                                                                }
                                                            )
                                                            SettingsSwitchItem(
                                                                index = 1,
                                                                count = 2,
                                                                icon = R.drawable.ic_system_icon,
                                                                title = stringResource(R.string.settings_security_allow_secondary_users_title),
                                                                summary = stringResource(R.string.settings_security_allow_secondary_users_summary),
                                                                checked = securityPolicy.allowSecondaryUsers,
                                                                onCheckedChange = { allowed ->
                                                                    updateSecurityPolicy { it.copy(allowSecondaryUsers = allowed) }
                                                                }
                                                            )
                                                        }

                                                        if (deviceUsers.isNotEmpty()) {
                                                            SettingsSection(stringResource(R.string.settings_security_section_detected_users)) {
                                                                deviceUsers.forEachIndexed { index, user ->
                                                                    val kindLabel = when (user.kind) {
                                                                        DeviceUserEntry.KIND_PRIMARY -> stringResource(R.string.settings_security_user_kind_primary)
                                                                        DeviceUserEntry.KIND_WORK_PROFILE -> stringResource(R.string.settings_security_user_kind_work)
                                                                        DeviceUserEntry.KIND_ISOLATED_CONTAINER -> stringResource(R.string.settings_security_user_kind_isolated)
                                                                        else -> stringResource(R.string.settings_security_user_kind_secondary)
                                                                    }
                                                                    val userTitle = "${user.name} (ID ${user.userId})"
                                                                    if (user.isPrimary) {
                                                                        SettingsItem(
                                                                            index = index,
                                                                            count = deviceUsers.size,
                                                                            icon = R.drawable.ic_shield_24,
                                                                            title = userTitle,
                                                                            summary = "$kindLabel • ${stringResource(R.string.settings_security_user_always_allowed)}",
                                                                            showChevron = false,
                                                                            onClick = null
                                                                        )
                                                                    } else if (user.isIsolated && securityPolicy.blockIsolatedProfiles) {
                                                                        SettingsItem(
                                                                            index = index,
                                                                            count = deviceUsers.size,
                                                                            icon = R.drawable.ic_warning_24,
                                                                            title = userTitle,
                                                                            summary = "$kindLabel • ${stringResource(R.string.settings_security_user_blocked_by_policy)}",
                                                                            showChevron = false,
                                                                            onClick = null
                                                                        )
                                                                    } else {
                                                                        SettingsSwitchItem(
                                                                            index = index,
                                                                            count = deviceUsers.size,
                                                                            icon = R.drawable.ic_system_icon,
                                                                            title = userTitle,
                                                                            summary = kindLabel,
                                                                            checked = user.allowed,
                                                                            onCheckedChange = { enabled ->
                                                                                updateSecurityPolicy { cur ->
                                                                                    val nextUsers = cur.allowedUserIds.toMutableSet()
                                                                                    if (enabled) {
                                                                                        nextUsers.add(user.userId)
                                                                                    } else {
                                                                                        nextUsers.remove(user.userId)
                                                                                    }
                                                                                    cur.copy(
                                                                                        allowedUserIds = nextUsers,
                                                                                        allowWorkProfile = if (!enabled && user.isWorkProfile) false else cur.allowWorkProfile,
                                                                                        allowSecondaryUsers = if (!enabled && !user.isWorkProfile && !user.isIsolated) false else cur.allowSecondaryUsers
                                                                                    )
                                                                                }
                                                                            }
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            SettingsCategory.APPEARANCE -> {
                                                val showBlackNightTheme = nightMode != AppCompatDelegate.MODE_NIGHT_NO
                                                val showSystemColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                                val itemCount = 1 +
                                                    (if (showBlackNightTheme) 1 else 0) +
                                                    (if (showSystemColor) 1 else 0)
                                                item {
                                                    SettingsSection(stringResource(R.string.settings_user_interface)) {
                                                        SettingsItem(
                                                            index = 0,
                                                            count = itemCount,
                                                            icon = R.drawable.ic_outline_dark_mode_24,
                                                            title = stringResource(R.string.dark_theme),
                                                            summary = nightSummary,
                                                            onClick = { showNightDialog = true }
                                                        )
                                                        if (showBlackNightTheme) {
                                                            SettingsSwitchItem(
                                                                index = 1,
                                                                count = itemCount,
                                                                icon = R.drawable.ic_outline_dark_mode_24,
                                                                title = stringResource(R.string.settings_black_night_theme),
                                                                summary = stringResource(R.string.settings_black_night_theme_summary),
                                                                checked = blackNightTheme,
                                                                onCheckedChange = { enabled ->
                                                                    prefs.edit().putBoolean(KEY_BLACK_NIGHT_THEME, enabled).apply()
                                                                    blackNightTheme = enabled
                                                                    if (ResourceUtils.isNightMode(resources.configuration)) {
                                                                        recreateTick++
                                                                    }
                                                                }
                                                            )
                                                        }
                                                        if (showSystemColor) {
                                                            SettingsSwitchItem(
                                                                index = if (showBlackNightTheme) 2 else 1,
                                                                count = itemCount,
                                                                icon = R.drawable.ic_settings_outline_24dp,
                                                                title = stringResource(R.string.settings_use_system_color),
                                                                checked = useSystemColor,
                                                                onCheckedChange = { enabled ->
                                                                    prefs.edit().putBoolean(KEY_USE_SYSTEM_COLOR, enabled).apply()
                                                                    useSystemColor = enabled
                                                                    recreateTick++
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            SettingsCategory.LANGUAGE -> {
                                                val showContributors = contributors.isNotBlank()
                                                val itemCount = 2 + (if (showContributors) 1 else 0)
                                                item {
                                                    SettingsSection(stringResource(R.string.settings_language)) {
                                                        SettingsItem(
                                                            index = 0,
                                                            count = itemCount,
                                                            icon = R.drawable.ic_outline_translate_24,
                                                            title = stringResource(R.string.settings_language),
                                                            summary = languageSummary,
                                                            onClick = { showLanguageDialog = true }
                                                        )
                                                        if (showContributors) {
                                                            SettingsItem(
                                                                index = 1,
                                                                count = itemCount,
                                                                icon = R.drawable.ic_outline_info_24,
                                                                title = stringResource(R.string.settings_translation_contributors),
                                                                summary = contributors,
                                                                showChevron = false,
                                                                onClick = null
                                                            )
                                                        }
                                                        SettingsItem(
                                                            index = if (showContributors) 2 else 1,
                                                            count = itemCount,
                                                            icon = R.drawable.ic_baseline_link_24,
                                                            title = stringResource(R.string.settings_translation),
                                                            summary = stringResource(
                                                                R.string.settings_translation_summary,
                                                                stringResource(R.string.app_name)
                                                            ),
                                                            onClick = {
                                                                CustomTabsHelper.launchUrlOrCopy(this@SettingsActivity, getString(R.string.translation_url))
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            SettingsCategory.MODULES -> {
                                                val showCustomPermissions = moduleAccessMode == ModuleSettings.AccessMode.CUSTOM
                                                val accessItemCount = 3 + (if (showCustomPermissions) 1 else 0)
                                                item {
                                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        SettingsSection(stringResource(R.string.modules_settings_title)) {
                                                            var idx = 0
                                                            SettingsItem(
                                                                index = idx++,
                                                                count = accessItemCount,
                                                                icon = R.drawable.ic_settings_outline_24dp,
                                                                title = stringResource(R.string.modules_access_mode),
                                                                summary = stringResource(moduleAccessMode.labelRes),
                                                                onClick = { showModuleModeDialog = true }
                                                            )
                                                            if (showCustomPermissions) {
                                                                SettingsItem(
                                                                    index = idx++,
                                                                    count = accessItemCount,
                                                                    icon = R.drawable.ic_add_24,
                                                                    title = stringResource(R.string.modules_custom_permissions),
                                                                    summary = stringResource(R.string.modules_custom_permissions_summary),
                                                                    onClick = { showCustomPermissionsDialog = true }
                                                                )
                                                            }
                                                            SettingsSwitchItem(
                                                                index = idx++,
                                                                count = accessItemCount,
                                                                icon = R.drawable.ic_terminal_24,
                                                                title = stringResource(R.string.modules_background_actions),
                                                                summary = stringResource(R.string.modules_background_actions_summary),
                                                                checked = allowBackgroundActions,
                                                                onCheckedChange = { enabled ->
                                                                    ModuleSettings.setAllowBackgroundActions(enabled)
                                                                    allowBackgroundActions = enabled
                                                                }
                                                            )
                                                            SettingsItem(
                                                                index = idx,
                                                                count = accessItemCount,
                                                                icon = R.drawable.ic_outline_notifications_active_24,
                                                                title = stringResource(R.string.update_settings_title),
                                                                summary = stringResource(R.string.update_settings_entry_summary),
                                                                onClick = { phoneRoute = PhoneSettingsRoute.UpdateSettings }
                                                            )
                                                        }
                                                        SettingsSection(stringResource(R.string.settings_modules_recommendations)) {
                                                            SettingsSwitchItem(
                                                                index = 0,
                                                                count = 2,
                                                                icon = R.drawable.ic_warning_24,
                                                                title = stringResource(R.string.modules_recommand_webui),
                                                                summary = stringResource(R.string.modules_recommand_webui_summary),
                                                                checked = recommandWebUi,
                                                                onCheckedChange = { enabled ->
                                                                    ModuleSettings.setRecommandForWebUi(enabled)
                                                                    recommandWebUi = enabled
                                                                }
                                                            )
                                                            SettingsSwitchItem(
                                                                index = 1,
                                                                count = 2,
                                                                icon = R.drawable.ic_warning_24,
                                                                title = stringResource(R.string.modules_recommand_action),
                                                                summary = stringResource(R.string.modules_recommand_action_summary),
                                                                checked = recommandAction,
                                                                onCheckedChange = { enabled ->
                                                                    ModuleSettings.setRecommandForAction(enabled)
                                                                    recommandAction = enabled
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showLanguageDialog) {
                    val choices = remember {
                        localeOptions.map {
                            ChoiceOption(title = it.title, summary = it.summary, icon = R.drawable.ic_outline_translate_24)
                        }
                    }
                    val selectedIndex = localeOptions.indexOfFirst { it.tag == languageTag }
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            WearChoiceDialog(
                                title = stringResource(R.string.settings_language),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showLanguageDialog = false },
                                onSelect = { index ->
                                    val tag = localeOptions[index].tag
                                    prefs.edit().putString(LANGUAGE, tag).apply()
                                    languageTag = tag
                                    LocaleDelegate.defaultLocale = if (tag == "SYSTEM") LocaleDelegate.systemLocale else Locale.forLanguageTag(tag)
                                    showLanguageDialog = false
                                    recreate()
                                }
                            )
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvChoiceDialog(
                                title = stringResource(R.string.settings_language),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showLanguageDialog = false },
                                onSelect = { index ->
                                    val tag = localeOptions[index].tag
                                    prefs.edit().putString(LANGUAGE, tag).apply()
                                    languageTag = tag
                                    LocaleDelegate.defaultLocale = if (tag == "SYSTEM") LocaleDelegate.systemLocale else Locale.forLanguageTag(tag)
                                    showLanguageDialog = false
                                    recreate()
                                }
                            )
                        }
                    } else {
                        ShizukuExpressiveTheme {
                            ChoiceDialog(
                                title = stringResource(R.string.settings_language),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showLanguageDialog = false },
                                onSelect = { index ->
                                    val tag = localeOptions[index].tag
                                    prefs.edit().putString(LANGUAGE, tag).apply()
                                    languageTag = tag
                                    LocaleDelegate.defaultLocale = if (tag == "SYSTEM") LocaleDelegate.systemLocale else Locale.forLanguageTag(tag)
                                    showLanguageDialog = false
                                    recreate()
                                }
                            )
                        }
                    }
                }

                if (showNightDialog) {
                    val choices = remember {
                        nightValues.mapIndexed { index, _ ->
                            ChoiceOption(
                                title = nightLabels[index],
                                icon = when (nightValues[index]) {
                                    AppCompatDelegate.MODE_NIGHT_NO -> R.drawable.ic_outline_light_mode_24
                                    AppCompatDelegate.MODE_NIGHT_YES -> R.drawable.ic_outline_dark_mode_24
                                    else -> R.drawable.ic_settings_outline_24dp
                                }
                            )
                        }
                    }
                    val selectedIndex = nightValues.indexOf(nightMode)
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            WearChoiceDialog(
                                title = stringResource(R.string.dark_theme),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showNightDialog = false },
                                onSelect = { index ->
                                    val value = nightValues[index]
                                    prefs.edit().putInt(NIGHT_MODE, value).apply()
                                    nightMode = value
                                    AppCompatDelegate.setDefaultNightMode(value)
                                    showNightDialog = false
                                    recreate()
                                }
                            )
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvChoiceDialog(
                                title = stringResource(R.string.dark_theme),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showNightDialog = false },
                                onSelect = { index ->
                                    val value = nightValues[index]
                                    prefs.edit().putInt(NIGHT_MODE, value).apply()
                                    nightMode = value
                                    AppCompatDelegate.setDefaultNightMode(value)
                                    showNightDialog = false
                                    recreate()
                                }
                            )
                        }
                    } else {
                        ShizukuExpressiveTheme {
                            ChoiceDialog(
                                title = stringResource(R.string.dark_theme),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showNightDialog = false },
                                onSelect = { index ->
                                    val value = nightValues[index]
                                    prefs.edit().putInt(NIGHT_MODE, value).apply()
                                    nightMode = value
                                    AppCompatDelegate.setDefaultNightMode(value)
                                    showNightDialog = false
                                    recreate()
                                }
                            )
                        }
                    }
                }

                if (showModuleModeDialog) {
                    val moduleModes = remember {
                        listOf(
                            ModuleSettings.AccessMode.SAFE,
                            ModuleSettings.AccessMode.CUSTOM,
                            ModuleSettings.AccessMode.FULL
                        )
                    }
                    val choices = remember {
                        moduleModes.map {
                            ChoiceOption(title = getString(it.labelRes), summary = getString(it.summaryRes), icon = R.drawable.ic_adb_24dp)
                        }
                    }
                    val selectedIndex = moduleModes.indexOf(moduleAccessMode)
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            WearChoiceDialog(
                                title = stringResource(R.string.modules_access_mode),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showModuleModeDialog = false },
                                onSelect = { index ->
                                    val mode = moduleModes[index]
                                    ModuleSettings.setAccessMode(mode)
                                    moduleAccessMode = mode
                                    showModuleModeDialog = false
                                }
                            )
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvChoiceDialog(
                                title = stringResource(R.string.modules_access_mode),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showModuleModeDialog = false },
                                onSelect = { index ->
                                    val mode = moduleModes[index]
                                    ModuleSettings.setAccessMode(mode)
                                    moduleAccessMode = mode
                                    showModuleModeDialog = false
                                }
                            )
                        }
                    } else {
                        ShizukuExpressiveTheme {
                            ChoiceDialog(
                                title = stringResource(R.string.modules_access_mode),
                                choices = choices,
                                selectedIndex = selectedIndex,
                                onDismiss = { showModuleModeDialog = false },
                                onSelect = { index ->
                                    val mode = moduleModes[index]
                                    ModuleSettings.setAccessMode(mode)
                                    moduleAccessMode = mode
                                    showModuleModeDialog = false
                                }
                            )
                        }
                    }
                }

                if (showDeliveryModeDialog) {
                    val deliveryChoices = listOf(
                        ChoiceOption(
                            title = stringResource(R.string.settings_security_delivery_all_declared),
                            summary = stringResource(R.string.settings_security_delivery_all_declared_summary),
                            icon = R.drawable.ic_settings_outline_24dp
                        ),
                        ChoiceOption(
                            title = stringResource(R.string.settings_security_delivery_authorized_only),
                            summary = stringResource(R.string.settings_security_delivery_authorized_only_summary),
                            icon = R.drawable.ic_shield_24
                        )
                    )
                    val selectedDelivery = if (
                        securityPolicy.binderDeliveryMode == SecurityPolicyState.BINDER_DELIVERY_AUTHORIZED_ONLY
                    ) 1 else 0
                    ShizukuExpressiveTheme {
                        ChoiceDialog(
                            title = stringResource(R.string.settings_security_delivery_mode_title),
                            choices = deliveryChoices,
                            selectedIndex = selectedDelivery,
                            onDismiss = { showDeliveryModeDialog = false },
                            onSelect = { index ->
                                val mode = if (index == 1) {
                                    SecurityPolicyState.BINDER_DELIVERY_AUTHORIZED_ONLY
                                } else {
                                    SecurityPolicyState.BINDER_DELIVERY_ALL_DECLARED
                                }
                                updateSecurityPolicy { it.copy(binderDeliveryMode = mode) }
                                showDeliveryModeDialog = false
                            }
                        )
                    }
                }

                if (showBootMethodDialog) {
                    val bootMethodChoices = listOf(
                        ChoiceOption(
                            title = stringResource(R.string.settings_boot_method_wireless),
                            summary = stringResource(R.string.settings_boot_method_wireless_desc),
                            icon = R.drawable.ic_wadb_24,
                            enabled = true
                        ),
                        ChoiceOption(
                            title = stringResource(R.string.settings_boot_method_root),
                            summary = if (isRooted) {
                                stringResource(R.string.settings_boot_method_root_desc)
                            } else {
                                stringResource(R.string.settings_boot_method_root_unavailable)
                            },
                            icon = R.drawable.ic_root_24dp,
                            enabled = isRooted
                        )
                    )
                    val selectedMethodIndex = if (bootLaunchMethod == LaunchMethod.ROOT && isRooted) 1 else 0
                    ShizukuExpressiveTheme {
                        ChoiceDialog(
                            title = stringResource(R.string.settings_boot_method_title),
                            choices = bootMethodChoices,
                            selectedIndex = selectedMethodIndex,
                            onDismiss = { showBootMethodDialog = false },
                            onSelect = { index ->
                                val chosen = if (index == 1 && isRooted) LaunchMethod.ROOT else LaunchMethod.ADB
                                ShizukuSettings.setLastLaunchMode(chosen)
                                bootLaunchMethod = chosen
                                showBootMethodDialog = false
                            }
                        )
                    }
                }

                if (showBootTimeoutDialog) {
                    val timeoutValues = listOf(15, 25, 45, 60)
                    val timeoutChoices = timeoutValues.map { sec ->
                        ChoiceOption(title = stringResource(R.string.settings_boot_timeout_summary, sec))
                    }
                    val selectedTimeout = timeoutValues.indexOf(bootDiscoveryTimeoutSec).coerceAtLeast(0)
                    ShizukuExpressiveTheme {
                        ChoiceDialog(
                            title = stringResource(R.string.settings_boot_timeout_title),
                            choices = timeoutChoices,
                            selectedIndex = selectedTimeout,
                            onDismiss = { showBootTimeoutDialog = false },
                            onSelect = { index ->
                                val chosen = timeoutValues[index]
                                ShizukuSettings.setBootDiscoveryTimeoutSeconds(chosen)
                                bootDiscoveryTimeoutSec = chosen
                                showBootTimeoutDialog = false
                            }
                        )
                    }
                }

                if (showBootRetriesDialog) {
                    val retryValues = listOf(2, 4, 6)
                    val retryChoices = retryValues.map { count ->
                        ChoiceOption(title = stringResource(R.string.settings_boot_retries_summary, count))
                    }
                    val selectedRetries = retryValues.indexOf(bootMaxRetries).coerceAtLeast(0)
                    ShizukuExpressiveTheme {
                        ChoiceDialog(
                            title = stringResource(R.string.settings_boot_retries_title),
                            choices = retryChoices,
                            selectedIndex = selectedRetries,
                            onDismiss = { showBootRetriesDialog = false },
                            onSelect = { index ->
                                val chosen = retryValues[index]
                                ShizukuSettings.setBootMaxRetries(chosen)
                                bootMaxRetries = chosen
                                showBootRetriesDialog = false
                            }
                        )
                    }
                }

                if (showCustomPermissionsDialog) {
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            WearCustomPermissionsDialog(
                                value = customPermissions,
                                onDismiss = { showCustomPermissionsDialog = false },
                                onSave = { value ->
                                    ModuleSettings.setCustomPermissions(value)
                                    customPermissions = value
                                    showCustomPermissionsDialog = false
                                }
                            )
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvCustomPermissionsDialog(
                                value = customPermissions,
                                onDismiss = { showCustomPermissionsDialog = false },
                                onSave = { value ->
                                    ModuleSettings.setCustomPermissions(value)
                                    customPermissions = value
                                    showCustomPermissionsDialog = false
                                }
                            )
                        }
                    } else {
                        ShizukuExpressiveTheme {
                            CustomPermissionsDialog(
                                value = customPermissions,
                                onDismiss = { showCustomPermissionsDialog = false },
                                onSave = { value ->
                                    ModuleSettings.setCustomPermissions(value)
                                    customPermissions = value
                                    showCustomPermissionsDialog = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildLocaleOptions(currentTag: String): List<LocaleOption> {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES
        val currentLocale = ShizukuSettings.getLocale()

        return localeTags.mapIndexed { index, tag ->
            if (index == 0) {
                LocaleOption(tag.toString(), getString(R.string.follow_system), null)
            } else {
                val locale = Locale.forLanguageTag(displayLocaleTags[index].toString())
                val localeName = if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(locale) else locale.getDisplayName(locale)
                val localizedLocaleName = if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(currentLocale) else locale.getDisplayName(currentLocale)
                LocaleOption(
                    tag = tag.toString(),
                    title = if (tag.toString() == currentTag) localizedLocaleName else localeName,
                    summary = if (tag.toString() == currentTag || localeName == localizedLocaleName) null else localizedLocaleName
                )
            }
        }
    }

    private data class LocaleOption(val tag: String, val title: String, val summary: String?)
}
