package app.podara.desktop

import app.podara.player.MediaPlayerState
import kotlin.test.*

/**
 * Regression cover for the keyboard speed shortcut.
 *
 * The shortcut steps through the same preset list the speed menus offer, so a
 * keypress always lands on a value the UI can display and return from.
 */
class PlaybackSpeedShortcutTest {

    private lateinit var state: MediaPlayerState

    @BeforeTest
    fun setup() {
        state = MediaPlayerState()
    }

    @AfterTest
    fun teardown() {
        state.release()
    }

    @Test
    fun presetsStartAtHalfSpeedAndEndAtDouble() {
        assertEquals(0.5f, MediaPlayerState.SPEED_PRESETS.first())
        assertEquals(2.0f, MediaPlayerState.SPEED_PRESETS.last())
        // The UI menus and the shortcut must agree about what a "step" is, so the
        // list is asserted to be strictly increasing with no duplicates.
        assertEquals(
            MediaPlayerState.SPEED_PRESETS.distinct().sorted(),
            MediaPlayerState.SPEED_PRESETS
        )
    }

    @Test
    fun stepUpWalksThePresetsInOrder() {
        assertEquals(1.0f, state.playbackSpeed, "playback starts at 1x")

        state.stepPlaybackSpeed(up = true)
        assertEquals(1.25f, state.playbackSpeed)

        state.stepPlaybackSpeed(up = true)
        assertEquals(1.5f, state.playbackSpeed)
    }

    @Test
    fun stepDownWalksThePresetsInOrder() {
        state.stepPlaybackSpeed(up = false)
        assertEquals(0.75f, state.playbackSpeed)

        state.stepPlaybackSpeed(up = false)
        assertEquals(0.5f, state.playbackSpeed)
    }

    @Test
    fun steppingPastEitherEndClamps() {
        repeat(20) { state.stepPlaybackSpeed(up = false) }
        assertEquals(0.5f, state.playbackSpeed, "cannot go slower than the first preset")

        repeat(40) { state.stepPlaybackSpeed(up = true) }
        assertEquals(2.0f, state.playbackSpeed, "cannot go faster than the last preset")
    }

    @Test
    fun steppingFromAnOffPresetSpeedLandsOnAPreset() {
        // A session restored from an older build can hold a speed that is not in
        // the list; the step must still produce a value the menu can display.
        state.changePlaybackSpeed(1.1f)

        state.stepPlaybackSpeed(up = true)

        assertTrue(
            state.playbackSpeed in MediaPlayerState.SPEED_PRESETS,
            "expected a preset, got ${state.playbackSpeed}"
        )
    }
}
