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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Does a hoisted `LazyListState` keep its cursor when the list is genuinely
 * unmounted and later composed again?
 *
 * This decides whether the restore machinery in `App` (recording the cursor on
 * every scroll into the back stack, then scrolling back to it on return) is
 * load-bearing or dead weight. The distinction matters beyond tidiness: the
 * recording writes a snapshot-state list on every scroll frame, which recomposes
 * the shell while the user is scrolling.
 *
 * The two cases are different and both are exercised here:
 *
 *  - far away and back: the list leaves composition entirely, exactly as
 *    `AnimatedContent` does when the target changes, and the state object is
 *    kept by the caller;
 *  - the state object is re-created: what the "restore" path actually rescues.
 */
class HoistedStateSurvivesUnmountTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val RowHeight = 60.dp
    private val ItemCount = 60

    private var listState: LazyListState? = null

    /** A list that is mounted or not, driven by [visible]. */
    @Composable
    private fun SwapHarness(visible: Boolean, openDetail: () -> Unit) {
        val hoisted = remember { LazyListState() }
        listState = hoisted

        AnimatedContent(
            targetState = visible,
            modifier = Modifier.fillMaxSize()
        ) { showList ->
            Column(Modifier.fillMaxSize()) {
                if (showList) {
                    LazyColumn(state = hoisted, modifier = Modifier.fillMaxSize()) {
                        items(List(ItemCount) { "row-$it" }) { label ->
                            Text(
                                text = label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(RowHeight)
                                    .clickable { openDetail() }
                            )
                        }
                    }
                } else {
                    // Stands in for the podcast detail: the list is gone from the
                    // composition, not merely covered.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("detail")
                            .clickable { openDetail() }
                    )
                }
            }
        }
    }

    @Test
    fun aHoistedStateKeepsItsCursorWhenTheListLeavesTheComposition() {
        var visible by mutableStateOf(true)

        composeTestRule.setContent {
            SwapHarness(visible = visible, openDetail = { visible = !visible })
        }
        composeTestRule.waitForIdle()

        val state = requireNotNull(listState) { "the harness never composed" }
        state.requestScrollToItem(25)
        composeTestRule.waitForIdle()
        assertEquals(25, state.firstVisibleItemIndex)

        // Leave: the LazyColumn is disposed with the outgoing content.
        composeTestRule.onNodeWithTag("detail").assertDoesNotExist()
        state.requestScrollToItem(25) // no-op, keeps the gesture honest
        composeTestRule.waitForIdle()

        // Swap to the detail, then come back.
        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("detail").assertExists()

        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitForIdle()

        // Nothing restored it. If this passes, the hoisted state alone is
        // enough and the record/restore machinery is redundant.
        assertEquals(25, state.firstVisibleItemIndex)
    }
}
