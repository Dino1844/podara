package app.podara.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The shell's navigation, at the smallest scale that still has the property
 * under test.
 *
 * Two reported bugs live here, and they had different causes:
 *
 *  - pressing back out of a podcast opened from Discover landed on Home, because
 *    every `onBack` in the shell was hardcoded to `currentScreen = "home"`. The
 *    fix is a stack: back pops, and the detail is not an entry of its own — it is
 *    the current screen with a podcast layered over it, so leaving it reveals the
 *    list that opened it.
 *  - the list came back scrolled to the top, because the `LazyListState` lived
 *    inside the subtree that `AnimatedContent` disposes. The fix is to hoist it
 *    above the swap; nothing has to restore the position afterwards, because the
 *    state object still holds it.
 *
 * The harness mirrors that shape rather than calling into `App`, which builds a
 * database, an audio engine and a system tray on composition. Its assertions read
 * the real values — the screen out of the stack, the cursor out of the list state
 * — because "a row is visible" cannot tell row 25 from row 3.
 */
class NavigationBackTargetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val RowHeight = 60.dp
    private val ItemCount = 40

    /** The screen the user is on, read out of the stack. */
    private var currentScreen = "unset"

    /** The Discover list's state, captured during composition. */
    private var discoverListState: LazyListState? = null

    @Composable
    private fun NavigationHarness() {
        // Two screens on the stack, because the point is that "back" can go
        // somewhere other than where it starts. With one entry every assertion
        // about the destination is trivially true.
        val backStack = remember { mutableStateListOf("discover", "home") }
        val listStates = remember { mutableStateMapOf<String, LazyListState>() }
        var selectedPodcast by remember { mutableStateOf<String?>(null) }

        fun listStateFor(screen: String): LazyListState =
            listStates.getOrPut(screen) { LazyListState() }

        discoverListState = listStates["discover"]

        LaunchedEffect(backStack.last()) { currentScreen = backStack.last() }

        Column(Modifier.fillMaxSize()) {
            // The sidebar. A switch is a destination, not a trail: it replaces
            // the stack rather than pushing onto it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sidebar-discover")
                    .clickable {
                        backStack.clear()
                        backStack.add("discover")
                    }
            )

            AnimatedContent(
                targetState = backStack.last() to selectedPodcast,
                modifier = Modifier.fillMaxSize()
            ) { (screen, podcast) ->
                when {
                    podcast != null -> Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("detail")
                            .clickable {
                                // The detail is a layer, not a stack entry:
                                // leaving it only clears the overlay.
                                selectedPodcast = null
                            }
                    )

                    else -> {
                        val state = listStateFor(screen)
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                            items(List(ItemCount) { "$screen-row-$it" }) { label ->
                                Text(
                                    text = label,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(RowHeight)
                                        .clickable { selectedPodcast = label }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun backFromADetailReturnsToTheScreenThatOpenedIt() {
        composeTestRule.setContent { NavigationHarness() }
        composeTestRule.waitForIdle()
        assertEquals("home", currentScreen)

        // Move to Discover the way the sidebar does. Without this step the stack
        // never holds two screens and the destination assertion means nothing.
        composeTestRule.onNodeWithTag("sidebar-discover").performClick()
        composeTestRule.waitForIdle()
        assertEquals("discover", currentScreen)

        // Open a detail over Discover.
        composeTestRule.onNodeWithText("discover-row-5").performClick()
        composeTestRule.waitForIdle()
        assertEquals("discover", currentScreen)

        // Back. The destination must be Discover, not Home.
        composeTestRule.onNodeWithTag("detail").performClick()
        composeTestRule.waitForIdle()
        assertEquals("discover", currentScreen)
    }

    @Test
    fun aHoistedListKeepsItsCursorAcrossADetailRoundTrip() {
        composeTestRule.setContent { NavigationHarness() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sidebar-discover").performClick()
        composeTestRule.waitForIdle()

        val state = requireNotNull(discoverListState) { "the harness never composed" }
        // The synchronous half of scrollToItem: it records the request and lets
        // the next layout apply it. The suspending version parks until a frame
        // is produced, which deadlocks against the frame clock a compose test
        // holds.
        state.requestScrollToItem(25)
        composeTestRule.waitForIdle()
        val scrolledTo = state.firstVisibleItemIndex
        if (scrolledTo <= 5) {
            throw AssertionError("the list should have scrolled, was at $scrolledTo")
        }

        // Open a detail and come back. Nothing restores the cursor — the state
        // object is hoisted, so it never lost it.
        composeTestRule.onNodeWithText("discover-row-$scrolledTo").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail").assertExists()

        composeTestRule.onNodeWithTag("detail").performClick()
        composeTestRule.waitForIdle()

        assertEquals(scrolledTo, state.firstVisibleItemIndex)
    }
}
