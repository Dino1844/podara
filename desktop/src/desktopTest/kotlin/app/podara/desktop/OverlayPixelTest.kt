package app.podara.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.podara.theme.DesignTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Pixel-level tests for the full-player overlay.
 *
 * FullPlayerOverlayTest asserts *structure*: that a node is in the semantics
 * tree, or that a container body ran a number of times. That is exactly what
 * the white-flash bug slipped through. The offending content node was fully
 * composed and fully on the semantics tree — it was simply painted over by an
 * opaque container, so every structural assertion in the suite passed while
 * the screen behind was invisible.
 *
 * These tests close that gap by reading the rendered pixels. Compose
 * Multiplatform 1.9.0's desktop test artifact really does ship an image
 * capture API:
 *
 *   org.jetbrains.compose.ui:ui-test-desktop:1.9.0
 *     androidx/compose/ui/test/SkikoImageHelpersKt.class
 *     public static final ImageBitmap captureToImage(SemanticsNodeInteraction)
 *
 * so the project-wide note that "Compose desktop's ui-test has no
 * captureToImage" is wrong for this version. That was verified by compiling
 * and running against the real artifact, not by reading documentation.
 * Two other ways to get pixels out of a headless scene, also verified here:
 * `androidx.compose.ui.renderComposeScene(w, h, density) { }` (no test rule
 * needed at all) and `androidx.compose.ui.test.junit4.DesktopScreenshotTestRule`
 * for golden-image diffing.
 *
 * Known limitation, found while writing this: App.kt composes the whole
 * overlay inside `if (showFullPlayer)`, so AnimatedVisibility is *inserted*
 * already visible and its enter/exit specs never run — an animation only plays
 * when the visible value changes. That means the settled-state tests below are
 * the ones that describe the shipping app, while the frame-by-frame tests use
 * [AnimatedOverlayHarness], the shape in which the transition really does run.
 * Do not read the frame tests as a statement about App.kt until that changes.
 *
 * What none of this can do, and nobody should pretend otherwise:
 *
 *   - It says nothing about *feel*. Frame pacing, spring overshoot and
 *     smoothness are properties of the running app, not of a frame captured in
 *     a headless scene. See VISUAL-CHECKS.md.
 *   - It only covers the harnesses below, not the real App.kt tree. If App.kt
 *     changes shape, the harness has to be updated by hand or these tests will
 *     quietly keep testing a shape that no longer exists.
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

    /** The opaque backing the player paints while it animates in. */
    private val OverlayBackingColor = Color(0xFFFAF9F7)

    /** The player's own artwork, painted on top of the backing. */
    private val OverlayArtColor = Color(0xFF0A84FF)

    /**
     * Mirrors App.kt:872-896. The `if` and the backing fill are both
     * load-bearing, and the two flags say which shape is being built.
     *
     * - [overlayIsConditional] false reproduces the shape that shipped in
     *   d65c348: the container is composed anyway, so its opaque fill paints
     *   over every screen behind it.
     * - [backingFillOnContainer] false reproduces the shape from 73cd454: the
     *   fill sits on the AnimatedVisibility child, which renders at alpha 0 on
     *   the first enter frame, so the screen behind shows straight through.
     */
    @androidx.compose.runtime.Composable
    private fun OverlayHarness(
        showOverlay: Boolean,
        overlayIsConditional: Boolean = true,
        backingFillOnContainer: Boolean = true
    ) {
        Box(Modifier.fillMaxSize().testTag("root").background(UnderlyingScreenColor)) {
            Text("underlying screen", modifier = Modifier.testTag("content"))

            if (!overlayIsConditional || showOverlay) {
                Box(Modifier.fillMaxSize().then(backingFill(backingFillOnContainer))) {
                    AnimatedVisibility(
                        visible = if (overlayIsConditional) true else showOverlay,
                        enter = slideInVertically(animationSpec = tween(400)) { it } +
                            fadeIn(tween(DesignTokens.Animation.NormalMs)),
                        exit = slideOutVertically(animationSpec = tween(DesignTokens.Animation.NormalMs)) { it } +
                            fadeOut(tween(200))
                    ) {
                        PlayerSurface(backingFillOnContainer)
                    }
                }
            }
        }
    }

    /**
     * The same overlay with the container always composed, so flipping [visible]
     * is a real state change and the enter transition actually plays. Needed
     * because inserting AnimatedVisibility with visible = true does not animate.
     */
    @androidx.compose.runtime.Composable
    private fun AnimatedOverlayHarness(
        visible: Boolean,
        backingFillOnContainer: Boolean
    ) {
        Box(Modifier.fillMaxSize().testTag("root").background(UnderlyingScreenColor)) {
            Text("underlying screen", modifier = Modifier.testTag("content"))
            Box(Modifier.fillMaxSize().then(backingFill(backingFillOnContainer))) {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically(animationSpec = tween(400)) { it } +
                        fadeIn(tween(DesignTokens.Animation.NormalMs)),
                    exit = slideOutVertically(animationSpec = tween(DesignTokens.Animation.NormalMs)) { it } +
                        fadeOut(tween(200))
                ) {
                    PlayerSurface(backingFillOnContainer)
                }
            }
        }
    }

    private fun backingFill(onContainer: Boolean): Modifier =
        if (onContainer) Modifier.background(OverlayBackingColor) else Modifier

    /**
     * The player's own content: centred artwork over the backing, with the
     * caption pushed to the bottom so it never sits on the centre pixel these
     * tests sample.
     */
    @androidx.compose.runtime.Composable
    private fun PlayerSurface(backingFillOnContainer: Boolean) {
        Box(Modifier.fillMaxSize().testTag("overlay"), contentAlignment = Alignment.Center) {
            if (!backingFillOnContainer) {
                Box(Modifier.fillMaxSize().background(OverlayBackingColor))
            }
            Box(Modifier.size(200.dp).background(OverlayArtColor).testTag("overlayArt"))
            Box(Modifier.align(Alignment.BottomCenter)) {
                Text("full player")
            }
        }
    }

    // ── Pixel helpers ──

    /**
     * Frames come out of Skia as premultiplied BGRA8888 and back, so an
     * exactly-opaque fill can land one 8-bit step off. One step of tolerance
     * covers that and nothing meaningful.
     */
    private val Tolerance = 1f / 255f

    private fun centrePixel(bitmap: ImageBitmap): Color =
        bitmap.toPixelMap().let { it[it.width / 2, it.height / 2] }

    /** Top-right, where the underlying screen's caption never reaches. */
    private fun clearCornerPixel(bitmap: ImageBitmap): Color =
        bitmap.toPixelMap().let { it[it.width - 6, 6] }

    /** How many sampled pixels in the frame sit within tolerance of [color]. */
    private fun countPixelsNear(bitmap: ImageBitmap, color: Color, stride: Int = 8): Int {
        val pixels = bitmap.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height step stride) {
            for (x in 0 until pixels.width step stride) {
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

    private fun assertColor(expected: Color, actual: Color, message: String) {
        val close = abs(expected.red - actual.red) <= Tolerance &&
            abs(expected.green - actual.green) <= Tolerance &&
            abs(expected.blue - actual.blue) <= Tolerance &&
            abs(expected.alpha - actual.alpha) <= Tolerance
        assertTrue("$message: expected $expected but the frame shows $actual", close)
    }

    private fun captureRoot(): ImageBitmap = composeTestRule.onNodeWithTag("root").captureToImage()

    private fun assertColorAbsent(bitmap: ImageBitmap, color: Color, message: String) {
        val found = countPixelsNear(bitmap, color)
        assertEquals("$message ($found sampled pixels matched)", 0L, found.toLong())
    }

    // ── The occlusion class ──

    @Test
    fun openOverlayPaintsItsOwnContentWhereTheScreenBehindWas() {
        // The positive case. The centre of the frame is where the underlying
        // screen sits, so this only passes if the player's own artwork really
        // is on top.
        composeTestRule.setContent { OverlayHarness(showOverlay = true) }
        composeTestRule.waitForIdle()

        assertColor(OverlayArtColor, centrePixel(captureRoot()), "open overlay did not paint its content")
    }

    @Test
    fun openOverlayBackingLeavesNoPixelOfTheScreenBehind() {
        // Stronger than spot-checking the centre: nothing at all of the screen
        // behind may survive anywhere in the frame. A dark halo painted over
        // the content — the shadow shape that caused the original bug — would
        // still let the screen's red bleed through at its edges.
        composeTestRule.setContent { OverlayHarness(showOverlay = true) }
        composeTestRule.waitForIdle()

        assertColorAbsent(captureRoot(), UnderlyingScreenColor, "the screen behind was still visible")
    }

    @Test
    fun closedOverlayLeavesTheContentAreaUncovered() {
        composeTestRule.setContent { OverlayHarness(showOverlay = false) }
        composeTestRule.waitForIdle()

        assertColor(UnderlyingScreenColor, centrePixel(captureRoot()), "closed overlay still covers the screen behind")
        assertColor(
            UnderlyingScreenColor,
            clearCornerPixel(captureRoot()),
            "closed overlay still covers the screen behind"
        )
    }

    /**
     * The bug class, reproduced and pinned.
     *
     * With the container composed unconditionally — the shape that shipped in
     * d65c348 — the semantics tree still reports the underlying screen as
     * displayed. That is why the whole structural suite passed. The pixels say
     * otherwise. Both halves are asserted on purpose: the structural assertion
     * documents the blindness, the pixel assertion is what catches the
     * regression.
     */
    @Test
    fun semanticsReportContentAsDisplayedEvenWhileItIsPaintedOver() {
        composeTestRule.setContent {
            OverlayHarness(showOverlay = false, overlayIsConditional = false)
        }
        composeTestRule.waitForIdle()

        // Blind: this is the assertion the structural suite made, and it passes.
        composeTestRule.onNodeWithTag("content").assertIsDisplayed()

        // Not blind: the frame is the overlay's fill, so the screen behind is
        // genuinely invisible.
        assertColor(OverlayBackingColor, centrePixel(captureRoot()), "occluded content was not detected")
    }

    // ── Motion: the frames an animation passes through ──

    @Test
    fun everyFrameOfTheEnterTransitionKeepsTheContentAreaCovered() {
        // The flash lived in the frames, not in the settled state. The clock
        // starts frozen so the sweep starts on the very first frame of the
        // enter transition, when AnimatedVisibility renders its child at
        // alpha 0 and slid fully below the viewport.
        //
        // If the backing fill ever moves onto the AnimatedVisibility child
        // again, the early frames are the underlying screen and this fails.
        val visible = mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AnimatedOverlayHarness(visible.value, backingFillOnContainer = true)
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { visible.value = true }

        val artPixelsPerFrame = (0 until 30).map { frame ->
            val captured = captureRoot()
            assertColorAbsent(
                captured,
                UnderlyingScreenColor,
                "enter transition frame $frame let the screen behind show through"
            )
            countPixelsNear(captured, OverlayArtColor).also {
                composeTestRule.mainClock.advanceTimeByFrame()
            }
        }

        // Anti-vacuity guards. Without these the loop above would pass just as
        // happily against a scene that never animated at all.
        assertEquals(
            "the overlay's artwork was already painted on the first enter frame",
            0L,
            artPixelsPerFrame.first().toLong()
        )
        assertTrue(
            "the artwork never appeared, so the sweep never saw the animation run",
            artPixelsPerFrame.any { it > 0 }
        )
    }

    @Test
    fun movingTheBackingFillInsideTheAnimationIsDetected() {
        // Same harness, broken shape: the fill now lives on the
        // AnimatedVisibility child, which renders at alpha 0 on the first enter
        // frames. This is the 73cd454 regression, and the thing the structural
        // suite missed. Asserted explicitly so this test documents the failure
        // mode instead of quietly passing.
        val visible = mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            AnimatedOverlayHarness(visible.value, backingFillOnContainer = false)
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { visible.value = true }

        // Frame 0: the transition has been requested but has not composed its
        // child yet. This is the window the flash lives in.
        assertColor(
            UnderlyingScreenColor,
            centrePixel(captureRoot()),
            "expected the broken shape to expose the screen behind on the first enter frame"
        )

        composeTestRule.mainClock.advanceTimeByFrame()

        // The overlay is now fully composed and on the semantics tree. Every
        // structural query a test can make is satisfied...
        composeTestRule.onNodeWithTag("overlay").assertExists()
        // ...and the screen behind is still visible straight through it, a
        // frame later. That gap is the flash, and no structural assertion can
        // see it. (assertIsDisplayed does notice the child is at alpha 0, which
        // is worth knowing — but it still cannot tell a *transparent* overlay
        // from an *occluded* one, which is the other half of this bug class
        // and the one that shipped.)
        assertColor(
            UnderlyingScreenColor,
            centrePixel(captureRoot()),
            "expected the broken shape to still expose the screen behind one frame in"
        )
    }

    // ── Geometry: the weaker, structural companion ──

    @Test
    fun openOverlayBoundsMatchTheContentAreaBounds() {
        // This is a geometry assertion, not a visual one. It says the overlay
        // occupies the same rectangle as the area it is supposed to cover, and
        // nothing more. It cannot see occlusion, alpha or z-order; that is what
        // the pixel tests above are for. Kept because a bounds regression is
        // cheap to catch and gives a clearer failure than a colour mismatch.
        composeTestRule.setContent { OverlayHarness(showOverlay = true) }
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
        composeTestRule.setContent { OverlayHarness(showOverlay = false) }
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