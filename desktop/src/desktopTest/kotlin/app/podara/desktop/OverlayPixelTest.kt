package app.podara.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.podara.FullPlayerOverlay
import app.podara.fullPlayerEnterTransition
import app.podara.fullPlayerFadedEnterTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Pixel-level tests for the full-player overlay.
 *
 * FullPlayerOverlayTest asserts *structure*: that a node is in the semantics tree,
 * or that a container body ran a number of times. That is exactly what every
 * iteration of this bug slipped through. The offending content was fully
 * composed and fully on the semantics tree — it was simply painted over, so
 * every structural assertion in the suite passed while the screen behind was
 * invisible or the panel was transparent.
 *
 * These tests close that gap by reading rendered pixels. Compose Multiplatform
 * 1.9.0's desktop test artifact really does ship an image capture API:
 *
 *   org.jetbrains.compose.ui:ui-test-desktop:1.9.0
 *     androidx/compose/ui/test/SkikoImageHelpersKt.class
 *     public static final ImageBitmap captureToImage(SemanticsNodeInteraction)
 *
 * so the project-wide note that "Compose desktop's ui-test has no
 * captureToImage" is wrong for this version. That was verified by compiling and
 * running against the real artifact, not by reading documentation.
 *
 * The invariant under test, and why it is the right one:
 *
 * The player must never present a frame in which the content area has been
 * replaced by a blank field of the page background. Both earlier attempts to
 * suppress a white flash did exactly that, from opposite directions:
 *
 *  - Putting an opaque fill on the always-composed container (d65c348) left the
 *    container painting the page colour over every screen, permanently.
 *  - Gating that fill on the overlay being open, and additionally fading the
 *    panel in (73cd454, then ddb2723), traded that for a hard cut to a blank
 *    page on open, followed by 300 ms of translucency while the panel travelled.
 *
 * So the property worth asserting is not "the overlay covers the screen behind"
 * — it is "the overlay arrives by travelling, and it is opaque while it does".
 * The settled state is identical in all three shapes. Only the frames differ,
 * which is why this had to be tested frame by frame.
 *
 * What none of this can do, and nobody should pretend otherwise:
 *
 *   - It says nothing about *feel*. Frame pacing and smoothness are properties of
 *     the running app, not of a frame captured in a headless scene. See
 *     VISUAL-CHECKS.md.
 *   - It covers the harness below, not the real App.kt tree. If App.kt changes
 *     shape, the harness has to be updated by hand or these tests will quietly
 *     keep testing a shape that no longer exists.
 *   - Each capture reads one instant. The frame tests advance a frozen clock in
 *     fixed steps, so they only see the frames they sample.
 *   - A pixel says what was painted, not what was legible. Contrast, colour
 *     harmony and "does this read well" stay human judgements.
 */
class OverlayPixelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** The screen behind the overlay. Loud enough that being covered is obvious. */
    private val UnderlyingScreenColor = Color(0xFFB00020)

    /**
     * The player's own opaque root.
     *
     * Deliberately a near-white, because that is the colour the bug was reported
     * as. A test using a saturated colour here would pass against the broken
     * shape and prove nothing about a white flash.
     */
    private val OverlayBackingColor = Color(0xFFFAF9F7)

    /** The player's artwork, painted on top of its own opaque root. */
    private val OverlayArtColor = Color(0xFF0A84FF)

    /**
     * Mirrors the overlay in App.kt, with the two defects that have each shipped
     * switchable so they can be reproduced rather than described.
     *
     *  - [opaqueBackdrop] adds the full-screen fill gated on the overlay being
     *    open, from ddb2723. Instant blank page on open.
     *  - [fadeOnEnter] fades the panel while it travels, from 73cd454. The panel
     *    is translucent mid-flight, so the screen behind shows through it.
     *
     * App.kt uses `opaqueBackdrop = false, fadeOnEnter = false`.
     */
    @androidx.compose.runtime.Composable
    private fun OverlayHarness(
        visible: Boolean,
        opaqueBackdrop: Boolean = false,
        fadeOnEnter: Boolean = false
    ) {
        Box(Modifier.fillMaxSize().testTag("root").background(UnderlyingScreenColor)) {
            Text("underlying screen", modifier = Modifier.testTag("content").align(Alignment.TopStart))

            // The one shape that has actually shipped wrong, reproduced by hand
            // because it lives outside FullPlayerOverlay's AnimatedVisibility: a
            // full-screen backing fill gated on the player being open.
            if (opaqueBackdrop && visible) {
                Box(Modifier.fillMaxSize().background(OverlayBackingColor))
            }

            // The real overlay, not a copy of it. Everything that has gone wrong
            // here has been around this call — the container's composition, a
            // backing fill, a fade — so animating a stand-in would certify the
            // wrong shape.
            FullPlayerOverlay(
                visible = visible,
                enter = if (fadeOnEnter) fullPlayerFadedEnterTransition() else fullPlayerEnterTransition()
            ) {
                PlayerSurface()
            }
        }
    }

    /**
     * The player's surface: an opaque root of its own, matching FullPlayer's
     * `Modifier.fillMaxSize().background(colors.background)`. That opacity is
     * what makes the backing fill unnecessary, so it is load-bearing here.
     */
    @androidx.compose.runtime.Composable
    private fun PlayerSurface() {
        Box(
            Modifier.fillMaxSize().testTag("overlay").background(OverlayBackingColor),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(200.dp).background(OverlayArtColor).testTag("overlayArt"))
        }
    }

    // ── Pixel helpers ──

    /**
     * Frames come out of Skia as premultiplied BGRA8888 and back, so an
     * exactly-opaque fill can land one 8-bit step off. One step of tolerance
     * covers that and nothing meaningful.
     */
    private val Tolerance = 1f / 255f

    /** Sampling stride. Small enough that a thin sliver is still counted. */
    private val Stride = 8

    private fun centrePixel(bitmap: ImageBitmap): Color =
        bitmap.toPixelMap().let { it[it.width / 2, it.height / 2] }

    /** Top-left, inside the underlying screen's caption area but clear of it. */
    private fun cornerPixel(bitmap: ImageBitmap): Color =
        bitmap.toPixelMap().let { it[8, 8] }

    /**
     * Near the bottom-left, which is deep inside a panel that has begun to slide
     * up and well clear of the centred artwork. Used to tell an opaque panel from
     * a blended one.
     */
    private fun panelEdgePixel(bitmap: ImageBitmap): Color =
        bitmap.toPixelMap().let { it[8, it.height - 8] }

    /** How many pixels are sampled on the whole frame. */
    private fun sampledCount(bitmap: ImageBitmap): Int {
        val pixels = bitmap.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height step Stride) {
            for (x in 0 until pixels.width step Stride) count++
        }
        return count
    }

    private fun countPixelsNear(bitmap: ImageBitmap, color: Color): Int {
        val pixels = bitmap.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height step Stride) {
            for (x in 0 until pixels.width step Stride) {
                val c = pixels[x, y]
                if (abs(c.red - color.red) <= Tolerance &&
                    abs(c.green - color.green) <= Tolerance &&
                    abs(c.blue - color.blue) <= Tolerance
                ) {
                    count++
                }
            }
        }
        return count
    }

    /** Share of the frame painted within tolerance of [color], 0f..1f. */
    private fun coverage(bitmap: ImageBitmap, color: Color): Float {
        val total = sampledCount(bitmap)
        return if (total == 0) 0f else countPixelsNear(bitmap, color).toFloat() / total
    }

    private fun isColorNear(expected: Color, actual: Color): Boolean =
        abs(expected.red - actual.red) <= Tolerance &&
            abs(expected.green - actual.green) <= Tolerance &&
            abs(expected.blue - actual.blue) <= Tolerance &&
            abs(expected.alpha - actual.alpha) <= Tolerance

    private fun assertColor(expected: Color, actual: Color, message: String) {
        assertTrue("$message: expected $expected but the frame shows $actual", isColorNear(expected, actual))
    }

    private fun captureRoot(): ImageBitmap = composeTestRule.onNodeWithTag("root").captureToImage()

    private fun assertColorAbsent(bitmap: ImageBitmap, color: Color, message: String) {
        val found = countPixelsNear(bitmap, color)
        assertEquals("$message ($found sampled pixels matched)", 0L, found.toLong())
    }

    // ── Frame sweep ──

    /**
     * Mounts [OverlayHarness] with a frozen clock, flips it open and walks the
     * enter transition one frame at a time.
     *
     * One frame is captured and discarded before the sweep begins.
     * `runOnUiThread { visible.value = true }` followed by `waitForIdle()` does
     * *not* get the change onto the screen: the overlay is still absent from the
     * semantics tree and the frame is still the closed state. The change is drawn
     * on the first clock advance. Measured, not assumed — without discarding that
     * frame the regression test below passes against the broken shape for the
     * wrong reason, which is how it got in here the first time.
     *
     * @return one captured frame per step, starting on the first frame the enter
     *   transition has been drawn into — the frame the flash lived on.
     */
    private fun sweepEnterTransition(
        opaqueBackdrop: Boolean = false,
        fadeOnEnter: Boolean = false,
        frames: Int = 40
    ): List<ImageBitmap> {
        beginEnterTransition(opaqueBackdrop, fadeOnEnter)
        return (0 until frames).map {
            val captured = captureRoot()
            composeTestRule.mainClock.advanceTimeByFrame()
            captured
        }
    }

    /**
     * Freezes the clock, opens the overlay and parks the clock on the first
     * drawn frame of the enter transition.
     *
     * Separate from [sweepEnterTransition] on purpose. A sweep runs the clock to
     * the end of the transition, so anything that needs to sample a *particular*
     * point in the travel has to start from here instead — calling both in
     * sequence silently samples the settled state, which is how an opacity test
     * ended up comparing a completed fade against itself.
     */
    private fun beginEnterTransition(
        opaqueBackdrop: Boolean = false,
        fadeOnEnter: Boolean = false
    ) {
        val visible = mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            OverlayHarness(visible.value, opaqueBackdrop, fadeOnEnter)
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { visible.value = true }
        composeTestRule.waitForIdle()

        // The frame the change has not been drawn into yet.
        captureRoot()
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    /**
     * Advances until the panel has covered at least [fraction] of the root height,
     * i.e. the panel is genuinely on screen and not merely in transit.
     *
     * Uses layout bounds rather than pixel counts on purpose: a translucent panel
     * blends to a colour matching neither the panel nor the screen behind, so
     * pixel counting cannot distinguish "not here yet" from "here but see-through".
     *
     * @return how many frames it took, for the caller's own sanity check.
     */
    private fun advanceUntilPanelHasCovered(fraction: Float, maxFrames: Int = 60): Int {
        val rootBounds = composeTestRule.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        // The panel starts a full panel-height below the root and rises, so the
        // target is measured up from the bottom edge, not down from the top.
        val target = rootBounds.top + rootBounds.height * (1f - fraction)
        var frame = 0
        while (frame < maxFrames) {
            val nodes = composeTestRule.onAllNodesWithTag("overlay").fetchSemanticsNodes()
            if (nodes.isNotEmpty() && nodes[0].boundsInRoot.top <= target) return frame
            composeTestRule.mainClock.advanceTimeByFrame()
            frame++
        }
        return frame
    }

    // ── Settled states ──

    @Test
    fun closedOverlayLeavesTheContentAreaUncovered() {
        composeTestRule.setContent { OverlayHarness(visible = false) }
        composeTestRule.waitForIdle()

        assertColor(UnderlyingScreenColor, centrePixel(captureRoot()), "closed overlay still covers the screen behind")
        assertColor(UnderlyingScreenColor, cornerPixel(captureRoot()), "closed overlay still covers the screen behind")
    }

    @Test
    fun openOverlayPaintsItsOwnContentWhereTheScreenBehindWas() {
        // The positive case. The centre of the frame is where the underlying
        // screen sits, so this only passes if the player's artwork really is on
        // top.
        composeTestRule.setContent { OverlayHarness(visible = true) }
        composeTestRule.waitForIdle()

        assertColor(OverlayArtColor, centrePixel(captureRoot()), "open overlay did not paint its content")
    }

    @Test
    fun openOverlayBackingLeavesNoPixelOfTheScreenBehind() {
        // Stronger than spot-checking the centre: nothing at all of the screen
        // behind may survive anywhere in the frame. A dark halo painted over the
        // content — the shadow shape that caused the original bug — would still
        // let the screen's red bleed through at its edges.
        composeTestRule.setContent { OverlayHarness(visible = true) }
        composeTestRule.waitForIdle()

        assertColorAbsent(captureRoot(), UnderlyingScreenColor, "the screen behind was still visible")
    }

    // ── The frames the flash lived on ──

    @Test
    fun theEnterTransitionStartsOnTheScreenBehindRatherThanABlankPage() {
        // The regression. The panel begins a full panel-height below the viewport,
        // so the content area must still be showing the screen behind on the first
        // drawn frame.
        //
        // The broken shape (opaqueBackdrop = true) paints the page colour across
        // the whole area on that same frame — measured: 100% page background while
        // the panel is still entirely off-screen. That is the reported white screen.
        val frames = sweepEnterTransition()

        val firstFrameBacking = coverage(frames.first(), OverlayBackingColor)
        assertTrue(
            "the first drawn frame of the enter transition was $firstFrameBacking of the " +
                "page background; expected the screen behind to still be showing",
            firstFrameBacking < 0.25f
        )
    }

    @Test
    fun theEnterTransitionCoversTheAreaProgressivelyRatherThanCutting() {
        // Guards the flash from the other side: the panel has to arrive by
        // travelling. If the page background can jump most of the way in a single
        // frame the transition has effectively become a cut, and settled-state
        // assertions will never notice.
        val frames = sweepEnterTransition()
        val coverages = frames.map { coverage(it, OverlayBackingColor) }

        val largestJump = coverages.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0f
        assertTrue(
            "the page background jumped by $largestJump in one frame; the panel is cutting, not sliding",
            largestJump < 0.15f
        )

        assertTrue(
            "the panel never finished arriving (ended at ${coverages.last()})",
            coverages.last() > 0.90f
        )
    }

    @Test
    fun theOpaqueBackdropShapeThatShippedIsDetected() {
        // ddb2723, reproduced. Asserted explicitly so this test documents the
        // failure mode instead of quietly passing against it: the content area is
        // already a blank page before the panel has moved at all.
        val frames = sweepEnterTransition(opaqueBackdrop = true)

        val firstFrameBacking = coverage(frames.first(), OverlayBackingColor)
        assertTrue(
            "expected the broken shape to replace the content area with the page background " +
                "on the first drawn frame, but it was $firstFrameBacking",
            firstFrameBacking > 0.90f
        )
    }

    @Test
    fun thePanelIsFullyOpaqueWhileItSlidesIn() {
        // Once the panel is genuinely on screen it must be painting its own colour,
        // not a blend with whatever is behind it.
        //
        // Sampled at 20% coverage rather than near the end: the slide runs 400 ms and
        // the fade 300 ms, so by the time the panel has travelled most of the way
        // both are nearly complete and a faded panel is indistinguishable from an
        // opaque one. Measured, the two shapes differ only in the early frames.
        beginEnterTransition()
        val frame = advanceUntilPanelHasCovered(0.20f)
        val pixel = panelEdgePixel(captureRoot())

        assertTrue("the panel never reached the frame", frame < 60)
        assertColor(
            OverlayBackingColor,
            pixel,
            "the panel was translucent while travelling, so the screen behind showed through it"
        )
    }

    @Test
    fun theFadedPanelShapeIsDetected() {
        // 73cd454, reproduced. Same point in the transition as the test above, but
        // with a fade on the way in. The panel blends with the screen behind instead
        // of covering it, so the pixel is neither the panel's colour nor the
        // screen's — it is part way between the two.
        beginEnterTransition(fadeOnEnter = true)
        val frame = advanceUntilPanelHasCovered(0.20f)
        val pixel = panelEdgePixel(captureRoot())

        assertTrue("the panel never reached the frame", frame < 60)
        assertTrue(
            "expected the faded panel to be translucent at this point in its travel, " +
                "but it painted its backing colour exactly at $pixel",
            !isColorNear(pixel, OverlayBackingColor)
        )
        assertTrue(
            "expected a blend of the panel and the screen behind, got $pixel",
            !isColorNear(pixel, UnderlyingScreenColor)
        )
    }

    @Test
    fun theSweepActuallySeesTheAnimationRun() {
        // Anti-vacuity. Without this, every test above would pass just as
        // happily against a harness that never animated at all — the artwork
        // would never appear and the panel would never travel.
        val frames = sweepEnterTransition()

        val artPerFrame = frames.map { coverage(it, OverlayArtColor) }
        assertEquals(
            "the artwork was already painted on the first enter frame",
            0f,
            artPerFrame.first(),
            0.001f
        )
        assertTrue(
            "the artwork never appeared, so the sweep never saw the animation run",
            artPerFrame.any { it > 0f }
        )
    }

    /**
     * The bug class, reproduced and pinned.
     *
     * With the overlay open, the semantics tree still reports the underlying
     * screen as displayed. That is why the whole structural suite passed. The
     * pixels say otherwise. Both halves are asserted on purpose: the structural
     * assertion documents the blindness, the pixel assertion is what catches the
     * regression.
     */
    @Test
    fun semanticsReportContentAsDisplayedEvenWhileItIsPaintedOver() {
        composeTestRule.setContent { OverlayHarness(visible = true) }
        composeTestRule.waitForIdle()

        // Blind: this is the assertion the structural suite made, and it passes.
        composeTestRule.onNodeWithTag("content").assertIsDisplayed()

        // Not blind: not one pixel of the screen behind survives the overlay.
        assertColorAbsent(
            captureRoot(),
            UnderlyingScreenColor,
            "occluded content was not detected — the screen behind is still visible somewhere"
        )
    }

    // ── Geometry: the weaker, structural companion ──

    @Test
    fun openOverlayBoundsMatchTheContentAreaBounds() {
        // A geometry assertion, not a visual one. It says the overlay occupies the
        // same rectangle as the area it is supposed to cover, and nothing more. It
        // cannot see occlusion, alpha or z-order; that is what the pixel tests
        // above are for. Kept because a bounds regression is cheap to catch and
        // gives a clearer failure than a colour mismatch.
        composeTestRule.setContent { OverlayHarness(visible = true) }
        composeTestRule.waitForIdle()

        val rootBounds = composeTestRule.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        val overlayBounds = composeTestRule.onNodeWithTag("overlay").fetchSemanticsNode().boundsInRoot

        assertEquals("overlay left edge", rootBounds.left, overlayBounds.left, 0.5f)
        assertEquals("overlay top edge", rootBounds.top, overlayBounds.top, 0.5f)
        assertEquals("overlay right edge", rootBounds.right, overlayBounds.right, 0.5f)
        assertEquals("overlay bottom edge", rootBounds.bottom, overlayBounds.bottom, 0.5f)
    }

    // ── Pixel plumbing, so the tests above cannot silently rot ──

    @Test
    fun captureIsDeterministicAndNonEmpty() {
        composeTestRule.setContent { OverlayHarness(visible = false) }
        composeTestRule.waitForIdle()

        val first = captureRoot()
        val second = captureRoot()

        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertTrue("captured a zero-sized frame", first.width > 0 && first.height > 0)
        assertColor(UnderlyingScreenColor, centrePixel(first), "capture is not reading the frame")
        assertColor(UnderlyingScreenColor, centrePixel(second), "capture is not reading the frame")
    }
}