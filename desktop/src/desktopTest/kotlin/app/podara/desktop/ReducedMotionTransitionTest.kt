package app.podara.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.podara.FullPlayerOverlay
import app.podara.ScreenMotion
import app.podara.fullPlayerEnterTransition
import app.podara.fullPlayerExitTransition
import app.podara.screenTransitionSpec
import app.podara.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The degraded shapes behind the 减少动效 switch (DESIGN-CRAFT.md C4).
 *
 * OverlayPixelTest pins the full player's *slide* against a frozen clock; this
 * file pins the opposite branch of the same specs. "Reduced motion" is a
 * behavioural claim — nothing travels — and a structural assertion can hear it:
 * with the slide dropped, the entering content composes at its final position
 * from the first frame of the transition and never moves. The default specs are
 * asserted too, from the same harness, so both directions of the parameter are
 * load-bearing: if the reduced spec regressed into a slide, the first group
 * fails; if the flag stopped reaching the spec, the second group fails.
 *
 * The specs take the flag as a plain parameter, so these tests call the real
 * [screenTransitionSpec] / [fullPlayerEnterTransition] directly — no copy of
 * their shape, and no theme needed, since neither reads a CompositionLocal.
 */
class ReducedMotionTransitionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val RootTag = "motion_root"
    private val EnterTag = "motion_enter"

    /** Two-frame probe helper: frozen clock, flip the target, read bounds twice. */
    private fun boundsOfEnteringContentAfterFlip(
        setHarness: @androidx.compose.runtime.Composable (target: Boolean) -> Unit
    ): Triple<androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect> {
        val target = mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { setHarness(target.value) }
        composeTestRule.waitForIdle()

        val root = composeTestRule.onNodeWithTag(RootTag).fetchSemanticsNode().boundsInRoot
        composeTestRule.runOnUiThread { target.value = true }
        composeTestRule.waitForIdle()
        // The transition composes its entering content on the first clock
        // advance, not during the waitForIdle above — the same order
        // OverlayPixelTest.beginEnterTransition establishes.
        composeTestRule.mainClock.advanceTimeByFrame()
        val first = composeTestRule.onNodeWithTag(EnterTag).fetchSemanticsNode().boundsInRoot
        composeTestRule.mainClock.advanceTimeByFrame()
        val second = composeTestRule.onNodeWithTag(EnterTag).fetchSemanticsNode().boundsInRoot
        return Triple(root, first, second)
    }

    @Test
    fun reducedScreenPushFadesInPlaceWithoutTravelling() {
        val (root, first, second) = boundsOfEnteringContentAfterFlip { target ->
            Box(Modifier.fillMaxSize().testTag(RootTag)) {
                AnimatedContent(
                    targetState = target,
                    transitionSpec = {
                        screenTransitionSpec(
                            ScreenMotion.Forward,
                            risePx = 0,
                            pushFarPx = 40,
                            pushNearPx = 20,
                            reducedMotion = true
                        )
                    }
                ) { value ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(if (value) Color(0xFF123456) else Color(0xFF654321))
                            .testTag(if (value) EnterTag else "motion_exit")
                    )
                }
            }
        }

        assertEquals(
            "the entering screen must not be offset horizontally under reduced motion",
            root.left,
            first.left,
            0.5f
        )
        assertEquals(
            "and it must stay there — a fade does not move",
            root.left,
            second.left,
            0.5f
        )
    }

    @Test
    fun theDefaultScreenPushStillTravels() {
        // Anti-vacuity for the test above: same harness, flag off, the entering
        // screen must arrive from the push offset — otherwise the reduced test
        // would pass against a spec that never slid in the first place.
        val (root, first, _) = boundsOfEnteringContentAfterFlip { target ->
            Box(Modifier.fillMaxSize().testTag(RootTag)) {
                AnimatedContent(
                    targetState = target,
                    transitionSpec = {
                        screenTransitionSpec(
                            ScreenMotion.Forward,
                            risePx = 0,
                            pushFarPx = 40,
                            pushNearPx = 20,
                            reducedMotion = false
                        )
                    }
                ) { value ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(if (value) Color(0xFF123456) else Color(0xFF654321))
                            .testTag(if (value) EnterTag else "motion_exit")
                    )
                }
            }
        }

        assertTrue(
            "the default push must offset the entering screen; got left=${first.left} against root.left=${root.left}",
            first.left > root.left + 1f
        )
    }

    @Test
    fun reducedFullPlayerAppearsInPlaceWithoutRising() {
        val visible = mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(Modifier.fillMaxSize().testTag(RootTag)) {
                    FullPlayerOverlay(
                        visible = visible.value,
                        enter = fullPlayerEnterTransition(reducedMotion = true),
                        exit = fullPlayerExitTransition(reducedMotion = true)
                    ) {
                        Box(Modifier.fillMaxSize().testTag(EnterTag))
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        val root = composeTestRule.onNodeWithTag(RootTag).fetchSemanticsNode().boundsInRoot
        composeTestRule.runOnUiThread { visible.value = true }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()

        val first = composeTestRule.onNodeWithTag(EnterTag).fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the panel must be at its final position from the first frame — a fade does not rise",
            root.top,
            first.top,
            0.5f
        )
        composeTestRule.mainClock.advanceTimeByFrame()
        val second = composeTestRule.onNodeWithTag(EnterTag).fetchSemanticsNode().boundsInRoot
        assertEquals(root.top, second.top, 0.5f)
    }

    @Test
    fun theDefaultFullPlayerStillRises() {
        // Anti-vacuity: without the flag the panel starts a full height below
        // the viewport (OverlayPixelTest's premise); this pins that the
        // parameter, not a spec rewrite, is what changed.
        val visible = mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize().testTag(RootTag)) {
                FullPlayerOverlay(
                    visible = visible.value,
                    enter = fullPlayerEnterTransition(reducedMotion = false),
                    exit = fullPlayerExitTransition(reducedMotion = false)
                ) {
                    Box(Modifier.fillMaxSize().testTag(EnterTag))
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { visible.value = true }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()

        val root = composeTestRule.onNodeWithTag(RootTag).fetchSemanticsNode().boundsInRoot
        val first = composeTestRule.onNodeWithTag(EnterTag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the default enter must start the panel below its final position; got top=${first.top} against root.top=${root.top}",
            first.top > root.top + 1f
        )
    }
}
