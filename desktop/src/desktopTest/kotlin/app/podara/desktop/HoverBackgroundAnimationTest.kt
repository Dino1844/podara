package app.podara.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.podara.util.animateHoverBackgroundColor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pixel-level test for the hover wash behind the Discover featured card and
 * every other place that fades a background in and out on hover.
 *
 * The reported bug: moving the cursor into the card and out of it both triggered
 * a dark grey background, which then settled to a lighter grey.
 *
 * The cause is a property of the endpoint, not of the animation spec.
 * `Color.Transparent` is black with zero alpha, and `animateColorAsState`
 * interpolates each channel. Animating Transparent → 0xFFEFEFF2 passes through
 * 0.47-grey at 50% alpha half way, which composited over a white page is a
 * clearly visible dark grey (luminance ~0.735 against a page at 1.0). The same
 * dark grey is passed through again on the way out. Measured before writing
 * this, not assumed: the minimum of the composite over the sweep lands at
 * t ≈ 0.53, matching what the user saw.
 *
 * The invariant is monotonicity. A wash that only fades its alpha changes the
 * composite in one direction only; any shape whose composite dips below both
 * endpoints mid-sweep has visited a colour that was never a state.
 */
class HoverBackgroundAnimationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val PageColor = Color.White

    /** The light scheme's cardFillHover — the colour of the reported flash. */
    private val HoverColor = Color(0xFFEFEFF2)

    /**
     * Renders the wash over a white page, turns hover on with a frozen clock and
     * returns the centre pixel's luminance proxy (red channel; both colours are
     * grey) frame by frame.
     *
     * [transparentEndpoint] reproduces the bug's shape exactly:
     * `else Color.Transparent`. The shipping shape is [animateHoverBackgroundColor].
     */
    private fun sweepHoverIn(transparentEndpoint: Boolean, frames: Int = 24): List<Float> {
        val hovered = mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize().testTag("root").background(PageColor)) {
                val bg = if (transparentEndpoint) {
                    animateColorAsState(
                        targetValue = if (hovered.value) HoverColor else Color.Transparent,
                        animationSpec = tween(300)
                    ).value
                } else {
                    animateHoverBackgroundColor(hovered.value, HoverColor, tween(300)).value
                }
                Box(Modifier.fillMaxSize().background(bg))
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { hovered.value = true }
        composeTestRule.waitForIdle()

        // Same measured lesson as OverlayPixelTest: the change is drawn on the
        // first clock advance, so this frame is the pre-change one. Discard it.
        captureRoot()
        composeTestRule.mainClock.advanceTimeByFrame()

        return (0 until frames).map {
            val captured = captureRoot()
            composeTestRule.mainClock.advanceTimeByFrame()
            captured.toPixelMap()[captured.width / 2, captured.height / 2].red
        }
    }

    private fun captureRoot(): ImageBitmap =
        composeTestRule.onNodeWithTag("root").captureToImage()

    /** Covers Skia's premultiplied-BGRA rounding, and nothing else. */
    private val Tol = 0.005f

    @Test
    fun hoverWashSettlesOnTheHoverColour() {
        val series = sweepHoverIn(transparentEndpoint = false)

        // Sanity: the sweep must actually end at the blended hover state, which
        // is darker than the page. Without this the monotonicity assertion below
        // would also pass against a wash that never appeared at all.
        assertTrue(
            "the wash never appeared (first=${series.first()}, last=${series.last()})",
            series.last() < series.first() - 0.01f
        )
        assertTrue(
            "the wash did not settle near the hover colour (last=${series.last()})",
            series.last() in 0.9f..1.0f
        )
    }

    @Test
    fun hoverWashNeverVisitsAColourDarkerThanEitherEndpoint() {
        // The regression. A pure alpha fade is monotonic: every frame is at most
        // as dark as the one before. A single frame that is darker than both the
        // settled state and the frame before it is the flash.
        val series = sweepHoverIn(transparentEndpoint = false)

        series.zipWithNext { previous, current ->
            assertTrue(
                "the wash got lighter mid-sweep ($previous -> $current); " +
                    "it should only ever move toward the hover colour",
                current <= previous + Tol
            )
        }
        assertTrue(
            "the wash dipped to ${series.min()}, below both the page " +
                "(${series.first()}) and the settled state (${series.last()})",
            series.min() >= series.last() - Tol
        )
    }

    @Test
    fun theTransparentEndpointShapeIsDetected() {
        // The reported shape, reproduced. Asserted explicitly so this test
        // documents the failure mode instead of quietly passing against it: the
        // composite dips visibly below both endpoints before settling.
        val series = sweepHoverIn(transparentEndpoint = true)

        val dip = series.min()
        assertTrue(
            "expected the Transparent-endpoint shape to dip darker mid-sweep; got min=$dip",
            dip < series.first() - 0.05f && dip < series.last() - 0.05f
        )
    }
}