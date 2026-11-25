@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class
)

package moe.shizuku.manager.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults as TvOutlinedButtonDefaults
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.SurfaceDefaults as TvSurfaceDefaults
import androidx.tv.material3.Switch as TvSwitch
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R

@Composable
fun tvFocusBorder(
    shape: Shape = TvMaterialTheme.shapes.medium,
    focusedColor: Color = TvMaterialTheme.colorScheme.primary,
    unfocusedColor: Color = TvMaterialTheme.colorScheme.borderVariant.copy(alpha = 0.45f)
): ClickableSurfaceBorder {
    val focused = Border(
        border = BorderStroke(2.5.dp, focusedColor),
        shape = shape
    )
    return ClickableSurfaceDefaults.border(
        border = Border(
            border = BorderStroke(1.dp, unfocusedColor),
            shape = shape
        ),
        focusedBorder = focused,
        pressedBorder = focused,
        disabledBorder = Border(
            border = BorderStroke(1.dp, unfocusedColor.copy(alpha = 0.25f)),
            shape = shape
        )
    )
}

@Composable
fun tvFocusScale(focusedScale: Float = 1.02f): ClickableSurfaceScale {
    return ClickableSurfaceDefaults.scale(focusedScale = focusedScale)
}

@Composable
fun TvMenuButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    enabled: Boolean = true
) {
    val shape = TvMaterialTheme.shapes.medium
    val alpha = if (isSystemInDarkTheme()) 0.35f else 0.14f
    TvSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = tvFocusScale(focusedScale = 1.02f),
        border = tvFocusBorder(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) {
                TvMaterialTheme.colorScheme.primaryContainer
            } else {
                TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
            },
            contentColor = if (isSelected) {
                TvMaterialTheme.colorScheme.onPrimaryContainer
            } else {
                TvMaterialTheme.colorScheme.onSurface
            },
            focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = TvMaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            TvText(text = stringResource(label), style = TvMaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun TvBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes labelRes: Int = R.string.tv_action_back,
    @DrawableRes icon: Int = R.drawable.ic_arrow_back_24
) {
    TvMenuButton(
        icon = icon,
        label = labelRes,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
fun TvTwoPaneLayout(
    title: String,
    sidebarContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    sidebarModifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    mainContent: @Composable BoxScope.() -> Unit
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .focusGroup()
                .focusRestorer()
                .padding(32.dp)
                .then(sidebarModifier),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = title,
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            sidebarContent()
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusGroup()
                .focusRestorer()
                .then(contentModifier),
            content = mainContent
        )
    }
}

@Composable
fun TvStaticCard(
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    shape: Shape = TvMaterialTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.35f else 0.14f
    val containerColor = if (danger) {
        TvMaterialTheme.colorScheme.errorContainer.copy(alpha = alpha)
    } else {
        TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    }
    val contentColor = if (danger) {
        TvMaterialTheme.colorScheme.onErrorContainer
    } else {
        TvMaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (danger) {
        TvMaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    } else {
        TvMaterialTheme.colorScheme.borderVariant.copy(alpha = 0.45f)
    }

    TvSurface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = TvSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = Border(
            border = BorderStroke(1.dp, borderColor),
            shape = shape
        ),
        content = content
    )
}

@Composable
fun TvActionCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
    shape: Shape = TvMaterialTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.35f else 0.14f
    val containerColor = when {
        danger -> TvMaterialTheme.colorScheme.errorContainer.copy(alpha = alpha)
        selected -> TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)
        else -> TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    }
    val focusedContainerColor = when {
        danger -> TvMaterialTheme.colorScheme.errorContainer
        selected -> TvMaterialTheme.colorScheme.primaryContainer
        else -> TvMaterialTheme.colorScheme.surfaceVariant
    }
    val focusedContentColor = when {
        danger -> TvMaterialTheme.colorScheme.onErrorContainer
        selected -> TvMaterialTheme.colorScheme.onPrimaryContainer
        else -> TvMaterialTheme.colorScheme.onSurface
    }
    val focusedBorderColor = if (danger) {
        TvMaterialTheme.colorScheme.error
    } else {
        TvMaterialTheme.colorScheme.primary
    }

    TvSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = tvFocusScale(focusedScale = 1.02f),
        border = tvFocusBorder(shape = shape, focusedColor = focusedBorderColor),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = TvMaterialTheme.colorScheme.onSurface,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor
        ),
        content = content
    )
}

@Composable
fun TvToggleCard(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    shape: Shape = TvMaterialTheme.shapes.medium
) {
    TvActionCard(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        enabled = enabled,
        selected = checked,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (icon != null) {
                ShizukuIcon(
                    icon = icon,
                    modifier = Modifier.size(24.dp),
                    tint = if (checked) {
                        TvMaterialTheme.colorScheme.primary
                    } else {
                        TvMaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!summary.isNullOrBlank()) {
                    TvText(
                        text = summary,
                        style = TvMaterialTheme.typography.bodySmall,
                        color = TvMaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TvText(
                    text = stringResource(if (checked) R.string.tv_state_on else R.string.tv_state_off),
                    style = TvMaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (checked) {
                        TvMaterialTheme.colorScheme.primary
                    } else {
                        TvMaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                TvSwitch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    modifier = Modifier.focusProperties { canFocus = false }
                )
            }
        }
    }
}

@Composable
fun TvSettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null
) {
    TvToggleCard(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        icon = icon,
        shape = TvMaterialTheme.shapes.medium
    )
}

@Composable
fun TvSettingsClickRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null
) {
    TvActionCard(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TvMaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (icon != null) {
                ShizukuIcon(
                    icon = icon,
                    modifier = Modifier.size(24.dp),
                    tint = TvMaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TvText(
                    text = title,
                    style = TvMaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!summary.isNullOrBlank()) {
                    TvText(
                        text = summary,
                        style = TvMaterialTheme.typography.bodySmall,
                        color = TvMaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun TvActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val shape = TvMaterialTheme.shapes.medium
    val focusedBorder = Border(
        border = BorderStroke(2.5.dp, TvMaterialTheme.colorScheme.onSurface),
        shape = shape
    )
    TvButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TvButtonDefaults.shape(shape = shape),
        scale = TvButtonDefaults.scale(focusedScale = 1.04f),
        border = TvButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, TvMaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                shape = shape
            ),
            focusedBorder = focusedBorder,
            pressedBorder = focusedBorder
        ),
        colors = TvButtonDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.primaryContainer,
            contentColor = TvMaterialTheme.colorScheme.onPrimaryContainer,
            focusedContainerColor = TvMaterialTheme.colorScheme.primary,
            focusedContentColor = TvMaterialTheme.colorScheme.onPrimary
        ),
        content = content
    )
}

@Composable
fun TvSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val shape = TvMaterialTheme.shapes.medium
    val focusedBorder = Border(
        border = BorderStroke(2.5.dp, TvMaterialTheme.colorScheme.primary),
        shape = shape
    )
    TvOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TvOutlinedButtonDefaults.shape(shape = shape),
        scale = TvOutlinedButtonDefaults.scale(focusedScale = 1.04f),
        border = TvOutlinedButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, TvMaterialTheme.colorScheme.border),
                shape = shape
            ),
            focusedBorder = focusedBorder,
            pressedBorder = focusedBorder
        ),
        colors = TvOutlinedButtonDefaults.colors(
            contentColor = TvMaterialTheme.colorScheme.onSurface,
            focusedContainerColor = TvMaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = TvMaterialTheme.colorScheme.onSurface
        ),
        content = content
    )
}

@Composable
fun TvDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    initialFocusRequester: FocusRequester? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            if (initialFocusRequester != null) {
                LaunchedEffect(initialFocusRequester) {
                    runCatching { initialFocusRequester.requestFocus() }
                }
            }
            title()
        },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
        shape = TvMaterialTheme.shapes.extraLarge
    )
}
