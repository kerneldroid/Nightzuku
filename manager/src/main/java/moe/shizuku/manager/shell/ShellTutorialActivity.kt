package moe.shizuku.manager.shell

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.ExpressiveCard
import moe.shizuku.manager.ui.compose.HtmlText
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.StepRow
import moe.shizuku.manager.utils.CustomTabsHelper

class ShellTutorialActivity : AppActivity() {

    companion object {

        private const val SH_NAME = "rish"
        private const val DEX_NAME = "rish_shizuku.dex"
    }

    private val openDocumentsTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@registerForActivityResult

            val cr = contentResolver
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val child =
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

            cr.query(
                child,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    if (name == SH_NAME || name == DEX_NAME) {
                        DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }

            fun writeToDocument(name: String) {
                DocumentsContract.createDocument(contentResolver, doc, "application/octet-stream", name)?.runCatching {
                    cr.openOutputStream(this)?.let { assets.open(name).copyTo(it) }
                }
            }

            writeToDocument(SH_NAME)
            writeToDocument(DEX_NAME)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val shName = SH_NAME
            val dexName = DEX_NAME

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@ShellTutorialActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                        androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                moe.shizuku.manager.ui.compose.WearScreenTitle(
                                    icon = Icons.Rounded.Terminal,
                                    title = stringResource(R.string.home_terminal_title)
                                )
                            }
                            item {
                                androidx.wear.compose.material3.Card(
                                    onClick = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.wear.compose.material3.Text(
                                        text = HtmlText(R.string.rish_description, shName),
                                        style = androidx.wear.compose.material3.MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            item {
                                androidx.wear.compose.material3.Card(
                                    onClick = { openDocumentsTree.launch(null) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.compose.foundation.layout.Column {
                                        androidx.wear.compose.material3.Text(
                                            text = "1. " + HtmlText(R.string.terminal_tutorial_1, shName, dexName),
                                            style = androidx.wear.compose.material3.MaterialTheme.typography.labelMedium
                                        )
                                        androidx.wear.compose.material3.Text(
                                            text = stringResource(R.string.terminal_export_files),
                                            style = androidx.wear.compose.material3.MaterialTheme.typography.bodySmall,
                                            color = androidx.wear.compose.material3.MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            item {
                                androidx.wear.compose.material3.Card(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.wear.compose.material3.Text(
                                        text = "2. " + HtmlText(R.string.terminal_tutorial_2, shName),
                                        style = androidx.wear.compose.material3.MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            item {
                                androidx.wear.compose.material3.Card(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.wear.compose.material3.Text(
                                        text = "3. " + HtmlText(R.string.terminal_tutorial_3, "sh $shName"),
                                        style = androidx.wear.compose.material3.MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                ShizukuExpressiveTheme {
                    ShizukuLazyScaffold(
                        title = stringResource(R.string.home_terminal_title),
                        onNavigateUp = { finish() }
                    ) {
                        item {
                            ExpressiveCard(
                                icon = R.drawable.ic_help_outline_24dp,
                                title = stringResource(R.string.home_terminal_title),
                                body = HtmlText(R.string.rish_description, shName),
                                onClick = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) }
                            ) {
                                FilledTonalButton(
                                    onClick = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) }
                                ) {
                                    Text(stringResource(R.string.home_adb_button_view_help))
                                }
                            }
                        }
                        item {
                            StepRow(
                                number = 1,
                                title = HtmlText(R.string.terminal_tutorial_1, shName, dexName),
                                body = stringResource(R.string.terminal_tutorial_1_description),
                                action = {
                                    Button(onClick = { openDocumentsTree.launch(null) }) {
                                        Text(stringResource(R.string.terminal_export_files))
                                    }
                                }
                            )
                        }
                        item {
                            StepRow(
                                number = 2,
                                title = HtmlText(R.string.terminal_tutorial_2, shName),
                                body = HtmlText(
                                    R.string.terminal_tutorial_2_description,
                                    "Termux",
                                    "PKG",
                                    "com.termux",
                                    "com.termux"
                                )
                            )
                        }
                        item {
                            StepRow(
                                number = 3,
                                title = HtmlText(R.string.terminal_tutorial_3, "sh $shName"),
                                body = HtmlText(R.string.terminal_tutorial_3_description, shName, "PATH")
                            )
                        }
                    }
                }
            }
        }
    }
}
