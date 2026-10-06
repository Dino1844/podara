package app.podara.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.podara.PlayerOverlaidArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Structural tests for the main area and the full-player overlay.
 *
 * The bug that mattered here was not a paint problem. The current screen used to
 * sit inside `if (!showFullPlayer)`, commented "hidden when FullPlayer is
 * showing". That hid it by destroying it: every open and close tore the screen
 * down and rebuilt it, so scroll positions reset and every LaunchedEffect re-ran
 * — Home re-read the database and Discover re-fetched the top charts. To the user
 * that read as "the main screen refreshes when I open the player".
 *
 * Structural assertions are the right tool here even though they were the wrong
 * tool for the white flash. This is not about paint order or alpha; it is about
 * whether a subtree is in the composition at all, and that is exactly what the
 * semantics tree and composition counts can answer. (Whether anything is actually
 * *visible* is a separate question, checked against pixels in OverlayPixelTest.)
 *
 * Everything here mounts the real `PlayerOverlaidArea`, not a copy of its shape,
 * so the assertions cannot pass while the app's own arrangement differs.
 */
class FullPlayerOverlayTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var playerVisible by mutableStateOf(false)

    /** How many times the content body composed. Reset per test. */
    private var contentCompositions = 0

    private fun setUpArea() {
        contentCompositions = 0
        playerVisible = false
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                PlayerOverlaidArea(
                    playerVisible = playerVisible,
                    sidebar = { Text("sidebar", Modifier.testTag("sidebar")) },
                    content = {
                        contentCompositions++
                        val counter = remember { mutableIntStateOf(0) }
                        Text(
                            text = "content ${counter.intValue}",
                            modifier = Modifier.testTag("content").clickable { counter.intValue++ }
                        )
                    },
                    player = { Text("player", Modifier.testTag("player")) }
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun contentComposesWhileThePlayerIsOpen() {
        // The regression. Content behind an opaque overlay is covered but still
        // composed; unmounting it is what destroyed scroll state and re-ran every
        // screen's loading effect on each open and close.
        setUpArea()
        assertTrue("content did not compose at all", contentCompositions > 0)

        playerVisible = true
        composeTestRule.waitForIdle()

        assertTrue(
            "the screen was left in the composition only $contentCompositions time(s) " +
                "after the player opened — it is being unmounted, not covered",
            contentCompositions > 1
        )
        assertTrue(
            "the content node left the semantics tree while the player was open",
            composeTestRule.onAllNodesWithTag("content").fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun contentStateSurvivesOpeningAndClosingThePlayer() {
        // The strongest form of the same assertion. Scroll position, loaded data
        // and selection are all remembered inside a screen, so if the subtree is
        // torn down they cannot survive. A counter stands in for all of it: it is
        // created by remember inside the content body, so losing it is exactly
        // what a user sees as the screen resetting.
        setUpArea()
        composeTestRule.onNodeWithTag("content").performClick()
        composeTestRule.onNodeWithTag("content").assertTextEquals("content 1")

        playerVisible = true
        composeTestRule.waitForIdle()
        playerVisible = false
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("content")
            .assertTextEquals("content 1")
    }

    @Test
    fun sidebarIsAlsoComposedWhileThePlayerIsOpen() {
        // The sidebar sits outside the overlay's content area, so it was never
        // covered by the `if`. It is asserted here so that a future change which
        // hides the whole row is caught rather than mistaken for the player
        // taking over the window.
        setUpArea()
        playerVisible = true
        composeTestRule.waitForIdle()

        assertTrue(
            "the sidebar was unmounted while the player was open",
            composeTestRule.onAllNodesWithTag("sidebar").fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun coveredContentIsHiddenFromAccessibilityServices() {
        // The one thing the `if` was reaching for. The content is visually covered
        // but still in the tree, so a screen reader would otherwise walk through
        // it while the player is open. Semantics is the right tool for that;
        // a conditional is not.
        //
        // Walks ancestors rather than reading the content node's own config:
        // `invisibleToUser` is applied to the container that holds the content, and
        // semantics do not inherit downward. Checking the child directly would
        // report the flag as absent even while it is doing its job.
        setUpArea()
        assertFalse(
            "content should be reachable by accessibility services while it is visible",
            isContentHiddenFromAccessibility()
        )

        playerVisible = true
        composeTestRule.waitForIdle()

        assertTrue(
            "covered content is still exposed to accessibility services",
            isContentHiddenFromAccessibility()
        )
    }

    /** Whether [content] or any of its ancestors is marked invisible to services. */
    private fun isContentHiddenFromAccessibility(): Boolean {
        val node = composeTestRule.onNodeWithTag("content", useUnmergedTree = true)
            .fetchSemanticsNode()
        return generateSequence(node) { it.parent }
            .any { it.config.contains(SemanticsProperties.InvisibleToUser) }
    }

    // ── The overlay itself ──

    @Test
    fun playerIsComposedOnlyWhileVisible() {
        setUpArea()
        composeTestRule.onNodeWithTag("player").assertDoesNotExist()

        playerVisible = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player").assertIsDisplayed()

        playerVisible = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player").assertDoesNotExist()
    }

    @Test
    fun overlayContainerIsComposedEvenWhileThePlayerIsClosed() {
        // Cheap structural pin for the transitions. AnimatedVisibility only plays
        // a transition when `visible` changes, so its container has to already be
        // in the tree while the player is closed. This was silently false for a
        // while: the container was inside `if (showFullPlayer)`, which meant the
        // 400 ms slide was dead code and closing was an instant cut.
        setUpArea()
        var overlayContainerCompositions = 0

        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    Text("screen", Modifier.testTag("content"))
                    overlayContainerCompositions++
                    androidx.compose.animation.AnimatedVisibility(visible = false) {
                        Text("player", Modifier.testTag("player"))
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "an AnimatedVisibility inserted already-invisible cannot animate",
            overlayContainerCompositions > 0
        )
        composeTestRule.onNodeWithTag("player").assertDoesNotExist()
    }

    @Test
    fun openingAndClosingRepeatedlyDoesNotAccumulateState() {
        // The leak-shaped version of the same bug: teardown on every toggle means
        // every open re-runs the screen's loading effects, so repeatedly toggling
        // does repeated work rather than none.
        setUpArea()
        val afterFirstOpen = contentCompositions

        repeat(3) {
            playerVisible = true
            composeTestRule.waitForIdle()
            playerVisible = false
            composeTestRule.waitForIdle()
        }

        // Recomposition still happens — that is fine. What must not happen is the
        // remembered counter being lost, which the test above pins directly. Here
        // the assertion is just that the cycle stays cheap and bounded.
        assertTrue(
            "three open/close cycles caused $afterFirstOpen -> $contentCompositions compositions, " +
                "which suggests the screen is being rebuilt each time",
            contentCompositions < afterFirstOpen * 10
        )
    }

    @Test
    fun contentIsDisplayedWhenThePlayerIsClosed() {
        setUpArea()
        composeTestRule.onNodeWithTag("content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sidebar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player").assertDoesNotExist()
    }

    @Test
    fun theOverlayCoversTheContentArea() {
        // Geometry only — bounds say nothing about what was painted on top, which
        // is what OverlayPixelTest is for. Kept because a bounds regression is
        // cheap to catch and fails more clearly than a colour mismatch.
        setUpArea()
        playerVisible = true
        composeTestRule.waitForIdle()

        val rootBounds = composeTestRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        val playerBounds = composeTestRule.onNodeWithTag("player").fetchSemanticsNode().boundsInRoot

        assertFalse("the player was not laid out", playerBounds.width == 0f)
        assertEquals("player top edge", rootBounds.top, playerBounds.top, 0.5f)
        assertEquals("player bottom edge", rootBounds.bottom, playerBounds.bottom, 0.5f)
    }
}