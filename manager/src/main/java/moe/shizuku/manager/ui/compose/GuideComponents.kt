package moe.shizuku.manager.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.R

/**
 * Compact guide block used by the rish and ADB pairing screens.
 *
 * This intentionally does not have a hero/illustration container: the screen already has
 * a top app bar, and the guide itself is a scannable list of short steps.
 */
@Immutable
data class GuideStep(
    val title: String,
    val summary: String,
    val detail: String? = null,
    val code: String? = null,
    val onCopyCode: (() -> Unit)? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val visual: (@Composable () -> Unit)? = null
)

@Composable
fun GuideContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
fun GuideIntro(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun GuideStepList(
    steps: List<GuideStep>,
    modifier: Modifier = Modifier,
    initiallyExpanded: Int = -1
) {
    var expandedIndex by rememberSaveable { mutableIntStateOf(initiallyExpanded) }
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            GuideStepRow(
                step = step,
                number = index + 1,
                expanded = expandedIndex == index,
                isLast = index == steps.lastIndex,
                onToggle = {
                    expandedIndex = if (expandedIndex == index) -1 else index
                }
            )
        }
    }
}

private val GuideRailWidth = 36.dp
private val GuideBadgeSize = 28.dp

@Composable
private fun GuideStepRow(
    step: GuideStep,
    number: Int,
    expanded: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit
) {
    val badgeContainer by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(200),
        label = "guideBadgeContainer"
    )
    val badgeContent by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "guideBadgeContent"
    )
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "guideChevron"
    )
    val stateLabel = stringResource(
        if (expanded) R.string.guide_step_expanded else R.string.guide_step_collapsed
    )
    val layoutDirection = LocalLayoutDirection.current
    val railColor = MaterialTheme.colorScheme.outlineVariant

    Box(Modifier.fillMaxWidth()) {
        if (!isLast) {
            Canvas(Modifier.matchParentSize()) {
                val x = if (layoutDirection == LayoutDirection.Rtl) {
                    size.width - GuideRailWidth.toPx() / 2f
                } else {
                    GuideRailWidth.toPx() / 2f
                }
                drawLine(
                    color = railColor,
                    start = Offset(x, GuideBadgeSize.toPx() + 4.dp.toPx()),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        Row(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.width(GuideRailWidth),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = Modifier.size(GuideBadgeSize),
                    shape = CircleShape,
                    color = badgeContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = badgeContent
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = if (isLast) 0.dp else 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(
                            onClickLabel = stringResource(R.string.guide_step_toggle),
                            role = Role.Button,
                            onClick = onToggle
                        )
                        .semantics { stateDescription = stateLabel }
                        .padding(vertical = 4.dp)
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = step.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(rotation)
                    )
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(200)),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(150))
                ) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!step.detail.isNullOrBlank()) {
                            Text(
                                text = step.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        step.visual?.invoke()
                        if (!step.code.isNullOrBlank()) {
                            CommandBlock(code = step.code, onCopy = step.onCopyCode)
                        }
                        if (step.actionLabel != null && step.onAction != null) {
                            TextButton(
                                onClick = step.onAction,
                                modifier = Modifier.heightIn(min = 40.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = step.actionLabel,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommandBlock(
    code: String,
    onCopy: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    text = code,
                    modifier = Modifier.horizontalScroll(scroll),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.guide_copy_code),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

enum class GuideNoteTone { Neutral, Warning }

@Composable
fun GuideNote(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: GuideNoteTone = GuideNoteTone.Neutral
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tone == GuideNoteTone.Warning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Six compact slots that mirror the pairing code dialog. */
@Composable
fun PairingCodePreview(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.guide_pairing_code_preview)
    Row(
        modifier = modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(6) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.8f),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
