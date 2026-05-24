package moe.shizuku.manager.starter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.material3.Card as WearCard
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle

@Composable
fun WearStarterScreen(
    output: String,
    failed: Boolean,
    startedWithRoot: Boolean
) {
    val title = stringResource(R.string.starter)
    val bodyText = if (failed) {
        stringResource(R.string.notification_service_start_failed)
    } else {
        stringResource(R.string.notification_service_starting)
    }
    val fallbackText = stringResource(R.string.starting_root_shell)
    
    val icon = if (startedWithRoot) Icons.Rounded.PlayArrow else Icons.Rounded.Usb

    WearScreenScaffold { state ->
        TransformingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                WearScreenTitle(
                    icon = icon,
                    title = title
                )
            }
            item {
                WearCard(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                ) {
                    WearText(
                        text = bodyText,
                        style = WearMaterialTheme.typography.bodyMedium,
                        color = if (failed) WearMaterialTheme.colorScheme.error else WearMaterialTheme.colorScheme.onSurface
                    )
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = WearMaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = WearMaterialTheme.shapes.large
                        )
                ) {
                    WearText(
                        text = output.ifBlank { fallbackText },
                        modifier = Modifier.padding(12.dp),
                        style = WearMaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = WearMaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
