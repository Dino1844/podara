package app.podara.platform

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podara.theme.DesignTokens
import java.awt.Dimension

/**
 * The floor the main window must not be resized below.
 *
 * The window is undecorated, so nothing in the OS imposes one. That is fine
 * until the layout runs out of room — and Compose does not shrink fixed-size
 * controls when it does, it squeezes whatever is elastic beside them. In the
 * mini player the transport cluster (speed, replay 10, play, forward 10) is
 * fixed-size, so under pressure the weighted areas either side of it collapse
 * instead: the episode title and the timestamps go to zero width and disappear
 * while the buttons stay put.
 *
 * Width, from the pieces the window actually holds:
 *
 *   240dp  the sidebar, a fixed-width column
 *   660dp  the content area beside it — enough for the 320dp search bar plus
 *          its surrounding padding, and enough left over that a list row's
 *          title and its action buttons sit side by side rather than
 *          overprinting each other
 *   -----
 *   900dp  total
 *
 * The mini player spans the whole window width rather than just the content
 * area, so 900dp is well clear of what that bar needs: 2 x 20dp padding, the
 * 232dp transport cluster, and the weighted text areas on either side.
 *
 * Height: 600dp minus the 36dp title bar and the 88dp mini player leaves about
 * 476dp of content, which is a screen header plus four full list rows — below
 * that the app reads as a strip rather than a list.
 */
object WindowGeometry {
    /** The content area beside the fixed sidebar, independent of the sidebar. */
    val MinContentWidth: Dp = 660.dp

    // Declared after MinContentWidth: object property initialisers run in
    // source order, so reading it first would capture 0.dp.
    val MinWidth: Dp = DesignTokens.Sidebar.Width + MinContentWidth

    val MinHeight: Dp = 600.dp

    /**
     * [MinWidth] x [MinHeight] in AWT pixels.
     *
     * `window.minimumSize` is expressed in device pixels while the budget above
     * is in dp, so the conversion belongs here rather than at the call site:
     * on a 150% display 900dp is 1350px, and handing 900 straight to AWT would
     * have made the floor a third smaller than intended on that machine.
     */
    fun minimumSizePx(density: Density): Dimension = with(density) {
        Dimension(MinWidth.roundToPx(), MinHeight.roundToPx())
    }
}
