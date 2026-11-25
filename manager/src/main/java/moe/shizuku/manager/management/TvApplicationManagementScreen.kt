@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.management

import android.content.pm.PackageInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.SurfaceDefaults as TvSurfaceDefaults
import androidx.tv.material3.Text as TvText
import kerneldroid.nightzuku.util.LabelSanitizer
import moe.shizuku.manager.R
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.ui.compose.TvActionCard
import moe.shizuku.manager.ui.compose.TvBackButton
import moe.shizuku.manager.ui.compose.TvMenuButton
import moe.shizuku.manager.ui.compose.TvTwoPaneLayout
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat

@Composable
fun TvApplicationManagementScreen(
    packages: List<PackageInfo>,
    tick: Int,
    onNavigateUp: () -> Unit,
    onToggle: (PackageInfo) -> Unit,
    onSelectAll: (Boolean) -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(packages.isEmpty()) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    TvTwoPaneLayout(
        title = stringResource(R.string.home_app_management_title),
        sidebarContent = {
            TvBackButton(
                onClick = onNavigateUp,
                modifier = if (packages.isEmpty()) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )

            if (packages.isNotEmpty()) {
                TvMenuButton(
                    icon = R.drawable.ic_server_ok_24dp,
                    label = R.string.app_management_select_all,
                    onClick = { onSelectAll(true) }
                )
                TvMenuButton(
                    icon = R.drawable.ic_close_24,
                    label = R.string.app_management_deselect_all,
                    onClick = { onSelectAll(false) }
                )
            }
        }
    ) {
        if (packages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                TvText(
                    text = stringResource(R.string.home_app_management_empty),
                    style = TvMaterialTheme.typography.headlineSmall
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 32.dp, end = 32.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(
                    items = packages,
                    key = { _, pkg -> "${pkg.packageName}:${pkg.applicationInfo?.uid ?: 0}" }
                ) { index, pkg ->
                    TvAppCard(
                        packageInfo = pkg,
                        tick = tick,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        },
                        onClick = { onToggle(pkg) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TvAppCard(
    packageInfo: PackageInfo,
    tick: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val applicationInfo = packageInfo.applicationInfo ?: return
    val uid = applicationInfo.uid
    val packageName = packageInfo.packageName
    val granted = remember(packageName, uid, tick) {
        AuthorizationManager.granted(packageName, uid)
    }
    val userId = UserHandleCompat.getUserId(uid)
    val title = remember(packageName, userId) {
        val rawLabel = try {
            applicationInfo.loadLabel(pm)
        } catch (_: Exception) {
            packageName
        }
        val label = LabelSanitizer.sanitize(rawLabel, packageName)
        if (userId != UserHandleCompat.myUserId()) {
            val userInfo = ShizukuSystemApis.getUserInfo(userId)
            "$label - ${userInfo.name} ($userId)"
        } else {
            label
        }
    }
    val icon = remember(packageName) {
        applicationInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
    }

    TvActionCard(
        onClick = onClick,
        modifier = modifier,
        selected = granted,
        shape = TvMaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvSurface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = TvSurfaceDefaults.colors(containerColor = TvMaterialTheme.colorScheme.surfaceVariant)
            ) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TvText(
                    text = packageName,
                    style = TvMaterialTheme.typography.bodySmall,
                    color = TvMaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TvText(
                    text = stringResource(
                        if (granted) R.string.tv_app_authorized else R.string.tv_app_denied
                    ),
                    style = TvMaterialTheme.typography.labelSmall,
                    color = if (granted) {
                        TvMaterialTheme.colorScheme.primary
                    } else {
                        TvMaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
