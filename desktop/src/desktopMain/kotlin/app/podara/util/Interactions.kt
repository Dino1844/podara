package app.podara.util

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme

/**
 * Click handling with no press indication.
 *
 * The app drives its own hover and pressed feedback from a
 * [MutableInteractionSource] — a subtle fill change, a border tint, a shadow
 * lift — so Compose's default indication would double up on top of it. Most
 * call sites here already passed `indication = null`; a scattering of
 * `.clickable { }` calls did not, and those buttons lit up with the default
 * Material indication the moment they were pressed.
 *
 * `indication = null` still leaves the ripple-free press state available, so
 * `collectIsPressedAsState` keeps working.
 */
fun Modifier.clickableWithoutIndication(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: androidx.compose.ui.semantics.Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick
    )
}

/** As [clickableWithoutIndication], for call sites that supply their own [interactionSource]. */
fun Modifier.clickableWithoutIndication(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: androidx.compose.ui.semantics.Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick
    )
}

/**
 * Whether the user is currently driving the app from the keyboard.
 *
 * Compose has no "focus visible" concept: a component is either focused or not,
 * and the same styling applies whether focus came from Tab or from a mouse
 * click. Drawing a focus ring on every mouse click is what made these controls
 * look broken, and suppressing it entirely (the previous `canFocus = false`)
 * made the app unreachable by keyboard. Tracking the input modality is the
 * middle ground: keep every control focusable, but only paint the indicator
 * while the keyboard is the thing being used.
 *
 * Set to true on Tab, false on any pointer press — see the root handler in
 * `App.kt`.
 */
val LocalKeyboardNavigation = staticCompositionLocalOf { false }

/**
 * Historical name for [clickableWithFocusIndicator].
 *
 * The name is now wrong — the modifier no longer removes the focus ring, it
 * replaces the drawn one. Kept because 39 call sites use it and a rename across
 * all of them would bury the actual behaviour change; new code should call
 * [clickableWithFocusIndicator].
 */
fun Modifier.clickableWithoutIndicationOrFocusRing(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: androidx.compose.ui.semantics.Role? = null,
    onClick: () -> Unit
): Modifier = clickableWithFocusIndicator(
    interactionSource = interactionSource,
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick
)

/**
 * [clickableWithoutIndication] plus a focus indicator that only appears for
 * keyboard users.
 *
 * The ring is drawn by this modifier rather than by Compose, for two reasons:
 *
 * 1. `Modifier.clickable` accepts one `indication` for hover, press *and* focus.
 *    These call sites pass `indication = null` to suppress the material ripple,
 *    which also suppressed any focus cue — so making the element focusable again
 *    would have left keyboard focus invisible.
 * 2. The default ring is a hard-edged rectangle. These controls are pills and
 *    cards with their own radii, so the indicator follows [focusShape] instead.
 *
 * @param focusShape shape of the drawn indicator; defaults to a small radius
 *   that reads as an inset ring on both pill buttons and list rows.
 */
fun Modifier.clickableWithFocusIndicator(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: androidx.compose.ui.semantics.Role? = null,
    focusShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(DesignTokens.FocusIndicator.Radius),
    onClick: () -> Unit
): Modifier = composed {
    val keyboardNavigation = LocalKeyboardNavigation.current
    val focusColor = PodaraTheme.colors.accent
    var isFocused by remember { mutableStateOf(false) }

    clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick
    )
        .onFocusChanged { isFocused = it.isFocused }
        .then(
            if (isFocused && keyboardNavigation) {
                Modifier.border(
                    DesignTokens.FocusIndicator.Width,
                    focusColor,
                    focusShape
                )
            } else {
                Modifier
            }
        )
}

/**
 * Animates a hover wash in and out.
 *
 * The natural way to write this is
 *
 *     animateColorAsState(if (hovered) hoverColor else Color.Transparent, ...)
 *
 * and it is wrong. `Color.Transparent` is black with zero alpha, and
 * `animateColorAsState` interpolates each channel, so the animated value passes
 * through dark grey on the way in *and* on the way out: halfway in, a wash of
 * 0xFFEFEFF2 is 0.47-grey at 50% alpha, which over a light page reads as a dark
 * flash before it settles. Reported on the Discover featured card, whose 300 ms
 * sweep makes it the most visible case.
 *
 * Keeping the colour channels fixed and animating only the alpha is a pure
 * fade, which is what a hover wash is supposed to be. The off-state value is
 * `hoverColor.copy(alpha = 0f)` rather than `Color.Transparent` for exactly
 * that reason — both are invisible, but only one of them fades there without
 * visiting black.
 */
@Composable
fun animateHoverBackgroundColor(
    hovered: Boolean,
    hoverColor: Color,
    animationSpec: AnimationSpec<Color> = tween(DesignTokens.Animation.HoverMs)
): State<Color> = animateColorAsState(
    targetValue = if (hovered) hoverColor else hoverColor.copy(alpha = 0f),
    animationSpec = animationSpec,
    label = "hoverBackground"
)

/**
 * Whether a text field currently has focus.
 *
 * The root-level keyboard shortcut handler needs this: it runs on
 * `onPreviewKeyEvent`, so it sees Space and the arrow keys before the focused
 * text field does. Without the flag, typing "the daily" into the search box
 * would pause playback on the first space and seek on every arrow press.
 *
 * A composition local rather than a global so it follows the composition's
 * lifetime and cannot leak `true` across a screen change.
 */
val LocalTextFieldFocus = staticCompositionLocalOf { false }

/**
 * Writer for [LocalTextFieldFocus].
 *
 * A separate local because the provider lives at the root of the app, where the
 * state is created, while the readers are the text fields themselves — passing
 * a callback down is what lets a field announce "I have focus" without the root
 * having to know which field it was.
 */
val LocalSetTextFieldFocus = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
