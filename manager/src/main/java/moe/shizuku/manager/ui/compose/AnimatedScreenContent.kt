package moe.shizuku.manager.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable

/**
 * Horizontal shared-axis style transition for in-app screens. Going deeper slides in from
 * the right; going back slides in from the left. Both enter and exit are animated.
 */
@Composable
fun <T> AnimatedScreenContent(
    targetState: T,
    label: String,
    depth: (T) -> Int = { 0 },
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            val forward = depth(targetState) >= depth(initialState)
            val direction = if (forward) 1 else -1
            (
                slideInHorizontally(tween(280)) { width -> direction * width / 10 } +
                    fadeIn(tween(220))
                ) togetherWith (
                slideOutHorizontally(tween(220)) { width -> -direction * width / 12 } +
                    fadeOut(tween(160))
                )
        },
        label = label
    ) { state ->
        content(state)
    }
}
