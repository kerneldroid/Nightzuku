@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.shell

import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card as WearCard
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.HtmlText
import moe.shizuku.manager.ui.compose.SettingsSectionHeader
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
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

    private fun copyCommandToClipboard(command: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(SH_NAME, command))
        Toast.makeText(this, getString(R.string.toast_copied_to_clipboard, command), Toast.LENGTH_SHORT).show()
    }

    private fun openTerminalOrCopy(command: String) {
        copyCommandToClipboard(command)
        val intent = packageManager.getLaunchIntentForPackage("com.termux")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent == null || runCatching { startActivity(intent) }.isFailure) {
            Toast.makeText(this, R.string.guide_rish_terminal_missing, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val shName = SH_NAME
            val dexName = DEX_NAME

            BackHandler {
                finish()
            }

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@ShellTutorialActivity)
            val isTv = moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@ShellTutorialActivity)
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
                                WearCard(
                                    onClick = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    WearText(
                                        text = HtmlText(R.string.rish_description, shName),
                                        style = WearMaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            item {
                                WearCard(
                                    onClick = { openDocumentsTree.launch(null) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        WearText(
                                            text = "1. " + HtmlText(R.string.terminal_tutorial_1, shName, dexName),
                                            style = WearMaterialTheme.typography.labelMedium
                                        )
                                        WearText(
                                            text = stringResource(R.string.terminal_export_files),
                                            style = WearMaterialTheme.typography.bodySmall,
                                            color = WearMaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            item {
                                WearCard(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    WearText(
                                        text = "2. " + HtmlText(R.string.terminal_tutorial_2, shName),
                                        style = WearMaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            item {
                                WearCard(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    WearText(
                                        text = "3. " + HtmlText(R.string.terminal_tutorial_3, "sh $shName"),
                                        style = WearMaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (isTv) {
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    TvShellTutorialScreen(
                        onNavigateUp = { finish() },
                        shName = shName,
                        dexName = dexName,
                        onExportFiles = { openDocumentsTree.launch(null) },
                        onViewHelp = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) }
                    )
                }
            } else {
                ShizukuExpressiveTheme {
                    ShellGuideScreen(
                        shName = shName,
                        dexName = dexName,
                        onNavigateUp = { finish() },
                        onExportFiles = { openDocumentsTree.launch(null) },
                        onCopyCommand = { copyCommandToClipboard(it) },
                        onCopySetup = {
                            copyCommandToClipboard("export RISH_APPLICATION_ID=\"com.termux\"\nsh $shName")
                        },
                        onOpenTerminal = {
                            openTerminalOrCopy("export RISH_APPLICATION_ID=\"com.termux\"\nsh $shName")
                        },
                        onViewHelp = {
                            CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get())
                        }
                    )
                }
            }
        }
    }
}
