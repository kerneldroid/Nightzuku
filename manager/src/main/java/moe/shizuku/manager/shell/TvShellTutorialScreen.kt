@file:OptIn(ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.TvActionButton
import moe.shizuku.manager.ui.compose.TvActionCard
import moe.shizuku.manager.ui.compose.TvBackButton
import moe.shizuku.manager.ui.compose.TvMenuButton
import moe.shizuku.manager.ui.compose.TvStaticCard
import moe.shizuku.manager.ui.compose.TvTwoPaneLayout
import moe.shizuku.manager.ui.compose.htmlToPlainText

@Composable
fun TvShellTutorialScreen(
    onNavigateUp: () -> Unit,
    shName: String,
    dexName: String,
    onExportFiles: () -> Unit,
    onViewHelp: () -> Unit
) {
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { initialFocusRequester.requestFocus() }
    }

    TvTwoPaneLayout(
        title = stringResource(R.string.home_terminal_title),
        sidebarContent = {
            TvBackButton(onClick = onNavigateUp)

            TvMenuButton(
                icon = R.drawable.ic_help_outline_24dp,
                label = R.string.home_adb_button_view_help,
                onClick = onViewHelp
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 32.dp, end = 32.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                TvTutorialStepCard(
                    number = 1,
                    title = htmlToPlainText(stringResource(R.string.terminal_tutorial_1, shName, dexName)),
                    body = stringResource(R.string.terminal_tutorial_1_description)
                ) {
                    TvActionButton(
                        onClick = onExportFiles,
                        modifier = Modifier.focusRequester(initialFocusRequester)
                    ) {
                        TvText(stringResource(R.string.terminal_export_files))
                    }
                }
            }
            item {
                TvTutorialStepCard(
                    number = 2,
                    title = htmlToPlainText(stringResource(R.string.terminal_tutorial_2, shName)),
                    body = htmlToPlainText(
                        stringResource(
                            R.string.terminal_tutorial_2_description,
                            "Termux",
                            "PKG",
                            "com.termux",
                            "com.termux"
                        )
                    )
                )
            }
            item {
                TvTutorialStepCard(
                    number = 3,
                    title = htmlToPlainText(stringResource(R.string.terminal_tutorial_3, "sh $shName")),
                    body = htmlToPlainText(stringResource(R.string.terminal_tutorial_3_description, shName, "PATH"))
                )
            }
        }
    }
}

@Composable
private fun TvTutorialStepCard(
    number: Int,
    title: String,
    body: String,
    content: (@Composable () -> Unit)? = null
) {
    val cardContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            TvText(
                text = number.toString(),
                style = TvMaterialTheme.typography.headlineLarge,
                color = TvMaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TvText(
                    text = body,
                    style = TvMaterialTheme.typography.bodyMedium,
                    color = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
                if (content != null) {
                    Spacer(Modifier.height(4.dp))
                    content()
                }
            }
        }
    }

    if (content == null) {
        TvActionCard(onClick = {}) {
            cardContent()
        }
    } else {
        TvStaticCard {
            cardContent()
        }
    }
}
