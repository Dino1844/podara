package app.podara.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties

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
 * Opts a clickable out of the focus ring.
 *
 * `Modifier.clickable` makes its target focusable, so a mouse click grants it
 * focus and Compose Desktop then draws a ring around it. On a nav item that
 * already has its own active/hover treatment that ring reads as a stray grey
 * outline, and nothing in this app is keyboard-navigable, so it carries no
 * information.
 *
 * Must be applied after `clickable` so it wins over the focusability that
 * modifier adds. Keyboard focus still reaches the element and semantics are
 * unchanged; only the drawn indicator is suppressed.
 */
fun Modifier.withoutFocusRing(): Modifier = focusProperties { canFocus = false }

/** [clickableWithoutIndication] with no press indication and no focus ring. */
fun Modifier.clickableWithoutIndicationOrFocusRing(
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
    ).withoutFocusRing()
}
