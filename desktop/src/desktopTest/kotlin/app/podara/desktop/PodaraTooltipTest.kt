package app.podara.desktop

import app.podara.component.PodaraTooltip
import app.podara.theme.PodaraTheme
import app.podara.util.clickableWithoutIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
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
 * A tooltip is allowed to appear on hover and do nothing else.
 *
 * Material's `TooltipBox` is built for touch, where the anchor's own gesture is
 * what summons the bubble, so the wrapper installs pointer handling on the
 * control's slot rather than beside it. This app drives the bubble from hover
 * instead, which means the wrapper has no reason to intercept anything — and a
 * real reason not to: the control under it is the thing the user was reaching
 * for. A tooltip that swallows the click it describes is worse than no tooltip.
 *
 * Two shapes are checked because the app contains both. Most icon buttons carry
 * their own click. The play circle inside `ContinueListeningCard` does not: the
 * card row owns the click, and the wrapper sits *between* the row and the
 * target, which is exactly where an intercepting ancestor would break it.
 */
class PodaraTooltipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun clickOnTheWrappedControlReachesTheControl() {
        var clicks = 0
        composeTestRule.setContent {
            PodaraTheme {
                PodaraTooltip(text = "Maximise") {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("control")
                            .clickableWithoutIndication { clicks++ }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("control").performClick()

        assertEquals(
            "the tooltip wrapper consumed its control's click — the button under a " +
                "tooltip would read as dead",
            1,
            clicks
        )
    }

    @Test
    fun clickInsideAWrappedChildReachesTheAncestorThatOwnsIt() {
        var clicks = 0
        composeTestRule.setContent {
            PodaraTheme {
                Row(modifier = Modifier.clickableWithoutIndication { clicks++ }) {
                    PodaraTooltip(text = "Play") {
                        Box(modifier = Modifier.size(48.dp).testTag("child")) {
                            Text("▸")
                        }
                    }
                }
            }
        }

        // The wrapper merges its subtree into one semantics node, which is fine
        // for a screen reader and invisible to the pointer; the unmerged tree is
        // where the actual target of the click lives.
        composeTestRule.onNodeWithTag("child", useUnmergedTree = true).performClick()

        assertEquals(
            "the wrapper blocked a click aimed at its parent, which is how the " +
                "continue-listening card's play circle would stop resuming",
            1,
            clicks
        )
    }
}
