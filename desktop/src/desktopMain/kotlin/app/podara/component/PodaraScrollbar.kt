package app.podara.component

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme

/**
 * The app's scroll indicator, for any list that can outgrow its own height.
 *
 * This used to exist once, inline in the queue drawer, while the seven other
 * scrollable lists had nothing. On a desktop window the wheel always scrolls, so
 * the absence is not a broken interaction — it is the loss of the one answer a
 * scrollbar gives that a wheel does not: how long this list is, and where in it
 * you are. A Discover feed that ends silently at the twelfth row reads as a
 * short feed rather than as the top of a long one.
 *
 * The thumb is deliberately quiet at rest and only picks up colour on hover, so
 * adding it to seven screens does not add seven permanent grey rails.
 *
 * @param adapter usually `rememberScrollbarAdapter(listState)` for a lazy list,
 *   or `rememberScrollbarAdapter(scrollState)` for a scrolled column.
 *
 * A [BoxScope] extension because the thumb docks to the trailing edge of the
 * list it describes, which is a decision about that box rather than about the
 * scrollbar.
 */
@Composable
fun BoxScope.PodaraScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    thumbInset: Dp = DesignTokens.Scrollbar.Inset
) {
    val surfaces = PodaraTheme.surfaces
    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .padding(horizontal = thumbInset),
        style = ScrollbarStyle(
            minimalHeight = DesignTokens.Scrollbar.MinimalHeight,
            thickness = DesignTokens.Scrollbar.Thickness,
            shape = CircleShape,
            hoverDurationMillis = DesignTokens.Animation.HoverMs,
            unhoverColor = surfaces.cardFillHover,
            hoverColor = surfaces.cardBorderHover
        )
    )
}
