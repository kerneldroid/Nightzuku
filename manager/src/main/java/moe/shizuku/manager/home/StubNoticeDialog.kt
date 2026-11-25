@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.TvActionButton
import moe.shizuku.manager.ui.compose.TvDialog
import moe.shizuku.manager.ui.compose.TvShizukuTheme
import moe.shizuku.manager.utils.EnvironmentUtils

@Composable
fun StubNoticeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isTv = EnvironmentUtils.isTV(context)

    if (isTv) {
        val confirmFocusRequester = remember { FocusRequester() }
        TvShizukuTheme(wrapSurface = false) {
            TvDialog(
                onDismissRequest = onDismiss,
                title = {
                    TvText(
                        text = stringResource(R.string.stub_notice_title),
                        style = TvMaterialTheme.typography.headlineSmall
                    )
                },
                text = {
                    TvText(
                        text = stringResource(R.string.stub_notice_message),
                        style = TvMaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                initialFocusRequester = confirmFocusRequester,
                confirmButton = {
                    TvActionButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(confirmFocusRequester)
                    ) {
                        TvText(stringResource(R.string.stub_notice_confirm))
                    }
                }
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.stub_notice_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.stub_notice_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.stub_notice_confirm))
                }
            }
        )
    }
}
