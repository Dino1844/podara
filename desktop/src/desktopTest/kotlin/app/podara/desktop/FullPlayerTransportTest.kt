package app.podara.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import app.podara.data.AppDatabase
import app.podara.player.FullPlayer
import app.podara.player.MediaPlayerState
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Geometry of the full player's transport row.
 *
 * The speed pill and the two audio-enhancement toggles all sit in the left
 * flank, which once pushed the play button off centre: a plain centered
 * Arrangement centres the whole cluster, so every pill the row gains shifts
 * the transport. The row now weighs both flanks equally, and this test pins
 * the invariant the restructure bought — the play button lands at the row's
 * exact centre whatever sits beside it.
 */
class FullPlayerTransportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val database = AppDatabase.buildInMemory()

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun thePlayButtonSitsAtTheRowsExactCentre() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            composeTestRule.setContent {
                PodaraTheme {
                    FullPlayer(state = state, database = database, onClose = {})
                }
            }
            composeTestRule.waitForIdle()

            // Nothing loaded: the glyph reads "Play" and the transport row is
            // fully rendered — the toggles are clickable with nothing loaded.
            val root = composeTestRule.onRoot().fetchSemanticsNode()
            val play = composeTestRule
                .onNodeWithContentDescription(Strings["player_play"])
                .fetchSemanticsNode()

            val rootCentre = root.positionInRoot.x + root.size.width / 2f
            val playCentre = play.positionInRoot.x + play.size.width / 2f
            // The tolerance covers the vertical scrollbar, which reserves a
            // few pixels of layout width at the column's right edge, plus
            // dp-to-px rounding. A flank-weighting regression moves the
            // button by tens of pixels; that is what this test exists to
            // catch, and it sails far outside this band.
            assertEquals(
                rootCentre,
                playCentre,
                absoluteTolerance = 8f,
                "the play button drifted off centre — the flanks are no longer equally weighted"
            )
        } finally {
            state.release()
        }
    }
}
