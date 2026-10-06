package app.podara.desktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import app.podara.component.ContinueListeningCard
import app.podara.component.formatEpisodeClock
import app.podara.component.progressFraction
import app.podara.component.remainingLabel
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Home "continue listening" card.
 *
 * The pure helpers are asserted on their own because the interesting behaviour
 * lives there: the fraction must clamp (a stale position can sit past the
 * duration after an episode was re-downloaded shorter), zero duration must read
 * as "unknown", never as zero progress, and the remaining label rounds up so a
 * 46:59 remainder does not claim "0 min left".
 *
 * Expected strings are built through [Strings] with the same arguments the
 * component passes, so the tests hold under either configured language.
 */
class ContinueListeningCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val episodeTitle = "Episode 12: The Long Middle"
    private val podcastTitle = "Daily Tech"

    private fun setContent(
        positionMs: Long = 754_000L,
        durationMs: Long = 3_600_000L,
        imageUrl: String? = null,
        onClick: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            PodaraTheme {
                ContinueListeningCard(
                    episodeTitle = episodeTitle,
                    podcastTitle = podcastTitle,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onClick = onClick,
                    imageUrl = imageUrl
                )
            }
        }
    }

    // ── progressFraction ──

    @Test
    fun testProgressFractionIsPlayedShare() {
        assertEquals(0.25f, progressFraction(250_000L, 1_000_000L), 1e-6f)
        assertEquals(0.5f, progressFraction(2_000_000L, 4_000_000L), 1e-6f)
    }

    @Test
    fun testProgressFractionClampsToFullWhenPositionReachesDuration() {
        // position >= duration means the episode is finished.
        assertEquals(1f, progressFraction(4_000_000L, 4_000_000L), 1e-6f)
        assertEquals(1f, progressFraction(8_000_000L, 4_000_000L), 1e-6f)
    }

    @Test
    fun testProgressFractionIsZeroWhenEitherSideIsUnknown() {
        assertEquals(0f, progressFraction(0L, 1_000_000L), 1e-6f)
        assertEquals(0f, progressFraction(-5L, 1_000_000L), 1e-6f)
        assertEquals(0f, progressFraction(500_000L, 0L), 1e-6f)
        assertEquals(0f, progressFraction(500_000L, -1_000L), 1e-6f)
    }

    // ── formatEpisodeClock ──

    @Test
    fun testEpisodeClockUsesMinutesUnderOneHour() {
        assertEquals("0:00", formatEpisodeClock(0L))
        assertEquals("0:34", formatEpisodeClock(34_000L))
        assertEquals("12:34", formatEpisodeClock(754_000L))
        // Sub-second remainders truncate, like the player's own time labels.
        assertEquals("12:34", formatEpisodeClock(754_999L))
    }

    @Test
    fun testEpisodeClockSwitchesToHoursAtOrAboveOneHour() {
        assertEquals("1:00:00", formatEpisodeClock(3_600_000L))
        assertEquals("1:02:03", formatEpisodeClock(3_723_000L))
    }

    // ── remainingLabel ──

    @Test
    fun testRemainingLabelRoundsUpToWholeMinutes() {
        // 46:59 left must not read as "0 min left".
        val rounded = remainingLabel(47 * 60_000L, 1_000L)
        assertEquals(Strings.get("home_continue_time_left_minutes", 47L), rounded)

        val justOverAMinute = remainingLabel(60_001L, 0L)
        assertEquals(Strings.get("home_continue_time_left_minutes", 2L), justOverAMinute)
    }

    @Test
    fun testRemainingLabelMixesHoursAndMinutes() {
        val mixed = remainingLabel(90 * 60_000L, 0L)
        assertEquals(Strings.get("home_continue_time_left_hours", 1L, 30L), mixed)
    }

    @Test
    fun testRemainingLabelShowsWholeHoursWithoutMinutes() {
        val wholeHours = remainingLabel(2 * 3_600_000L, 0L)
        assertEquals(Strings.get("home_continue_time_left_hours_exact", 2L), wholeHours)
    }

    @Test
    fun testRemainingLabelIsZeroMinutesWhenPositionOverrunsDuration() {
        val overrun = remainingLabel(60_000L, 120_000L)
        assertEquals(Strings.get("home_continue_time_left_minutes", 0L), overrun)
    }

    @Test
    fun testRemainingLabelIsEmptyWithoutDuration() {
        assertEquals("", remainingLabel(0L, 754_000L))
    }

    // ── Rendered card ──

    @Test
    fun testRendersBadgeAndTitles() {
        setContent()
        composeTestRule.onNodeWithText(Strings["home_continue_badge"]).assertIsDisplayed()
        composeTestRule.onNodeWithText(episodeTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(podcastTitle).assertIsDisplayed()
    }

    @Test
    fun testRendersLeftOffAtAndRemainingTime() {
        setContent(positionMs = 754_000L, durationMs = 3_600_000L)
        composeTestRule.onNodeWithText(
            Strings.get("home_continue_last_listened", formatEpisodeClock(754_000L))
        ).assertIsDisplayed()
        // 60 min total, 12:34 in: 47:26 rounds up to 48 min left.
        composeTestRule.onNodeWithText(
            Strings.get("home_continue_time_left_minutes", 48L)
        ).assertIsDisplayed()
    }

    @Test
    fun testNoDurationKeepsLeftOffAtButHidesProgressBarTexts() {
        setContent(positionMs = 754_000L, durationMs = 0L)
        composeTestRule.onNodeWithText(
            Strings.get("home_continue_last_listened", formatEpisodeClock(754_000L))
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            Strings.get("home_continue_time_left_minutes", 48L)
        ).assertDoesNotExist()
    }

    @Test
    fun testNoPositionAndNoDurationHideTheWholeTimeRow() {
        setContent(positionMs = 0L, durationMs = 0L)
        composeTestRule.onNodeWithText(
            Strings.get("home_continue_last_listened", "12:34")
        ).assertDoesNotExist()
        // The card itself is still there.
        composeTestRule.onNodeWithText(Strings["home_continue_badge"]).assertIsDisplayed()
    }

    @Test
    fun testClickAnywhereOnTheCardFiresTheCallback() {
        var clicks = 0
        setContent(onClick = { clicks++ })
        composeTestRule.onNodeWithText(episodeTitle).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun testPlayAffordanceFiresTheCallback() {
        var clicks = 0
        setContent(onClick = { clicks++ })
        composeTestRule.onNodeWithContentDescription(Strings["home_continue_play"]).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun testBlankImageUrlStillRendersAllText() {
        // A blank URL takes the placeholder-icon path; the text content must
        // still be present.
        setContent(positionMs = 754_000L, durationMs = 3_600_000L, imageUrl = "  ")
        composeTestRule.onNodeWithText(episodeTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(podcastTitle).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(episodeTitle).assertDoesNotExist()
    }
}
