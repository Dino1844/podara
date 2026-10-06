package app.podara

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.podara.theme.DesignTokens

/**
 * The full-player overlay: the container, the transition, and nothing else.
 *
 * Extracted from App.kt so the pixel tests can animate the real thing rather than
 * a hand-written copy. A copy in the test harness is only as good as the day
 * someone remembers to update it, and this overlay has been wrong three times in
 * three different ways — a copy would have quietly kept certifying the wrong shape
 * each time. Everything that has actually gone wrong here lives *around* the
 * AnimatedVisibility call: the container's composition, a backing fill, a fade.
 *
 * Those three are what the shape of this function is defending against:
 *
 *  - The container is composed unconditionally. AnimatedVisibility plays a
 *    transition only when `visible` changes, so wrapping it in
 *    `if (showFullPlayer)` inserted it already-visible on open and removed it on
 *    close, silencing both specs. The slide never played at all.
 *  - There is no opaque backing fill behind the panel. One did ship, gated on the
 *    player being open, and it turned opening the player into an instant cut to a
 *    blank page — the page background is white under the light scheme — that then
 *    spent the rest of the transition filling in.
 *  - There is no fade on the way in, for the same reason in reverse: it leaves the
 *    panel translucent while it travels, so the screen behind shows through it.
 *
 * FullPlayer's root is already opaque (`Modifier.fillMaxSize().background(colors.background)`),
 * which is what makes no backing fill necessary: sliding the panel up means every
 * pixel on screen is either the screen behind or the player, never a blend of the
 * two. It rises from the bottom edge, which is where the mini player it comes from
 * already sits.
 *
 * The exit keeps its fade, which is fine — fading out reveals the screen behind
 * rather than washing it out.
 *
 * A [BoxScope] extension because the overlay matches its parent's size without
 * affecting it — it is an overlay on the content area, not a participant in
 * laying it out.
 */
@Composable
internal fun BoxScope.FullPlayerOverlay(
    visible: Boolean,
    enter: EnterTransition = fullPlayerEnterTransition(),
    exit: ExitTransition = fullPlayerExitTransition(),
    content: @Composable () -> Unit
) {
    Box(Modifier.matchParentSize()) {
        AnimatedVisibility(visible = visible, enter = enter, exit = exit) {
            content()
        }
    }
}

internal fun fullPlayerEnterTransition(): EnterTransition = slideInVertically(
    animationSpec = tween(DesignTokens.Animation.FullPlayerSlideInMs),
    initialOffsetY = { it },
)

/** The enter spec as it was in 73cd454, kept only so a test can reproduce the defect. */
internal fun fullPlayerFadedEnterTransition(): EnterTransition =
    fullPlayerEnterTransition() + fadeIn(tween(DesignTokens.Animation.NormalMs))

internal fun fullPlayerExitTransition(): ExitTransition = slideOutVertically(
    animationSpec = tween(DesignTokens.Animation.NormalMs),
    targetOffsetY = { it },
) + fadeOut(tween(DesignTokens.Animation.FullPlayerFadeOutMs))