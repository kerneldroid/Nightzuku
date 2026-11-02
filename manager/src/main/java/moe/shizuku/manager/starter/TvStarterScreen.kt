@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.starter

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.MonospaceLog
import moe.shizuku.manager.ui.compose.ShizukuIcon

@Composable
fun TvStarterScreen(
    onNavigateUp: () -> Unit,
    output: String,
    failed: Boolean,
    startedWithRoot: Boolean
) {
    Row(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .padding(32.dp)
                .size(width = 300.dp, height = 500.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.starter),
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            TvMenuButton(
                icon = R.drawable.ic_close_24,
                label = android.R.string.cancel,
                onClick = onNavigateUp
            )
        }


        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentPadding = PaddingValues(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                TvStarterStatus(
                    failed = failed,
                    startedWithRoot = startedWithRoot
                )
            }
            item {
                MonospaceLog(
                    text = output.ifBlank { stringResource(R.string.starting_root_shell) }
                )
            }
        }
    }
}

@Composable
private fun TvStarterStatus(
    failed: Boolean,
    startedWithRoot: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ShizukuIcon(
            icon = if (startedWithRoot) R.drawable.ic_root_24dp else R.drawable.ic_adb_24dp,
            modifier = Modifier.size(40.dp),
            tint = if (failed) TvMaterialTheme.colorScheme.error else TvMaterialTheme.colorScheme.primary
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TvText(
                text = if (startedWithRoot) {
                    stringResource(R.string.home_root_title)
                } else {
                    stringResource(R.string.home_wireless_adb_title)
                },
                style = TvMaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            TvText(
                text = if (failed) {
                    stringResource(R.string.notification_service_start_failed)
                } else {
                    stringResource(R.string.notification_service_starting)
                },
                style = TvMaterialTheme.typography.bodyMedium,
                color = if (failed) TvMaterialTheme.colorScheme.error
                else TvMaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TvMenuButton(
    icon: Int,
    label: Int,
    onClick: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.5f else 0.2f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(shape = TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            TvText(text = stringResource(label), style = TvMaterialTheme.typography.labelLarge)
        }
    }
}
