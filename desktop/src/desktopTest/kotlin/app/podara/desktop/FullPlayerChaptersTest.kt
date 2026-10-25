package app.podara.desktop

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.podara.data.AppDatabase
import app.podara.player.FullPlayer
import app.podara.player.MediaPlayerState
import app.podara.player.MpvChapter
import app.podara.player.PlayerEvent
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chapter feature end to end through the real [FullPlayer], on a
 * [FakeAudioEngine]-backed state: the entry pill exists only while the loaded
 * file has chapters, opening it renders the list mpv reported, the current
 * chapter carries the selected semantics, and clicking a row seeks the engine
 * to that chapter's start.
 */
class FullPlayerChaptersTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val database = AppDatabase.buildInMemory()

    @After
    fun teardown() {
        database.close()
    }

    private val chapters = listOf(
        MpvChapter("Intro", 0L),
        MpvChapter("The middle bit", 61_500L),
        MpvChapter("Outro", 1_800_000L)
    )

    private fun showPlayer(state: MediaPlayerState) {
        composeTestRule.setContent {
            PodaraTheme {
                FullPlayer(
                    state = state,
                    database = database,
                    onClose = {}
                )
            }
        }
    }

    @Test
    fun noChaptersMeansNoEntry() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        showPlayer(state)

        composeTestRule.runOnIdle {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            // The engine reports the file's (empty) chapter list on FILE_LOADED.
            engine.emit(PlayerEvent.ChaptersLoaded(session = 1, chapters = emptyList()))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(Strings["player_chapters"])
            .assertDoesNotExist()
    }

    @Test
    fun chapterListRendersClicksSeekAndMarksTheCurrentChapter() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        showPlayer(state)

        composeTestRule.runOnIdle {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            engine.emit(PlayerEvent.ChaptersLoaded(session = 1, chapters = chapters))
            engine.emit(PlayerEvent.ChapterChanged(session = 1, index = 1))
        }
        composeTestRule.waitForIdle()

        // The entry, then the list it opens.
        composeTestRule.onNodeWithContentDescription(Strings["player_chapters"]).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(Strings["player_chapters"]).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Intro").assertIsDisplayed()
        composeTestRule.onNodeWithText("Outro").assertIsDisplayed()
        // "Selected" is the accessibility language for the current chapter, and
        // what the row paints its navActive pill from. Read off the row's merged
        // semantics node: onNodeWithText resolves to it because the row merges
        // its children.
        val currentRow = composeTestRule.onNodeWithText("The middle bit").fetchSemanticsNode()
        assertTrue(
            currentRow.config.contains(SemanticsProperties.Selected),
            "the current chapter's row must carry the selected semantics"
        )

        // A row click seeks to that chapter's start — read back from the engine,
        // not from the state, so the whole path (panel → state.seek → engine) is
        // exercised.
        composeTestRule.onNodeWithText("Outro").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            1_800_000L,
            engine.snapshot.positionMs,
            "clicking a chapter must seek the engine to its start"
        )
    }
}
