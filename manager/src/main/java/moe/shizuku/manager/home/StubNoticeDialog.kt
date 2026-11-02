package moe.shizuku.manager.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.shizuku.manager.R

@Composable
fun StubNoticeDialog(onDismiss: () -> Unit) {
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
                modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.stub_notice_confirm))
            }
        }
    )
}
