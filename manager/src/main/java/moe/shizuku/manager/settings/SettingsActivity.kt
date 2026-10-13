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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.ui.compose.SettingsItem
import moe.shizuku.manager.ui.compose.SettingsSection
import moe.shizuku.manager.ui.compose.SettingsSwitchItem
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.htmlToPlainText
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.shizuku.manager.ShizukuLocales
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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
            val scope = rememberCoroutineScope()
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
            var showTokenSettings by remember { mutableStateOf(false) }
            var showLabFeatures by remember { mutableStateOf(false) }
            var openCategory by remember { mutableStateOf<SettingsCategory?>(null) }

            var moduleAccessMode by remember {
                mutableStateOf(ModuleSettings.getAccessMode())
            }
            var customPermissions by remember {
                mutableStateOf(ModuleSettings.getCustomPermissions())
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
            val languageSummary = localeOptions.firstOrNull { it.tag == languageTag }?.summary
                ?: stringResource(rikka.core.R.string.follow_system)
            val nightValues = resources.getIntArray(R.array.night_mode_value).toList()
            val nightLabels = stringArrayResource(R.array.night_mode).toList()
            val nightSummary = nightLabels.getOrElse(nightValues.indexOf(nightMode)) {
                stringResource(rikka.core.R.string.follow_system)
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

            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                if (showTokenSettings) {
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            moe.shizuku.manager.module.catalog.WearTokenSettingsScreen(
                                onBack = { showTokenSettings = false }
                            )
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvTokenSettingsScreen(
                                onNavigateUp = { showTokenSettings = false }
                            )
                        }
                    }
                } else if (showLabFeatures) {
                    if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvLabMenuScreen(
                                onNavigateUp = { showLabFeatures = false }
                            )
                        }
                    } else {
                        startActivity(android.content.Intent(this@SettingsActivity, LabFeaturesActivity::class.java))
                        showLabFeatures = false
                    }
                } else if (isWatch) {
                    moe.shizuku.manager.ui.compose.WearShizukuTheme {
                        WearSettingsScreen(
                        startOnBoot = startOnBoot,
                        onStartOnBootChange = { enabled ->
                            packageManager.setComponentEnabled(componentName, enabled)
                            startOnBoot = packageManager.isComponentEnabled(componentName)
                        },
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
                        moduleAccessMode = moduleAccessMode,
                        onModuleAccessModeClick = { showModuleModeDialog = true },
                        onCustomPermissionsClick = { showCustomPermissionsDialog = true },
                        onGitHubTokenClick = { showTokenSettings = true },
                        showNightDialog = false,
                        nightLabels = nightLabels,
                        nightValues = nightValues,
                        currentNightMode = nightMode,
                        onNightModeSelect = { value ->
                            prefs.edit().putInt(NIGHT_MODE, value).apply()
                            nightMode = value
                            AppCompatDelegate.setDefaultNightMode(value)
                            showNightDialog = false
                            recreate()
                        },
                        onNightDialogDismiss = { showNightDialog = false }
                    )
                }
            } else if (isTv) {
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    TvSettingsScreen(
                        onNavigateUp = { finish() },
                        startOnBoot = startOnBoot,
                        onStartOnBootChange = { enabled ->
                            packageManager.setComponentEnabled(componentName, enabled)
                            startOnBoot = packageManager.isComponentEnabled(componentName)
                        },
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
                        moduleAccessMode = moduleAccessMode,
                        onModuleAccessModeClick = { showModuleModeDialog = true },
                        recommandWebUi = recommandWebUi,
                        onRecommandWebUiChange = { enabled ->
                            ModuleSettings.setRecommandForWebUi(enabled)
                            recommandWebUi = enabled
                        },
                        recommandAction = recommandAction,
                        onRecommandActionChange = { enabled ->
                            ModuleSettings.setRecommandForAction(enabled)
                            recommandAction = enabled
                        },
                        onLabFeaturesClick = {
                            showLabFeatures = true
                        },
                        onGitHubTokenClick = { showTokenSettings = true }
                    )
                }
            } else {
                ShizukuExpressiveTheme {
                    val activeCategory = openCategory
                    BackHandler(enabled = activeCategory != null) { openCategory = null }

                    if (activeCategory == null) {
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
                                            onClick = { openCategory = category },
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
                    } else {
                        ShizukuLazyScaffold(
                            title = stringResource(activeCategory.title),
                            onNavigateUp = { openCategory = null }
                        ) {
                            when (activeCategory) {
                                SettingsCategory.STARTUP -> item {
                                    SettingsSection(stringResource(R.string.settings_startup)) {
                                        SettingsSwitchItem(
                                            index = 0,
                                            count = 1,
                                            icon = R.drawable.ic_server_restart,
                                            title = stringResource(R.string.settings_start_on_boot),
                                            summary = stringResource(R.string.settings_start_on_boot_summary),
                                            checked = startOnBoot,
                                            onCheckedChange = { enabled ->
                                                packageManager.setComponentEnabled(componentName, enabled)
                                                startOnBoot = packageManager.isComponentEnabled(componentName)
                                            }
                                        )
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
                                                title = stringResource(rikka.core.R.string.dark_theme),
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
                                    val accessItemCount = 1 + (if (showCustomPermissions) 1 else 0)
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            SettingsSection(stringResource(R.string.modules_settings_title)) {
                                                SettingsItem(
                                                    index = 0,
                                                    count = accessItemCount,
                                                    icon = R.drawable.ic_settings_outline_24dp,
                                                    title = stringResource(R.string.modules_access_mode),
                                                    summary = stringResource(moduleAccessMode.labelRes),
                                                    onClick = { showModuleModeDialog = true }
                                                )
                                                if (showCustomPermissions) {
                                                    SettingsItem(
                                                        index = 1,
                                                        count = accessItemCount,
                                                        icon = R.drawable.ic_add_24,
                                                        title = stringResource(R.string.modules_custom_permissions),
                                                        summary = stringResource(R.string.modules_custom_permissions_summary),
                                                        onClick = { showCustomPermissionsDialog = true }
                                                    )
                                                }
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
                            title = stringResource(rikka.core.R.string.dark_theme),
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
                            title = stringResource(rikka.core.R.string.dark_theme),
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
                            title = stringResource(rikka.core.R.string.dark_theme),
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
                LocaleOption(tag.toString(), getString(rikka.core.R.string.follow_system), null)
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
