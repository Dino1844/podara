package app.podara.desktop

import app.podara.player.MediaPlayerState
import app.podara.player.PlaybackErrorCategory
import app.podara.player.PlaybackState
import app.podara.player.PlayerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The buffering flag at the state layer (DESIGN-CRAFT D3), driven through
 * [FakeAudioEngine.emit] the way the real engine's `paused-for-cache`
 * observation would deliver it.
 *
 * The contract being pinned: the flag tracks the event, a new load clears an
 * in-flight stall eagerly, a failure replaces the stall (the two must never
 * read as the same state in the UI), a user pause during a stall drops the
 * flag because mpv does not clear `paused-for-cache` for it, and events for
 * superseded sessions are dropped like every other event.
 */
class BufferingStateTest {

    @Test
    fun bufferingEventsSetAndClearTheFlag() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")

            engine.emit(PlayerEvent.Buffering(session = 1, active = true))
            assertTrue(state.isBuffering)

            engine.emit(PlayerEvent.Buffering(session = 1, active = false))
            assertFalse(state.isBuffering)
        } finally {
            state.release()
        }
    }

    @Test
    fun aNewLoadClearsAnInFlightStall() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            engine.emit(PlayerEvent.Buffering(session = 1, active = true))
            assertTrue(state.isBuffering)

            // The second load claims session 2.
            state.play(url = "https://example.com/2.mp3", title = "Episode 2")
            assertFalse(state.isBuffering, "the new load resets the stall at once")
        } finally {
            state.release()
        }
    }

    @Test
    fun aFailureReplacesTheStall() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            engine.emit(PlayerEvent.Buffering(session = 1, active = true))

            engine.emit(PlayerEvent.TrackFailed(session = 1, category = PlaybackErrorCategory.NETWORK))
            assertFalse(state.isBuffering, "a failure must not keep advertising a stall")
            assertNotNull(state.error)
        } finally {
            state.release()
        }
    }

    @Test
    fun aUserPauseDuringAStallDropsTheFlag() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            engine.emit(PlayerEvent.Buffering(session = 1, active = true))

            engine.emit(PlayerEvent.StateChanged(session = 1, state = PlaybackState.PAUSED))
            assertFalse(state.isBuffering, "mpv does not clear paused-for-cache for a user pause")
        } finally {
            state.release()
        }
    }

    @Test
    fun bufferingEventsForASupersededSessionAreDropped() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            engine.emit(PlayerEvent.Buffering(session = 99, active = true))
            assertFalse(state.isBuffering)
            assertEquals(null, state.error)
        } finally {
            state.release()
        }
    }
}
