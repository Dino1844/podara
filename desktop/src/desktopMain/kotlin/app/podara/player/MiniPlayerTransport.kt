package app.podara.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Geometry of the mini player's transport cluster: speed, replay 10, play/pause,
 * forward 10.
 *
 * Extracted from the layout so the numbers exist once and can be checked
 * without a window. The cluster used to be a flat `Modifier.width(260.dp)`, and
 * the root of that bug was that nobody could say how much of the 260 was the
 * controls and how much was slack — so the reserved width silently outgrew the
 * controls and started taking space from the title and the timestamps beside
 * it, which are elastic and therefore always lose that argument first.
 *
 * The controls themselves are fixed size: a play button that shrinks is a play
 * button that draws its icon outside its own circle. So the cluster's floor is
 * arithmetic, not taste — [MinWidth] — and everything between it and [MaxWidth]
 * is breathing room the layout is free to give back to the text.
 */
internal object MiniPlayerTransport {
    val SpeedWidth = 40.dp
    val SpeedHeight = 30.dp
    val SeekBackSize = 48.dp
    val PlayPauseSize = 56.dp
    val SeekForwardSize = 40.dp

    /** Gap between adjacent controls. */
    val ControlGap = 16.dp

    /**
     * The narrowest the cluster can be and still read as four separate
     * controls: the four fixed widths plus the three gaps between them. Below
     * this the buttons overlap, so the layout must not take the cluster under
     * it — the space has to come from the text columns instead.
     */
    val MinWidth: Dp =
        SpeedWidth + SeekBackSize + PlayPauseSize + SeekForwardSize + ControlGap * 3

    /**
     * The ceiling it will ever claim. This was once the *floor* (a hard
     * `width(260.dp)`), which reserved ~28dp more than the controls use and
     * held it against the title even when the window had none to spare.
     */
    val MaxWidth = 260.dp
}
