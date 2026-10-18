package app.podara.desktop

import androidx.compose.ui.unit.Density
import app.podara.platform.WindowGeometry
import app.podara.player.MiniPlayerTransport
import app.podara.theme.DesignTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two numeric budgets behind the window-resize fixes.
 *
 * Both are plain values on purpose: the defects were arithmetic, not rendering.
 * Nothing here needs a window, and nothing here would have been caught by one —
 * `minimumSize` on a real frame is a one-line AWT assignment, but the bug that
 * mattered was that 260dp was reserved for controls that need 232dp, and that
 * is a comparison of constants.
 */
class WindowGeometryTest {

    @Test
    fun testMinimumWidthCoversTheFixedSidebarPlusUsableContent() {
        // The sidebar is a fixed-width column that never shrinks, so every dp
        // added to the floor has to be worth something on the content side of it.
        assertEquals(
            DesignTokens.Sidebar.Width + WindowGeometry.MinContentWidth,
            WindowGeometry.MinWidth
        )
        assertTrue(
            WindowGeometry.MinContentWidth.value >= 600f,
            "content area under the floor is too narrow to hold a list row: ${WindowGeometry.MinContentWidth}"
        )
    }

    @Test
    fun testMinimumSizeConvertsDpToDevicePixels() {
        // AWT wants pixels; the budget is in dp. Handing 900 to AWT on a 150%
        // display would have made the floor a third smaller than intended.
        val atOne = WindowGeometry.minimumSizePx(Density(1f))
        assertEquals(WindowGeometry.MinWidth.value.toInt(), atOne.width)
        assertEquals(WindowGeometry.MinHeight.value.toInt(), atOne.height)

        val atOneAndAHalf = WindowGeometry.minimumSizePx(Density(1.5f))
        assertEquals((WindowGeometry.MinWidth.value * 1.5f).toInt(), atOneAndAHalf.width)
        assertTrue(
            atOneAndAHalf.width > atOne.width,
            "the floor must scale with the display, not stay in dp"
        )
    }

    @Test
    fun testMiniPlayerTransportFloorIsTheSumOfItsControls() {
        val expected =
            MiniPlayerTransport.SpeedWidth +
                MiniPlayerTransport.SeekBackSize +
                MiniPlayerTransport.PlayPauseSize +
                MiniPlayerTransport.SeekForwardSize +
                MiniPlayerTransport.ControlGap * 3
        assertEquals(expected, MiniPlayerTransport.MinWidth)
    }

    @Test
    fun testMiniPlayerTransportHasSlackToGiveBack() {
        // The regression: 260dp was the *floor*, so the slack over the controls
        // was held against the title at every window size. As a ceiling the same
        // number is fine — it is only reclaimable if it exceeds the floor.
        assertTrue(
            MiniPlayerTransport.MaxWidth > MiniPlayerTransport.MinWidth,
            "MaxWidth ${MiniPlayerTransport.MaxWidth} is not above MinWidth " +
                "${MiniPlayerTransport.MinWidth}; the cluster has nothing to shrink"
        )
        assertTrue(
            MiniPlayerTransport.MaxWidth.value <= 260f,
            "the cluster must not claim more than it used to: ${MiniPlayerTransport.MaxWidth}"
        )
    }
}
