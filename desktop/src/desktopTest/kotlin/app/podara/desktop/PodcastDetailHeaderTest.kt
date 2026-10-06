package app.podara.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.podara.screen.PodcastDetailTopBar
import app.podara.screen.rememberScrollOffProgress
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The collapsing podcast-detail header.
 *
 * The header scrolls away as the first item of the episode list, and the top bar
 * crossfades in to take over its context. Two pieces carry the behaviour and both
 * are tested against the real composables:
 *
 *  - [rememberScrollOffProgress] turns the list's scroll position into a 0..1
 *    collapse progress. Tested against a real LazyColumn with real scrolling,
 *    because the math has an easy off-by-one (offset vs. index) that a fake
 *    would never catch.
 *  - [PodcastDetailTopBar] gates its compact actions on that progress. The gate
 *    is structural, not visual: an invisible button still receives clicks, so a
 *    subscribe button the user cannot see must not be clickable. Alpha alone
 *    would have left that hole.
 *
 * What is not tested here is the aesthetic judgement — whether the crossfade
 * *reads* well. That is in VISUAL-CHECKS.md.
 */
class PodcastDetailHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val HeaderHeight = 200.dp
    private val RowHeight = 60.dp

    private lateinit var progress: State<Float>

    private fun setUpList(state: LazyListState = LazyListState(), itemCount: Int = 50) {
        composeTestRule.setContent {
            progress = rememberScrollOffProgress(state)
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                item { Box(Modifier.fillMaxWidth().height(HeaderHeight)) }
                items(List(itemCount) { it }) {
                    Box(Modifier.fillMaxWidth().height(RowHeight))
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    // ── Scroll progress ──

    @Test
    fun progressIsZeroWhenTheHeaderIsFullyVisible() {
        setUpList()
        assertEquals(0f, progress.value, 0.01f)
    }

    @Test
    fun progressTracksTheScrollOffsetWithinTheHeader() {
        val state = LazyListState()
        setUpList(state)

        val halfHeaderPx = with(composeTestRule.density) { (HeaderHeight / 2).toPx().toInt() }
        runBlocking { state.scrollToItem(0, halfHeaderPx) }
        composeTestRule.waitForIdle()

        assertEquals(0.5f, progress.value, 0.05f)
    }

    @Test
    fun progressIsOneOnceTheHeaderIsScrolledPast() {
        val state = LazyListState()
        setUpList(state)

        runBlocking { state.scrollToItem(2) }
        composeTestRule.waitForIdle()

        assertEquals(1f, progress.value, 0.01f)
    }

    @Test
    fun progressIsOneWhileThereIsNoHeader() {
        // Loading and empty states show no header at all; the compact bar must
        // still show the title then, or the page has no context.
        val state = LazyListState()
        composeTestRule.setContent {
            progress = rememberScrollOffProgress(state)
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {}
        }
        composeTestRule.waitForIdle()

        assertEquals(1f, progress.value, 0.01f)
    }

    @Test
    fun progressReturnsToZeroWhenScrolledBackUp() {
        // The crossfade has to run in both directions, or scrolling back up
        // leaves the bar title sitting on top of the header's own title.
        val state = LazyListState()
        setUpList(state)

        runBlocking { state.scrollToItem(3) }
        composeTestRule.waitForIdle()
        assertEquals(1f, progress.value, 0.01f)

        runBlocking { state.scrollToItem(0, 0) }
        composeTestRule.waitForIdle()
        assertEquals(0f, progress.value, 0.01f)
    }

    // ── The top bar ──

    private fun setUpTopBar(collapseProgress: Float, onBack: () -> Unit = {}) {
        composeTestRule.setContent {
            PodaraTheme {
                PodcastDetailTopBar(
                    title = "Test Podcast",
                    collapseProgress = collapseProgress,
                    onBack = onBack
                ) {
                    Box(Modifier.size(20.dp).testTag("action"))
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun actionsAreAbsentWhileTheHeaderIsVisible() {
        // The gate is structural, not visual. A half-transparent button still
        // receives clicks, so the compact subscribe button has to leave the
        // composition entirely until the header is mostly gone — otherwise it
        // answers clicks the user cannot see.
        setUpTopBar(collapseProgress = 0f)

        composeTestRule.onNodeWithTag("action").assertDoesNotExist()
        composeTestRule.onNodeWithText("Test Podcast").assertExists()
    }

    @Test
    fun actionsAppearOnceTheHeaderIsMostlyGone() {
        setUpTopBar(collapseProgress = 1f)

        composeTestRule.onNodeWithTag("action").assertExists()
        composeTestRule.onNodeWithText("Test Podcast").assertExists()
    }

    @Test
    fun actionsAreAbsentPartWayThroughTheCollapse() {
        // Below the threshold the compact row must not exist — this is the
        // ghost-click guard, not a cosmetic choice.
        setUpTopBar(collapseProgress = 0.5f)

        composeTestRule.onNodeWithTag("action").assertDoesNotExist()
    }

    @Test
    fun backButtonWorksRegardlessOfCollapseProgress() {
        var backPressed = false
        setUpTopBar(collapseProgress = 0f, onBack = { backPressed = true })
        composeTestRule.onNodeWithContentDescription(Strings["nav_back"]).performClick()
        assertTrue("back button did not fire while the header was visible", backPressed)

        backPressed = false
        setUpTopBar(collapseProgress = 1f, onBack = { backPressed = true })
        composeTestRule.onNodeWithContentDescription(Strings["nav_back"]).performClick()
        assertTrue("back button did not fire while collapsed", backPressed)
    }
}