package moe.shizuku.manager.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle

@Composable
fun WearSettingsScreen(
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    nightModeSummary: String,
    onNightModeClick: () -> Unit,
    blackNightTheme: Boolean,
    onBlackNightThemeChange: (Boolean) -> Unit,
    useSystemColor: Boolean,
    onUseSystemColorChange: (Boolean) -> Unit,
    // Dialog props
    showNightDialog: Boolean,
    nightLabels: List<String>,
    nightValues: List<Int>,
    currentNightMode: Int,
    onNightModeSelect: (Int) -> Unit,
    onNightDialogDismiss: () -> Unit
) {
    WearScreenScaffold { state ->
        if (showNightDialog) {
            TransformingLazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(rikka.core.R.string.dark_theme),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                itemsIndexed(nightLabels) { index, label ->
                    RadioButton(
                        selected = nightValues[index] == currentNightMode,
                        onSelect = { onNightModeSelect(nightValues[index]) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(label) }
                    )
                }
                item {
                    Button(
                        onClick = onNightDialogDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(android.R.string.cancel), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            TransformingLazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    WearScreenTitle(icon = Icons.Rounded.Settings, title = stringResource(R.string.settings_title))
                }

                item {
                    SwitchButton(
                        checked = startOnBoot,
                        onCheckedChange = onStartOnBootChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_start_on_boot)) },
                        icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) }
                    )
                }

                item {
                    Button(
                        onClick = onNightModeClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.DarkMode, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(text = nightModeSummary)
                        }
                    }
                }

                item {
                    SwitchButton(
                        checked = blackNightTheme,
                        onCheckedChange = onBlackNightThemeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_black_night_theme)) },
                        icon = { Icon(Icons.Rounded.DarkMode, contentDescription = null) }
                    )
                }

                item {
                    SwitchButton(
                        checked = useSystemColor,
                        onCheckedChange = onUseSystemColorChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_use_system_color)) },
                        icon = { Icon(Icons.Rounded.Palette, contentDescription = null) }
                    )
                }
            }
        }
    }
}
