package app.podara.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import app.podara.component.EpisodeListItem
import app.podara.component.FavoriteEpisodeButton
import app.podara.component.rememberRowActionRevealAlpha
import app.podara.data.model.PodcastEpisode
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 (DESIGN-CRAFT.md §5): a row's secondary actions are invisible on an
 * inactive row and fade in on hover **or keyboard focus** — the ask calls the
 * focus half an accessibility requirement, not a nicety.
 *
 * The fade itself is [rememberRowActionRevealAlpha], asserted directly on the
 * alpha for the three input combinations. The row wiring is asserted on a real
 * [EpisodeListItem] by capture: with focus (not the pointer — pointer hover
 * also washes the row background, which would make any pixel change
 * ambiguous) the favourite icon appears inside the button bounds where
 * nothing was drawn before.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class EpisodeRowActionRevealTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── The fade, driven directly ──

    private fun captureAlpha(hovered: Boolean, focused: Boolean): Float {
        val hover = mutableStateOf(hovered)
        val focus = mutableStateOf(focused)
        var latestAlpha = Float.NaN
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PodaraTheme {
                val alpha = rememberRowActionRevealAlpha(
                    isHovered = hover.value,
                    rowHasFocus = focus.value
                )
                // Read in composition so every animation frame recomposes and
                // SideEffect records the settled value.
                val value = alpha.value
                SideEffect { latestAlpha = value }
                Box(Modifier.fillMaxSize())
            }
        }
        composeTestRule.mainClock.advanceTimeBy((DesignTokens.Animation.HoverMs + 100).toLong())
        composeTestRule.waitForIdle()
        return latestAlpha
    }

    @Test
    fun anInactiveRowKeepsItsSecondaryActionsInvisible() {
        assertEquals(
            0f, captureAlpha(hovered = false, focused = false),
            "a row that is neither hovered nor focused must hide its secondary actions"
        )
    }

    @Test
    fun hoverFadesTheSecondaryActionsIn() {
        assertEquals(
            1f, captureAlpha(hovered = true, focused = false),
            "hover must fade the secondary actions fully in"
        )
    }

    @Test
    fun keyboardFocusFadesTheSecondaryActionsIn() {
        assertEquals(
            1f, captureAlpha(hovered = false, focused = true),
            "focus must fade the secondary actions fully in — hover-only reveal is an accessibility regression"
        )
    }

    // ── The row wiring, on a real EpisodeListItem ──

    private fun testEpisode() = PodcastEpisode(
        id = "ep-reveal",
        guid = "guid-reveal",
        origin = "https://example.com/feed.xml",
        link = "https://example.com/ep",
        title = "Reveal Episode",
        description = "Description",
        imageUrl = null,
        author = "Author",
        pubDate = 1000L,
        duration = 300,
        audioUrl = "https://example.com/audio.mp3",
        podcastTitle = "Reveal Podcast"
    )

    private fun setContentWithRow() {
        composeTestRule.setContent {
            PodaraTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    // A wrapper sized to the row, so the hover target's centre
                    // is the row's centre and the pointer lands on the row
                    // rather than somewhere below it.
                    Box(Modifier.testTag("row")) {
                        EpisodeListItem(
                            episode = testEpisode(),
                            podcast = null,
                            isPlaying = false,
                            secondaryText = "Reveal Podcast",
                            tertiaryText = "",
                            onPlay = {},
                            trailingActions = {
                                FavoriteEpisodeButton(isFavorite = false) { }
                            }
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private val favoriteDescription: String
        get() = Strings["episode_add_to_favorites"]

    private fun captureRoot(): ImageBitmap =
        composeTestRule.onNodeWithTag("root").captureToImage()

    /** Pixels that changed between the two captures inside [bounds]. */
    private fun changedPixels(before: ImageBitmap, after: ImageBitmap, bounds: androidx.compose.ui.geometry.Rect): Int {
        val pmBefore = before.toPixelMap()
        val pmAfter = after.toPixelMap()
        val left = bounds.left.toInt().coerceAtLeast(0)
        val top = bounds.top.toInt().coerceAtLeast(0)
        val right = bounds.right.toInt().coerceAtMost(before.width - 1)
        val bottom = bounds.bottom.toInt().coerceAtMost(before.height - 1)
        var changed = 0
        for (x in left..right) {
            for (y in top..bottom) {
                val a = pmBefore[x, y]
                val b = pmAfter[x, y]
                if (kotlin.math.abs(a.red - b.red) > 0.02f ||
                    kotlin.math.abs(a.green - b.green) > 0.02f ||
                    kotlin.math.abs(a.blue - b.blue) > 0.02f
                ) {
                    changed++
                }
            }
        }
        return changed
    }

    @Test
    fun focusingAnActionFadesItInOnTheRealRow() {
        setContentWithRow()
        val before = captureRoot()

        composeTestRule.onNodeWithContentDescription(favoriteDescription).requestFocus()
        composeTestRule.waitForIdle()

        val buttonBounds = composeTestRule
            .onNodeWithContentDescription(favoriteDescription)
            .fetchSemanticsNode()
            .boundsInRoot
        val changed = changedPixels(before, captureRoot(), buttonBounds)
        assertTrue(
            changed > 30,
            "focusing the favourite action must fade it in — $changed pixels changed inside its bounds, expected the icon to appear"
        )
    }

    @Test
    fun hoveringTheRowFadesItsActionsIn() {
        setContentWithRow()
        val before = captureRoot()

        // Hover the middle of the row — the text column, not a button — so the
        // only thing that can appear inside the action bounds is the action
        // itself fading in.
        composeTestRule.onNodeWithTag("row").performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()

        val buttonBounds = composeTestRule
            .onNodeWithContentDescription(favoriteDescription)
            .fetchSemanticsNode()
            .boundsInRoot
        val changed = changedPixels(before, captureRoot(), buttonBounds)
        assertTrue(
            changed > 30,
            "hovering the row must fade the favourite action in — $changed pixels changed inside its bounds"
        )
    }
}
