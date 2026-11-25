@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.starter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.SurfaceDefaults as TvSurfaceDefaults
import androidx.tv.material3.Text as TvText
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.TvBackButton
import moe.shizuku.manager.ui.compose.TvStaticCard
import moe.shizuku.manager.ui.compose.TvTwoPaneLayout

@Composable
fun TvStarterScreen(
    onNavigateUp: () -> Unit,
    output: String,
    failed: Boolean,
    startedWithRoot: Boolean
) {
    val backFocusRequester = remember { FocusRequester() }
    val logScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val scrollStepPx = with(LocalDensity.current) { 120.dp.toPx() }
    var logFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { backFocusRequester.requestFocus() }
    }

    LaunchedEffect(output) {
        if (!logFocused) {
            logScrollState.scrollTo(logScrollState.maxValue)
        }
    }

    TvTwoPaneLayout(
        title = stringResource(R.string.starter),
        sidebarContent = {
            TvBackButton(
                onClick = onNavigateUp,
                modifier = Modifier.focusRequester(backFocusRequester)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp, end = 32.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            TvStaticCard(danger = failed) {
                TvStarterStatus(
                    failed = failed,
                    startedWithRoot = startedWithRoot
                )
            }

            val logShape = TvMaterialTheme.shapes.large
            TvSurface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onFocusChanged { logFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (logScrollState.value < logScrollState.maxValue) {
                                        coroutineScope.launch {
                                            logScrollState.animateScrollBy(scrollStepPx)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                Key.DirectionUp -> {
                                    if (logScrollState.value > 0) {
                                        coroutineScope.launch {
                                            logScrollState.animateScrollBy(-scrollStepPx)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
                    .focusable(),
                shape = logShape,
                colors = TvSurfaceDefaults.colors(
                    containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                    contentColor = TvMaterialTheme.colorScheme.onSurface
                ),
                border = Border(
                    border = if (logFocused) {
                        BorderStroke(2.5.dp, TvMaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, TvMaterialTheme.colorScheme.borderVariant.copy(alpha = 0.45f))
                    },
                    shape = logShape
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(logScrollState)
                        .padding(20.dp)
                ) {
                    TvText(
                        text = output.ifBlank { stringResource(R.string.starting_root_shell) },
                        style = TvMaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TvMaterialTheme.colorScheme.onSurface
                    )
                }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
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
                color = if (failed) {
                    TvMaterialTheme.colorScheme.error
                } else {
                    TvMaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
