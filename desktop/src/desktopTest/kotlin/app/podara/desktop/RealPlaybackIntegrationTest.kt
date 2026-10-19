package app.podara.desktop

import app.podara.player.AudioPlayerEngine
import app.podara.player.MediaPlayerState
import app.podara.player.MpvAudioPlayerEngine
import app.podara.player.PlaybackState
import app.podara.player.PlayerEvent
import app.podara.player.silentAudioByDefault
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end playback checks against the real `mpv-1.dll`.
 *
 * These exist because the unit tests around the engine all fed it a faked event
 * stream, and the one thing none of them checked was whether the values *inside*
 * an event were the values mpv actually sent. Two structural mistakes survived a
 * fully green suite and shipped as "the player cannot play or seek at all":
 *
 *  1. `MPV_FORMAT_FLAG`/`DOUBLE` were declared as 2 and 4 instead of 3 and 5, so
 *     `mpv_observe_property` rejected `pause` and `eof-reached` outright (-9).
 *     The engine therefore never learned that it had been paused, which is why
 *     clicking play appeared to do nothing: the button stayed on "pause".
 *  2. A property change's `mpv_event.data` is an `mpv_event_property`, not the
 *     value; the value hangs off that struct's own `data` field. Reading the
 *     header as the value yielded 0 forever, so `time-pos` and `duration` were
 *     always 0 — the progress bar never moved and could not be dragged.
 *
 * A test that only counts events sees neither defect. These read the numbers.
 *
 * Every engine here is built with `silent = true`, and the test task also sets
 * `podara.test.silentAudio`, so a run of this suite decodes real audio without
 * playing it out loud. The assertions are unaffected: mpv's `null` output driver
 * decodes exactly like a real device, just without a device.
 */
class RealPlaybackIntegrationTest {

    @Test
    fun testRunsAreSilent() {
        // Guards the guard. The engine reaches the audio device unless it is
        // told not to, and the constructor default is driven by a system
        // property set in build.gradle.kts — so dropping that line, or building
        // an engine that ignores it, would quietly put sound back into every
        // test run. Asserting the property is cheaper than hearing the tone.
        assertTrue(
            silentAudioByDefault(),
            "tests must run with podara.test.silentAudio=true so playback is not audible"
        )
    }

    @Test
    fun silentEnginePlaysAFileAudiblyNowhere() {
        // The `null` output driver still decodes, so this proves the silence
        // switch does not also switch off the property stream the earlier tests
        // depend on: same file, same real position, no device.
        withAudio { file ->
            val engine = MpvAudioPlayerEngine(silent = true)
            try {
                engine.setEventHandler { }
                engine.open(file.absolutePath, speed = 1.0f, startPositionMs = 0L, durationMs = 0L)

                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline && engine.snapshot.durationMs <= 0L) {
                    Thread.sleep(50)
                }
                assertTrue(
                    engine.snapshot.durationMs >= 9_000L,
                    "the silent output must still decode, saw ${engine.snapshot.durationMs}ms"
                )
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun progressReportsRealPositionAndDuration() {
        withAudio { file ->
            val engine = MpvAudioPlayerEngine(silent = true)
            try {
                engine.setEventHandler { }
                engine.open(file.absolutePath, speed = 1.0f, startPositionMs = 0L, durationMs = 0L)

                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline && engine.snapshot.positionMs <= 0L) {
                    Thread.sleep(50)
                }

                val snapshot = engine.snapshot
                assertTrue(
                    snapshot.durationMs >= 9_000L,
                    "duration should come from mpv, saw ${snapshot.durationMs}ms"
                )
                assertTrue(
                    snapshot.positionMs > 0L,
                    "position should advance with playback, saw ${snapshot.positionMs}ms"
                )
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun pauseAndResumeAreReportedAsStateChanges() {
        withAudio { file ->
            val engine = MpvAudioPlayerEngine(silent = true)
            val events = CopyOnWriteArrayList<PlayerEvent>()
            try {
                engine.setEventHandler { events.add(it) }
                engine.open(file.absolutePath, speed = 1.0f, startPositionMs = 0L, durationMs = 0L)
                awaitState(engine, PlaybackState.PLAYING)

                engine.setPaused(true)
                awaitState(engine, PlaybackState.PAUSED)

                engine.setPaused(false)
                awaitState(engine, PlaybackState.PLAYING)

                assertTrue(
                    events.filterIsInstance<PlayerEvent.StateChanged>()
                        .any { it.state == PlaybackState.PAUSED },
                    "a PAUSED state change must reach the listener, saw $events"
                )
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun seekMovesTheReportedPosition() {
        withAudio { file ->
            val engine = MpvAudioPlayerEngine(silent = true)
            try {
                engine.setEventHandler { }
                engine.open(file.absolutePath, speed = 1.0f, startPositionMs = 0L, durationMs = 0L)
                awaitState(engine, PlaybackState.PLAYING)
                Thread.sleep(300)

                engine.seek(6_000L)

                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && engine.snapshot.positionMs < 5_000L) {
                    Thread.sleep(50)
                }
                assertTrue(
                    engine.snapshot.positionMs >= 5_000L,
                    "seek to 6s should move the position, saw ${engine.snapshot.positionMs}ms"
                )
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun endOfTrackIsReportedSoTheQueueCanAdvance() {
        withAudio { file ->
            val engine = MpvAudioPlayerEngine(silent = true)
            val events = CopyOnWriteArrayList<PlayerEvent>()
            try {
                engine.setEventHandler { events.add(it) }
                engine.open(file.absolutePath, speed = 1.0f, startPositionMs = 0L, durationMs = 0L)
                awaitState(engine, PlaybackState.PLAYING)

                // Jump near the end instead of waiting out the file.
                engine.seek(9_400L)

                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline &&
                    events.none { it is PlayerEvent.TrackCompleted }
                ) {
                    Thread.sleep(50)
                }
                assertTrue(
                    events.any { it is PlayerEvent.TrackCompleted },
                    "reaching the end must emit TrackCompleted, saw $events"
                )
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun restoringASessionDoesNotStartPlaying() {
        // restoreSession opens at a saved position and then pauses, so the app
        // comes back with the episode ready but silent. Two things broke that:
        // `open` reset `pause` to "no" after loadfile, and FILE_LOADED announced
        // PLAYING unconditionally — the transport showed a pause icon (and the
        // user heard nothing) for a track that was never started.
        withAudio { file ->
            val engine = MpvAudioPlayerEngine(silent = true)
            val events = CopyOnWriteArrayList<PlayerEvent>()
            try {
                engine.setEventHandler { events.add(it) }
                engine.open(file.absolutePath, speed = 1.0f, startPositionMs = 4_000L, durationMs = 0L)
                engine.setPaused(true)

                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline && engine.snapshot.durationMs <= 0L) {
                    Thread.sleep(50)
                }

                assertEquals(
                    PlaybackState.PAUSED,
                    engine.snapshot.state,
                    "a restored session must come back paused, saw ${engine.snapshot.state} / $events"
                )
                assertTrue(
                    engine.snapshot.positionMs >= 3_000L,
                    "the saved position must be applied, saw ${engine.snapshot.positionMs}ms"
                )
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun replayingTheSameTrackReportsItsEndAgain() {
        // The guard that keeps one EOF from advancing the queue twice was a
        // latch that was never reset, so replaying an episode (press play at the
        // end, or pick it from the queue again) finished silently: no second
        // TrackCompleted, so the queue never moved on.
        withAudio { file ->
            val engine = MpvAudioPlayerEngine(silent = true)
            val events = CopyOnWriteArrayList<PlayerEvent>()
            try {
                engine.setEventHandler { events.add(it) }
                engine.open(file.absolutePath, speed = 1.0f, startPositionMs = 0L, durationMs = 0L)
                awaitState(engine, PlaybackState.PLAYING)

                engine.seek(9_500L)
                awaitCompletions(events, 1)

                // What resume() does when play is pressed at the end of an episode.
                engine.seek(0L)
                Thread.sleep(300)
                engine.setPaused(false)
                Thread.sleep(700)
                engine.seek(9_500L)

                awaitCompletions(events, 2)
                assertEquals(
                    2,
                    events.filterIsInstance<PlayerEvent.TrackCompleted>().size,
                    "the second playthrough must also report completion, saw $events"
                )
            } finally {
                engine.release()
            }
        }
    }

    @Test
    fun stateLayerTogglesPlayPauseAgainstTheRealEngine() {
        withAudio { file ->
            val state = MediaPlayerState()
            try {
                state.play(file.absolutePath, "Integration")
                assertTrue(state.isPlaying, "pressing play must show as playing at once")

                val playingDeadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < playingDeadline && state.currentPosition <= 0L) {
                    Thread.sleep(50)
                }
                assertTrue(state.duration >= 9_000L, "duration should reach the UI, saw ${state.duration}ms")

                state.togglePlayPause()
                Thread.sleep(600)
                assertTrue(!state.isPlaying, "toggling from playing must pause")

                val pausedAt = state.currentPosition
                state.togglePlayPause()
                Thread.sleep(600)
                assertTrue(state.isPlaying, "toggling again must resume, not stay paused")
                assertTrue(
                    state.currentPosition >= pausedAt,
                    "resuming must continue from where it was paused"
                )

                state.seek(2_000L)
                val seekDeadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < seekDeadline && state.currentPosition < 1_500L) {
                    Thread.sleep(50)
                }
                assertTrue(
                    state.currentPosition >= 1_500L,
                    "seek must move the position, saw ${state.currentPosition}ms"
                )
            } finally {
                state.release()
            }
        }
    }

    private fun awaitState(engine: AudioPlayerEngine, expected: PlaybackState) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && engine.snapshot.state != expected) {
            Thread.sleep(50)
        }
        assertEquals(expected, engine.snapshot.state)
    }

    private fun awaitCompletions(events: List<PlayerEvent>, count: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline &&
            events.count { it is PlayerEvent.TrackCompleted } < count
        ) {
            Thread.sleep(50)
        }
    }

    /**
     * Runs [block] against a generated 10-second mono WAV.
     *
     * Synthesised rather than checked in: the test needs a decodable file of a
     * known length, and formats like MP3/OGG would either bloat the repo or
     * depend on a downloadable asset. libmpv decodes PCM WAV natively.
     */
    private fun withAudio(block: (File) -> Unit) {
        val file = File(System.getProperty("java.io.tmpdir"), "podara-test-tone-10s.wav")
        if (!file.exists() || file.length() < 100) writeTone(file)
        try {
            block(file)
        } finally {
            // Left in place: regenerating on the next run is cheap, and keeping
            // it makes a failing CI run inspectable.
        }
    }

    private fun writeTone(file: File, seconds: Int = 10, sampleRate: Int = 8000) {
        val samples = sampleRate * seconds
        val dataBytes = samples * 2
        val out = java.io.ByteArrayOutputStream(44 + dataBytes)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        // RIFF/WAVE is little-endian throughout. DataOutputStream writes shorts
        // big-endian, which produced a header mpv rejected as an unsupported
        // format — every assertion then failed against an engine that never
        // loaded the file at all.
        fun u32(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        ascii("RIFF"); u32(36 + dataBytes)
        ascii("WAVE")
        ascii("fmt "); u32(16)
        u16(1)                       // PCM
        u16(1)                       // mono
        u32(sampleRate)
        u32(sampleRate * 2)          // byte rate
        u16(2)                       // block align
        u16(16)                      // bits per sample
        ascii("data"); u32(dataBytes)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * 440.0 * i / sampleRate) * 8000).toInt()
            u16(v and 0xFFFF)
        }
        file.writeBytes(out.toByteArray())
    }
}
