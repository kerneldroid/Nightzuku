@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.management

import android.content.pm.PackageInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.ui.compose.ExpressiveCard
import moe.shizuku.manager.ui.compose.ExpressiveSwitch
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.lifecycle.Status
import rikka.shizuku.Shizuku
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Objects

private const val PERMISSION_POLL_MS = 2500L

class ApplicationManagementActivity : AppActivity() {

    private val viewModel by appsViewModel()
    private val permissionTick = mutableIntStateOf(0)

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder()) {
            finish()
            return
        }

        viewModel.packages.observe(this) {
            if (it.status == Status.ERROR) {
                finish()
                val tr = it.error
                Toast.makeText(this, Objects.toString(tr, "unknown"), Toast.LENGTH_SHORT).show()
                tr.printStackTrace()
            }
        }
        val isPhone = !EnvironmentUtils.isWatch(this) && !EnvironmentUtils.isTV(this)
        if (viewModel.packages.value == null) {
            viewModel.load(buildRows = isPhone)
        }
        if (isPhone) {
            // Keeps the switches in sync with the server without touching the main thread.
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        viewModel.refreshGranted()
                        delay(PERMISSION_POLL_MS)
                    }
                }
            }
        }

        Shizuku.addBinderDeadListener(binderDeadListener)

        setContent {
            val packagesResource by viewModel.packages.observeAsState()
            val packages = packagesResource?.data.orEmpty()
            val tick = permissionTick.intValue
            var showAdbLimitedDialog by remember { mutableStateOf(false) }

            val isWatch = EnvironmentUtils.isWatch(this@ApplicationManagementActivity)
            val isTv = EnvironmentUtils.isTV(this@ApplicationManagementActivity)

            LaunchedEffect(Unit) {
                viewModel.adbLimited.collect { showAdbLimitedDialog = true }
            }

            // TV and Wear still derive the switches from `tick`; phones read AppsViewModel.rows.
            if (isWatch || isTv) {
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(PERMISSION_POLL_MS)
                        permissionTick.intValue++
                    }
                }
            }
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    val pm = LocalContext.current.packageManager
                    val apps = remember(packages, tick) {
                        packages.mapNotNull { pkg ->
                            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                            WearAppItem(
                                label = appInfo.loadLabel(pm).toString(),
                                packageName = pkg.packageName,
                                uid = appInfo.uid,
                                granted = AuthorizationManager.granted(pkg.packageName, appInfo.uid)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        WearApplicationManagementScreen(
                            apps = apps,
                            onToggle = { app ->
                                try {
                                    if (app.granted) {
                                        AuthorizationManager.revoke(app.packageName, app.uid)
                                    } else {
                                        AuthorizationManager.grant(app.packageName, app.uid)
                                    }
                                    permissionTick.intValue++
                                    viewModel.load(onlyCount = true)
                                } catch (_: SecurityException) {
                                    showAdbLimitedDialog = true
                                }
                            }
                        )

                        if (showAdbLimitedDialog) {
                            moe.shizuku.manager.home.HomeAdbLimitedDialog(
                                onDismiss = { showAdbLimitedDialog = false }
                            )
                        }
                    }
                }
            } else if (isTv) {
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TvApplicationManagementScreen(
                            packages = packages,
                            tick = tick,
                            onNavigateUp = { finish() },
                            onToggle = { pkg ->
                                val applicationInfo = pkg.applicationInfo ?: return@TvApplicationManagementScreen
                                try {
                                    if (AuthorizationManager.granted(pkg.packageName, applicationInfo.uid)) {
                                        AuthorizationManager.revoke(pkg.packageName, applicationInfo.uid)
                                    } else {
                                        AuthorizationManager.grant(pkg.packageName, applicationInfo.uid)
                                    }
                                    permissionTick.intValue++
                                    viewModel.load(onlyCount = true)
                                } catch (_: SecurityException) {
                                    showAdbLimitedDialog = true
                                }
                            },
                            onSelectAll = { selectAll(packages, it) }
                        )

                        if (showAdbLimitedDialog) {
                            moe.shizuku.manager.home.HomeAdbLimitedDialog(
                                onDismiss = { showAdbLimitedDialog = false }
                            )
                        }
                    }
                }
            } else {
                val rows by viewModel.rows.collectAsStateWithLifecycle()
                val onToggle = remember(viewModel) {
                    { row: AppRow -> viewModel.toggle(row.uid, row.packageName) }
                }
                ShizukuExpressiveTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ShizukuLazyScaffold(
                            title = stringResource(R.string.home_app_management_title),
                            onNavigateUp = { finish() },
                            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                            actions = {
                                if (!rows.isNullOrEmpty()) {
                                    var menuExpanded by remember { mutableStateOf(false) }

                                    Box {
                                        IconButton(onClick = { menuExpanded = true }) {
                                            ShizukuIcon(R.drawable.ic_more_vert_24)
                                        }
                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.app_management_select_all)) },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.setAll(true)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.app_management_deselect_all)) },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.setAll(false)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        ) {
                            val current = rows
                            when {
                                current == null -> {
                                    item {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            LoadingIndicator(Modifier.size(36.dp))
                                        }
                                    }
                                }
                                current.isEmpty() -> {
                                    item {
                                        ExpressiveCard(
                                            icon = R.drawable.ic_system_icon,
                                            title = stringResource(R.string.home_app_management_title),
                                            body = stringResource(R.string.home_app_management_empty)
                                        )
                                    }
                                }
                                else -> {
                                    itemsIndexed(current, key = { _, row -> row.key }) { index, row ->
                                        AppPermissionRow(
                                            index = index,
                                            count = current.size,
                                            row = row,
                                            onToggle = onToggle
                                        )
                                    }
                                }
                            }
                        }

                        if (showAdbLimitedDialog) {
                            moe.shizuku.manager.home.HomeAdbLimitedDialog(
                                onDismiss = { showAdbLimitedDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun selectAll(packages: List<PackageInfo>, granted: Boolean) {
        packages.forEach { packageInfo ->
            val applicationInfo = packageInfo.applicationInfo ?: return@forEach
            val uid = applicationInfo.uid
            val packageName = packageInfo.packageName
            try {
                if (granted) {
                    AuthorizationManager.grant(packageName, uid)
                } else {
                    AuthorizationManager.revoke(packageName, uid)
                }
            } catch (_: SecurityException) {
            }
        }
        permissionTick.intValue++
        viewModel.load(onlyCount = true)
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        permissionTick.intValue++
    }
}

@Composable
private fun AppPermissionRow(
    index: Int,
    count: Int,
    row: AppRow,
    onToggle: (AppRow) -> Unit
) {
    SegmentedListItem(
        onClick = { onToggle(row) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        leadingContent = {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                row.icon?.let { icon ->
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.requiresRoot) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(stringResource(R.string.app_management_item_summary_requires_root))
                        }
                    )
                }
            }
        },
        trailingContent = {
            ExpressiveSwitch(
                checked = row.granted,
                onCheckedChange = { onToggle(row) }
            )
        }
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
