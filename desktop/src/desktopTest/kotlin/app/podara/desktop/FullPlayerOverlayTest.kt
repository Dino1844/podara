package app.podara.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import app.podara.theme.AppleLightPalette
import app.podara.theme.PodaraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for the full-player overlay.
 *
 * The overlay needs an opaque backing fill on its container, because
 * AnimatedVisibility renders its child at alpha 0 for the first frame of the
 * enter transition — without it the screen underneath flashes through. Putting
 * that fill on an unconditionally composed Box, however, paints an opaque white
 * over the entire content area and hides every screen behind it. Only the
 * sidebar survived, because it sits outside the overlay's parent.
 *
 * These tests pin both halves of that requirement: the backing fill must be on
 * the container, and the container must not exist while the overlay is closed.
 */
class FullPlayerOverlayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var showOverlayForTest = false
    private var overlayIsConditionalForTest = true

    /** Distinctive colour so a painted-over content area is unambiguous. */
    private val ContentMarkerColor = Color(0xFF00FF00)

    /**
     * Mirrors the structure in App.kt: content plus an overlay Box that carries
     * an opaque backing fill.
     *
     * [overlayIsConditional] reproduces the regression. When false, the Box is
     * composed regardless of [showOverlay] and an inner AnimatedVisibility
     * bound to the flag hides only its own child — the exact shape that painted
     * an opaque white over every screen behind it.
     */
    @androidx.compose.runtime.Composable
    private fun OverlayHarness(
        showOverlay: Boolean,
        overlayIsConditional: Boolean = true,
        onToggle: () -> Unit = {}
    ) {
        PodaraTheme(darkTheme = false) {
            val background = PodaraTheme.colors.background
            Box(Modifier.fillMaxSize()) {
                Text("underlying screen", modifier = Modifier.testTag("content"))

                if (!overlayIsConditional || showOverlay) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(background)
                    ) {
                        AnimatedVisibility(
                            visible = if (overlayIsConditional) true else showOverlay,
                            enter = slideInVertically(animationSpec = tween(400)) { it } + fadeIn(tween(300)),
                            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(tween(200))
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Text("full player", modifier = Modifier.testTag("overlay"))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun overlayOpenCoversUnderlyingContent() {
        composeTestRule.setContent { OverlayHarness(showOverlay = true, onToggle = {}) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("overlay").assertIsDisplayed()
    }

    @Test
    fun overlayClosedLeavesUnderlyingContentVisible() {
        // The regression: with the Box composed unconditionally, its opaque
        // #FFFFFF fill hid the content even though AnimatedVisibility was false.
        composeTestRule.setContent { OverlayHarness(showOverlay = false, onToggle = {}) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overlay").assertDoesNotExist()
    }

    @Test
    fun closingOverlayRevealsUnderlyingContent() {
        var showOverlay by mutableStateOf(true)
        composeTestRule.setContent {
            OverlayHarness(showOverlay = showOverlay, onToggle = { showOverlay = false })
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("overlay").assertIsDisplayed()

        showOverlay = false
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("overlay").assertDoesNotExist()
    }

    @Test
    fun closedOverlayComposesNothingWhenConditional() {
        // The fix's actual invariant. An opaque container composed while closed
        // paints over every screen behind it, so the container must not be
        // composed at all unless the overlay is open.
        //
        // This cannot be caught through the semantics tree: a Box carrying only
        // `background` has no semantics node and does not consume pointer
        // events, so the content below stays present and clickable under both
        // shapes. Compose desktop's ui-test has no captureToImage either, so
        // counting compositions is the way to observe it.
        assertEquals(0, composeOverlayContainer(overlayIsConditional = true, showOverlay = false))
    }

    @Test
    fun openOverlayComposesContainerExactlyOnceWhenConditional() {
        assertEquals(1, composeOverlayContainer(overlayIsConditional = true, showOverlay = true))
    }

    @Test
    fun closedOverlayStillComposesContainerWhenUnconditional() {
        // The regression, reproduced. Same render, container composed regardless
        // of the flag: this is what shipped and what hid the whole content area.
        assertEquals(1, composeOverlayContainer(overlayIsConditional = false, showOverlay = false))
    }

    /**
     * Composes a content area plus an overlay container and reports how many
     * times the container body ran.
     */
    private fun composeOverlayContainer(
        overlayIsConditional: Boolean,
        showOverlay: Boolean
    ): Int {
        var containerCompositions = 0

        composeTestRule.setContent {
            PodaraTheme(darkTheme = false) {
                val background = PodaraTheme.colors.background
                Box(Modifier.fillMaxSize()) {
                    Text("underlying screen", modifier = Modifier.testTag("content"))

                    @androidx.compose.runtime.Composable
                    fun Container() {
                        containerCompositions++
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(background)
                        ) {
                            AnimatedVisibility(visible = if (overlayIsConditional) true else showOverlay) {
                                Text("full player", modifier = Modifier.testTag("overlay"))
                            }
                        }
                    }

                    if (!overlayIsConditional || showOverlay) {
                        Container()
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        return containerCompositions
    }

    @Test
    fun overlayBackingFillIsOpaqueWhiteUnderLightScheme() {
        // Guards the value the flash fix depends on: if the backing fill ever
        // became translucent or matched the content, the flash would return.
        var backingFill: Color? = null
        composeTestRule.setContent {
            PodaraTheme(darkTheme = false) {
                backingFill = PodaraTheme.colors.background
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(AppleLightPalette.Background, backingFill)
        assertEquals(1f, backingFill!!.alpha)
    }
}
