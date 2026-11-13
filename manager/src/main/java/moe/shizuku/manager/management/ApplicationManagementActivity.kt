@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.management

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects
import kerneldroid.nightzuku.api.NightProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.authorization.AppControlSnapshot
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.ui.compose.AnimatedScreenContent
import moe.shizuku.manager.ui.compose.ExpressiveCard
import moe.shizuku.manager.ui.compose.ExpressiveSwitch
import moe.shizuku.manager.ui.compose.SettingsItem
import moe.shizuku.manager.ui.compose.SettingsSection
import moe.shizuku.manager.ui.compose.SettingsSectionHeader
import moe.shizuku.manager.ui.compose.SettingsSwitchItem
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.SmartLogManager
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.lifecycle.Status
import rikka.shizuku.Shizuku

private const val PERMISSION_POLL_MS = 2500L
private const val DEEP_CONTROL_HOLD_MS = 2000L
private const val DEEP_CONTROL_PROGRESS_DELAY_MS = 220L

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
                val tr = it.error
                SmartLogManager.recordManagerError("APP_MANAGEMENT_PACKAGES", "Failed to load application list from server", tr)
                finish()
                Toast.makeText(this, Objects.toString(tr, "unknown"), Toast.LENGTH_SHORT).show()
                tr.printStackTrace()
            }
        }
        val isPhone = !EnvironmentUtils.isWatch(this) && !EnvironmentUtils.isTV(this)
        if (viewModel.packages.value == null) {
            viewModel.load(buildRows = isPhone)
        }
        if (isPhone) {
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

            if (isWatch || isTv) {
                LaunchedEffect(Unit) {
                    this@ApplicationManagementActivity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            delay(PERMISSION_POLL_MS)
                            permissionTick.intValue++
                        }
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
                var searchQuery by rememberSaveable { mutableStateOf("") }
                var searchActive by rememberSaveable { mutableStateOf(false) }
                var activeControlKey by rememberSaveable { mutableStateOf<String?>(null) }
                val activeControlRow = remember(rows, activeControlKey) {
                    activeControlKey?.let { key -> rows?.firstOrNull { it.key == key } }
                }
                var lastActiveControlRow by remember { mutableStateOf<AppRow?>(null) }
                if (activeControlRow != null) {
                    lastActiveControlRow = activeControlRow
                }
                val onToggle = remember(viewModel) {
                    { row: AppRow -> viewModel.toggle(row.uid, row.packageName) }
                }

                LaunchedEffect(Unit) {
                    this@ApplicationManagementActivity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            delay(PERMISSION_POLL_MS)
                            viewModel.refreshGranted()
                        }
                    }
                }

                BackHandler(enabled = activeControlKey != null || searchQuery.isNotEmpty()) {
                    if (activeControlKey != null) {
                        activeControlKey = null
                        lifecycleScope.launch { viewModel.refreshGranted() }
                    } else if (searchQuery.isNotEmpty()) {
                        searchQuery = ""
                    }
                }

                ShizukuExpressiveTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AnimatedScreenContent(
                            targetState = activeControlKey,
                            label = "appManagementPhoneRoute",
                            depth = { if (it == null) 0 else 1 }
                        ) { routeKey ->
                            val targetRow = if (routeKey != null) {
                                rows?.firstOrNull { it.key == routeKey }
                                    ?: lastActiveControlRow?.takeIf { it.key == routeKey }
                            } else {
                                null
                            }
                            if (routeKey != null && targetRow != null) {
                                DeepAppControlScreen(
                                    row = targetRow,
                                    onBack = {
                                        activeControlKey = null
                                        lifecycleScope.launch { viewModel.refreshGranted() }
                                    },
                                    onAdbLimited = { showAdbLimitedDialog = true },
                                    onStateMutated = {
                                        lifecycleScope.launch { viewModel.refreshGranted() }
                                    }
                                )
                            } else {
                            val current = rows
                            val filteredRows = remember(current, searchQuery) {
                                val q = searchQuery.trim()
                                if (current == null) {
                                    null
                                } else if (q.isEmpty()) {
                                    current
                                } else {
                                    current.filter {
                                        it.title.contains(q, ignoreCase = true) ||
                                            it.packageName.contains(q, ignoreCase = true)
                                    }
                                }
                            }
                            val nativeRows = remember(filteredRows) {
                                filteredRows?.filter { it.isNativeWire }.orEmpty()
                            }
                            val legacyRows = remember(filteredRows) {
                                filteredRows?.filter { !it.isNativeWire }.orEmpty()
                            }

                            ShizukuLazyScaffold(
                                title = stringResource(R.string.home_app_management_title),
                                onNavigateUp = { finish() },
                                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                                actions = {
                                    if (!current.isNullOrEmpty()) {
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
                                when {
                                    current == null -> {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 32.dp),
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
                                        item {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                DockedSearchBar(
                                                    inputField = {
                                                        SearchBarDefaults.InputField(
                                                            query = searchQuery,
                                                            onQueryChange = { searchQuery = it },
                                                            onSearch = { searchActive = false },
                                                            expanded = false,
                                                            onExpandedChange = { },
                                                            placeholder = {
                                                                Text(stringResource(R.string.app_management_search_hint))
                                                            },
                                                            leadingIcon = {
                                                                Icon(
                                                                    imageVector = Icons.Rounded.Search,
                                                                    contentDescription = null,
                                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            },
                                                            trailingIcon = {
                                                                if (searchQuery.isNotEmpty()) {
                                                                    IconButton(onClick = { searchQuery = "" }) {
                                                                        Icon(
                                                                            imageVector = Icons.Rounded.Close,
                                                                            contentDescription = stringResource(R.string.app_management_search_clear)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    },
                                                    expanded = false,
                                                    onExpandedChange = { },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = MaterialTheme.shapes.extraLarge
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.app_management_search_hint),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(16.dp)
                                                    )
                                                }
                                                Text(
                                                    text = stringResource(R.string.app_management_hold_hint),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp)
                                                )
                                            }
                                        }

                                        if (nativeRows.isEmpty() && legacyRows.isEmpty()) {
                                            item {
                                                ExpressiveCard(
                                                    icon = R.drawable.ic_system_icon,
                                                    title = stringResource(R.string.home_app_management_title),
                                                    body = stringResource(R.string.app_management_search_empty)
                                                )
                                            }
                                        }

                                        if (nativeRows.isNotEmpty()) {
                                            item {
                                                Box(
                                                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                                                ) {
                                                    SettingsSectionHeader(
                                                        title = "${stringResource(R.string.app_management_section_native)} (${nativeRows.size})"
                                                    )
                                                }
                                            }
                                            itemsIndexed(nativeRows, key = { _, row -> "native:${row.key}" }) { index, row ->
                                                AppPermissionRow(
                                                    index = index,
                                                    count = nativeRows.size,
                                                    row = row,
                                                    onToggle = onToggle,
                                                    onDeepHold = { activeControlKey = row.key }
                                                )
                                            }
                                        }

                                        if (legacyRows.isNotEmpty()) {
                                            item {
                                                Box(
                                                    modifier = Modifier.padding(
                                                        top = if (nativeRows.isNotEmpty()) 14.dp else 4.dp,
                                                        bottom = 6.dp
                                                    )
                                                ) {
                                                    SettingsSectionHeader(
                                                        title = "${stringResource(R.string.app_management_section_legacy)} (${legacyRows.size})"
                                                    )
                                                }
                                            }
                                            itemsIndexed(legacyRows, key = { _, row -> "legacy:${row.key}" }) { index, row ->
                                                AppPermissionRow(
                                                    index = index,
                                                    count = legacyRows.size,
                                                    row = row,
                                                    onToggle = onToggle,
                                                    onDeepHold = { activeControlKey = row.key }
                                                )
                                            }
                                        }
                                    }
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
        val failures = ArrayList<String>(4)
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
            } catch (t: SecurityException) {
                failures += "${packageName}: ${t.message ?: "SecurityException"}"
            } catch (t: Throwable) {
                failures += "${packageName}: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        if (failures.isNotEmpty()) {
            Toast.makeText(
                this,
                "Failed for ${failures.size} app(s): ${failures.first()}",
                Toast.LENGTH_LONG
            ).show()
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
    onToggle: (AppRow) -> Unit,
    onDeepHold: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val currentOnDeepHold by rememberUpdatedState(onDeepHold)
    var holdProgress by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(row.key) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPosition = down.position
                        val touchSlop = viewConfiguration.touchSlop
                        var triggered = false
                        val holdJob = launch {
                            delay(DEEP_CONTROL_PROGRESS_DELAY_MS)
                            val startMs = System.currentTimeMillis()
                            val remainingDuration = DEEP_CONTROL_HOLD_MS - DEEP_CONTROL_PROGRESS_DELAY_MS
                            while (true) {
                                val elapsed = System.currentTimeMillis() - startMs
                                holdProgress = (elapsed.toFloat() / remainingDuration.toFloat()).coerceIn(0f, 1f)
                                if (elapsed >= remainingDuration) {
                                    triggered = true
                                    holdProgress = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnDeepHold()
                                    break
                                }
                                delay(16L)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                    if (triggered) {
                                        change.consume()
                                    }
                                    break
                                }
                                if ((change.position - startPosition).getDistance() > touchSlop) {
                                    break
                                }
                                if (triggered) {
                                    change.consume()
                                }
                            }
                        } finally {
                            holdJob.cancel()
                            holdProgress = 0f
                        }
                    }
                }
            }
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (row.isOneTime && row.granted) {
                            AssistChip(
                                onClick = {},
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                ),
                                label = { Text(stringResource(R.string.app_control_session_only)) }
                            )
                        }
                        if (row.timerRemainingMs > 0L && row.granted) {
                            AssistChip(
                                onClick = {},
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Timer,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                label = { Text(formatDurationShort(row.timerRemainingMs)) }
                            )
                        }
                        if (row.requiresRoot) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(stringResource(R.string.app_management_item_summary_requires_root))
                                }
                            )
                        }
                    }
                    if (holdProgress > 0f) {
                        LinearProgressIndicator(
                            progress = { holdProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
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
}

@Composable
private fun DeepAppControlScreen(
    row: AppRow,
    onBack: () -> Unit,
    onAdbLimited: () -> Unit,
    onStateMutated: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    val userId = remember(row.uid) { UserHandleCompat.getUserId(row.uid) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var snapshot by remember {
        mutableStateOf(
            AppControlSnapshot(
                uid = row.uid,
                granted = row.granted,
                isOneTime = row.isOneTime,
                timerRemainingMs = row.timerRemainingMs,
                capabilities = NightProtocol.DEFAULT_CAPABILITIES,
                lastConnectedAt = 0L,
                activePids = emptyList(),
                signerSha256 = ""
            )
        )
    }

    val packageInfo = remember(row.packageName, userId) {
        runCatching {
            ShizukuSystemApis.getPackageInfo(
                row.packageName,
                (PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA).toLong(),
                userId
            ) ?: pm.getPackageInfo(
                row.packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
            )
        }.getOrNull()
    }

    val runtimePermissions = remember(packageInfo) {
        packageInfo?.requestedPermissions.orEmpty().mapNotNull { permission ->
            val info = runCatching { pm.getPermissionInfo(permission, 0) }.getOrNull()
                ?: return@mapNotNull null
            val base = info.protectionLevel and 0xf
            if (base != PermissionInfo.PROTECTION_DANGEROUS) return@mapNotNull null
            permission to info.loadLabel(pm).toString()
        }
    }

    var runtimePermissionStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(row.uid, row.packageName, refreshTick) {
        while (true) {
            val freshSnapshot = withContext(Dispatchers.IO) {
                AuthorizationManager.getAppControlInfo(row.packageName, row.uid)
            }
            snapshot = freshSnapshot
            val permStates = withContext(Dispatchers.IO) {
                runtimePermissions.associate { (perm, _) ->
                    val granted = runCatching {
                        ShizukuSystemApis.checkPermission(perm, row.packageName, userId) ==
                            PackageManager.PERMISSION_GRANTED
                    }.getOrDefault(false)
                    perm to granted
                }
            }
            runtimePermissionStates = permStates
            delay(1000L)
        }
    }

    fun runPrivilegedAction(block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                refreshTick++
                onStateMutated()
            } catch (_: SecurityException) {
                val serverUid = withContext(Dispatchers.IO) {
                    runCatching { Shizuku.getUid() }.getOrNull()
                }
                if (serverUid != null && serverUid != 0) {
                    onAdbLimited()
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun toggleCapability(capBit: Int, enabled: Boolean) {
        val currentCaps = snapshot.capabilities
        val updated = if (enabled) {
            currentCaps or capBit
        } else {
            currentCaps and capBit.inv()
        }
        runPrivilegedAction {
            AuthorizationManager.setCapabilities(row.uid, updated)
        }
    }

    ShizukuLazyScaffold(
        title = row.title,
        onNavigateUp = onBack,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Expressive App Identity Header
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraExtraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            row.icon?.let { icon ->
                                Image(
                                    bitmap = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = row.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = row.packageName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text("UID ${row.uid}") }
                        )
                        packageInfo?.versionName?.takeIf { it.isNotBlank() }?.let { ver ->
                            AssistChip(
                                onClick = {},
                                label = { Text("v$ver") }
                            )
                        }
                    }
                }
            }
        }

        // 2. Session-Only Access & Auto-Revoke Timer
        item {
            SettingsSection(title = stringResource(R.string.app_control_session_section)) {
                SettingsSwitchItem(
                    index = 0,
                    count = 2,
                    icon = R.drawable.ic_server_restart,
                    title = stringResource(R.string.app_control_session_only),
                    summary = stringResource(R.string.app_control_session_only_summary),
                    checked = snapshot.isOneTime,
                    onCheckedChange = { enabled ->
                        runPrivilegedAction {
                            AuthorizationManager.setOneTime(row.uid, enabled)
                        }
                    }
                )

                val timerShape = ListItemDefaults.segmentedShapes(index = 1, count = 2)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = timerShape.shape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.app_control_timer_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (snapshot.timerRemainingMs > 0L) {
                                        stringResource(
                                            R.string.app_control_timer_active,
                                            formatDurationShort(snapshot.timerRemainingMs)
                                        )
                                    } else {
                                        stringResource(R.string.app_control_timer_off)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (snapshot.timerRemainingMs > 0L) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        val presets = listOf(
                            5 * 60 * 1000L to R.string.app_control_timer_5m,
                            15 * 60 * 1000L to R.string.app_control_timer_15m,
                            30 * 60 * 1000L to R.string.app_control_timer_30m,
                            60 * 60 * 1000L to R.string.app_control_timer_1h
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            presets.forEach { (durationMs, labelRes) ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        runPrivilegedAction {
                                            AuthorizationManager.setAccessTimer(row.uid, durationMs)
                                        }
                                    },
                                    label = { Text(stringResource(labelRes)) }
                                )
                            }
                            if (snapshot.timerRemainingMs > 0L) {
                                AssistChip(
                                    onClick = {
                                        runPrivilegedAction {
                                            AuthorizationManager.setAccessTimer(row.uid, 0L)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.app_control_timer_cancel)) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Fine-Grained Nightzuku Capabilities
        item {
            val caps = snapshot.capabilities
            SettingsSection(title = stringResource(R.string.app_control_capabilities_section)) {
                SettingsSwitchItem(
                    index = 0,
                    count = 4,
                    icon = R.drawable.ic_terminal_24,
                    title = stringResource(R.string.app_control_cap_shell),
                    summary = stringResource(R.string.app_control_cap_shell_summary),
                    checked = (caps and NightProtocol.CAP_SHELL_EXEC) != 0,
                    onCheckedChange = { toggleCapability(NightProtocol.CAP_SHELL_EXEC, it) }
                )
                SettingsSwitchItem(
                    index = 1,
                    count = 4,
                    icon = R.drawable.ic_code_24dp,
                    title = stringResource(R.string.app_control_cap_binder),
                    summary = stringResource(R.string.app_control_cap_binder_summary),
                    checked = (caps and NightProtocol.CAP_BINDER_PROXY) != 0,
                    onCheckedChange = { toggleCapability(NightProtocol.CAP_BINDER_PROXY, it) }
                )
                SettingsSwitchItem(
                    index = 2,
                    count = 4,
                    icon = R.drawable.ic_adb_24dp,
                    title = stringResource(R.string.app_control_cap_userservice),
                    summary = stringResource(R.string.app_control_cap_userservice_summary),
                    checked = (caps and NightProtocol.CAP_USER_SERVICE) != 0,
                    onCheckedChange = { toggleCapability(NightProtocol.CAP_USER_SERVICE, it) }
                )
                SettingsSwitchItem(
                    index = 3,
                    count = 4,
                    icon = R.drawable.ic_settings_outline_24dp,
                    title = stringResource(R.string.app_control_cap_sysprops),
                    summary = stringResource(R.string.app_control_cap_sysprops_summary),
                    checked = (caps and NightProtocol.CAP_SYSTEM_PROPS) != 0,
                    onCheckedChange = { toggleCapability(NightProtocol.CAP_SYSTEM_PROPS, it) }
                )
            }
        }

        // 4. Runtime Permissions Manager
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsSectionHeader(title = stringResource(R.string.app_control_runtime_section))
                    if (runtimePermissions.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalIconButton(
                                onClick = {
                                    runPrivilegedAction {
                                        runtimePermissions.forEach { (perm, _) ->
                                            ShizukuSystemApis.grantRuntimePermission(row.packageName, perm, userId)
                                        }
                                    }
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.app_control_runtime_grant_all),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            OutlinedIconButton(
                                onClick = {
                                    runPrivilegedAction {
                                        runtimePermissions.forEach { (perm, _) ->
                                            ShizukuSystemApis.revokeRuntimePermission(row.packageName, perm, userId)
                                        }
                                    }
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Block,
                                    contentDescription = stringResource(R.string.app_control_runtime_revoke_all),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                if (runtimePermissions.isEmpty()) {
                    SettingsItem(
                        index = 0,
                        count = 1,
                        title = stringResource(R.string.app_control_runtime_empty),
                        showChevron = false,
                        onClick = null
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        runtimePermissions.forEachIndexed { idx, (perm, label) ->
                            SettingsSwitchItem(
                                index = idx,
                                count = runtimePermissions.size,
                                title = label,
                                summary = perm,
                                checked = runtimePermissionStates[perm] == true,
                                onCheckedChange = { grant ->
                                    runPrivilegedAction {
                                        if (grant) {
                                            ShizukuSystemApis.grantRuntimePermission(row.packageName, perm, userId)
                                        } else {
                                            ShizukuSystemApis.revokeRuntimePermission(row.packageName, perm, userId)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 5. Process & Security Diagnostics
        item {
            val hasSigner = snapshot.signerSha256.isNotBlank()
            val diagCount = if (hasSigner) 4 else 3
            val lastConnectedText = if (snapshot.lastConnectedAt > 0L) {
                remember(snapshot.lastConnectedAt) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(snapshot.lastConnectedAt))
                }
            } else {
                stringResource(R.string.app_control_last_connected_never)
            }
            val activePidsText = if (snapshot.activePids.isNotEmpty()) {
                snapshot.activePids.joinToString(", ")
            } else {
                stringResource(R.string.app_control_active_pids_none)
            }

            SettingsSection(title = stringResource(R.string.app_control_process_section)) {
                SettingsItem(
                    index = 0,
                    count = diagCount,
                    icon = R.drawable.ic_close_24,
                    title = stringResource(R.string.app_control_force_stop),
                    summary = stringResource(R.string.app_control_force_stop_summary, row.uid),
                    showChevron = false,
                    onClick = {
                        runPrivilegedAction {
                            AuthorizationManager.forceStopApp(row.packageName, row.uid)
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.app_control_force_stopped_toast, row.title),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    trailing = {
                        Button(
                            onClick = {
                                runPrivilegedAction {
                                    AuthorizationManager.forceStopApp(row.packageName, row.uid)
                                }
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.app_control_force_stopped_toast, row.title),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.StopCircle, null, modifier = Modifier.size(16.dp))
                        }
                    }
                )
                SettingsItem(
                    index = 1,
                    count = diagCount,
                    icon = R.drawable.ic_terminal_24,
                    title = stringResource(R.string.app_control_active_pids),
                    summary = activePidsText,
                    showChevron = false,
                    onClick = null
                )
                SettingsItem(
                    index = 2,
                    count = diagCount,
                    icon = R.drawable.ic_outline_info_24,
                    title = stringResource(R.string.app_control_last_connected),
                    summary = lastConnectedText,
                    showChevron = false,
                    onClick = null
                )
                if (hasSigner) {
                    SettingsItem(
                        index = 3,
                        count = diagCount,
                        icon = R.drawable.ic_shield_24,
                        title = stringResource(R.string.app_control_signer_sha256),
                        summary = snapshot.signerSha256,
                        showChevron = false,
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("SHA-256", snapshot.signerSha256))
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_copied_to_clipboard, snapshot.signerSha256),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        trailing = {
                            IconButton(
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("SHA-256", snapshot.signerSha256))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_copied_to_clipboard, snapshot.signerSha256),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) {
                                Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun formatDurationShort(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
