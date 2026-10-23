package app.podara.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.podara.theme.DesignTokens
import kotlinx.coroutines.delay

/**
 * A hover tooltip for a control whose only label is its glyph.
 *
 * The app had no tooltip anywhere: an icon button's meaning lived entirely in
 * `contentDescription`, and Compose Desktop does not surface that to the pointer.
 * So `CropSquare` as a maximise glyph, or the difference between two overlapping
 * filter icons, is a guess. A tooltip is also the one affordance a desktop app
 * owes a pointer user that a touch design does not.
 *
 * Hover is driven here rather than left to Material. `TooltipBox` expands on
 * focus and press, which is right on the platform it was designed for and wrong
 * for this one — the whole point is to answer "what is this under my cursor"
 * before the user commits to clicking it.
 *
 * [DesignTokens.Tooltip.DelayMs] exists because an instant tooltip fires on the
 * way past the control: sweeping the mouse across a toolbar would strobe every
 * glyph on the path. A short wait means only a cursor that has stopped on the
 * control asks a question, and gets an answer.
 *
 * Call sites pass the same string they already pass as `contentDescription`, so
 * the accessible name and the visible one cannot drift apart.
 *
 * [modifier] exists because wrapping a control in a tooltip moves its slot in
 * the layout: a `weight` or `alignInBoxParent` that belonged to the control now
 * has to belong to the wrapper, or the row it sits in repacks.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PodaraTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tooltipState = rememberTooltipState(isPersistent = false)

    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(DesignTokens.Tooltip.DelayMs.toLong())
            // The pointer can have left during the wait; `isHovered` is read from
            // the snapshot the coroutine was launched against, so re-check through
            // the state object rather than the captured value.
            if (isHovered) tooltipState.show()
        } else {
            tooltipState.dismiss()
        }
    }

    TooltipBox(
        modifier = modifier.then(Modifier.hoverable(interactionSource)),
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(
            DesignTokens.Tooltip.AnchorGap
        ),
        state = tooltipState,
        tooltip = {
            PlainTooltip {
                Text(
                    text = text,
                    fontSize = DesignTokens.TypeScale.Caption,
                    modifier = Modifier.widthIn(max = DesignTokens.Tooltip.MaxWidth),
                )
            }
        },
        content = content
    )
}
