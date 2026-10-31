@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.shell

import android.content.ClipData
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
                    ShizukuLazyScaffold(
                        title = stringResource(R.string.home_terminal_title),
                        onNavigateUp = { finish() },
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.extraExtraLarge,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(44.dp),
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                ShizukuIcon(
                                                    icon = R.drawable.ic_terminal_24,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.home_terminal_title),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                text = HtmlText(R.string.rish_description, shName),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ExpressivePillBadge(text = shName)
                                        ExpressivePillBadge(text = dexName)
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { openDocumentsTree.launch(null) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp),
                                            shape = MaterialTheme.shapes.extraLargeIncreased
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.FolderOpen,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.terminal_export_files),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get())
                                            },
                                            modifier = Modifier.height(48.dp),
                                            shape = MaterialTheme.shapes.extraLargeIncreased
                                        ) {
                                            ShizukuIcon(
                                                icon = R.drawable.ic_help_outline_24dp,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.home_adb_button_view_help),
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SettingsSectionHeader(title = stringResource(R.string.home_wireless_adb_view_guide_button))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                                ) {
                                    ExpressiveTutorialStepCard(
                                        index = 0,
                                        count = 3,
                                        stepNumber = 1,
                                        badgeLabel = stringResource(R.string.terminal_step_export_title),
                                        icon = Icons.Rounded.FolderOpen,
                                        title = HtmlText(R.string.terminal_tutorial_1, shName, dexName),
                                        body = stringResource(R.string.terminal_tutorial_1_description),
                                        action = {
                                            FilledTonalButton(
                                                onClick = { openDocumentsTree.launch(null) },
                                                shape = MaterialTheme.shapes.largeIncreased
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.FolderOpen,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(R.string.terminal_export_files))
                                            }
                                        }
                                    )
                                    ExpressiveTutorialStepCard(
                                        index = 1,
                                        count = 3,
                                        stepNumber = 2,
                                        badgeLabel = stringResource(R.string.terminal_step_configure_title),
                                        icon = Icons.Rounded.Code,
                                        title = HtmlText(R.string.terminal_tutorial_2, shName),
                                        body = HtmlText(
                                            R.string.terminal_tutorial_2_description,
                                            "Termux",
                                            "PKG",
                                            "com.termux",
                                            "com.termux"
                                        ),
                                        codeSnippet = "RISH_APPLICATION_ID=\"com.termux\"",
                                        onCopyCode = { copyCommandToClipboard("RISH_APPLICATION_ID=\"com.termux\"") }
                                    )
                                    ExpressiveTutorialStepCard(
                                        index = 2,
                                        count = 3,
                                        stepNumber = 3,
                                        badgeLabel = stringResource(R.string.terminal_step_launch_title),
                                        icon = Icons.Rounded.Terminal,
                                        title = HtmlText(R.string.terminal_tutorial_3, "sh $shName"),
                                        body = HtmlText(R.string.terminal_tutorial_3_description, shName, "PATH"),
                                        codeSnippet = "sh $shName",
                                        onCopyCode = { copyCommandToClipboard("sh $shName") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressivePillBadge(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ExpressiveTutorialStepCard(
    index: Int,
    count: Int,
    stepNumber: Int,
    badgeLabel: String,
    icon: ImageVector,
    title: String,
    body: String? = null,
    codeSnippet: String? = null,
    onCopyCode: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    val itemShapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = itemShapes.shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!body.isNullOrBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!codeSnippet.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = codeSnippet,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (onCopyCode != null) {
                            IconButton(onClick = onCopyCode) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = stringResource(R.string.terminal_copy_command),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            action?.invoke()
        }
    }
}
