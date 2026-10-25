package app.podara.desktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.podara.data.AppDatabase
import app.podara.player.FullPlayer
import app.podara.player.MediaPlayerState
import app.podara.theme.PodaraTheme
import app.podara.util.Settings
import app.podara.util.Strings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two audio enhancement toggles end to end through the real [FullPlayer],
 * on a [FakeAudioEngine]-backed state: both pills render beside the speed
 * control, clicking one flips the state and pushes the whole (skipSilence,
 * loudnessBoost) pair to the engine, and the labels come from Strings so the
 * lookup doubles as the accessibility name.
 */
class FullPlayerAudioEnhancementsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val database = AppDatabase.buildInMemory()

    private var originalSkipSilence = false
    private var originalLoudnessBoost = false

    @Before
    fun rememberPersistedToggles() {
        originalSkipSilence = Settings.isSkipSilenceEnabled()
        originalLoudnessBoost = Settings.isLoudnessBoostEnabled()
        // Deterministic starting point: a fresh state's constructor push must
        // be the (false, false) pair the first assertion expects.
        Settings.setSkipSilence(false)
        Settings.setLoudnessBoost(false)
    }

    @After
    fun restorePersistedToggles() {
        Settings.setSkipSilence(originalSkipSilence)
        Settings.setLoudnessBoost(originalLoudnessBoost)
        database.close()
    }

    @Test
    fun theTogglesRenderFlipStateAndReachTheEngine() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        composeTestRule.setContent {
            PodaraTheme {
                FullPlayer(state = state, database = database, onClose = {})
            }
        }
        composeTestRule.waitForIdle()

        // The constructor push arrives before any load and carries both flags.
        assertEquals(
            listOf(false to false),
            engine.receivedAudioFilters.toList(),
            "a fresh state must push the persisted pair at construction"
        )

        val skipLabel = Strings["player_skip_silence"]
        val boostLabel = Strings["player_loudness_boost"]

        composeTestRule.onNodeWithContentDescription(skipLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(skipLabel).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(skipLabel).performClick()
        composeTestRule.waitForIdle()
        assertTrue(state.skipSilence, "clicking Skip Silence must flip the state on")
        assertEquals(
            listOf(false to false, true to false),
            engine.receivedAudioFilters.toList(),
            "the engine must receive the whole pair, not a delta"
        )

        composeTestRule.onNodeWithContentDescription(boostLabel).performClick()
        composeTestRule.waitForIdle()
        assertTrue(state.loudnessBoost, "clicking Loudness Boost must flip the state on")
        assertEquals(
            listOf(false to false, true to false, true to true),
            engine.receivedAudioFilters.toList()
        )

        composeTestRule.onNodeWithContentDescription(skipLabel).performClick()
        composeTestRule.waitForIdle()
        assertFalse(state.skipSilence, "clicking again must flip the toggle back off")
        assertEquals(
            listOf(false to false, true to false, true to true, false to true),
            engine.receivedAudioFilters.toList()
        )
    }
}
