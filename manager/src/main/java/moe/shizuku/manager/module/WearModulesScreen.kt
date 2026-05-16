package moe.shizuku.manager.module

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle

@Composable
fun WearModulesScreen(
    modules: List<AdbModule>,
    busyId: String?,
    onToggle: (AdbModule) -> Unit,
    onRunAction: (AdbModule) -> Unit,
    onRunService: (AdbModule) -> Unit,
    onDelete: (AdbModule) -> Unit,
    onInstallZip: () -> Unit
) {
    WearScreenScaffold { state ->
        TransformingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                WearScreenTitle(icon = Icons.Rounded.Extension, title = stringResource(R.string.modules_title))
            }
            
            item {
                Button(
                    onClick = onInstallZip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(R.string.modules_install_zip))
                    }
                }
            }

            if (modules.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.modules_empty_title),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            items(modules, key = { it.id }) { module ->
                Card(
                    onClick = { onToggle(module) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = module.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (module.enabled) stringResource(R.string.modules_enabled) else "Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (module.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
