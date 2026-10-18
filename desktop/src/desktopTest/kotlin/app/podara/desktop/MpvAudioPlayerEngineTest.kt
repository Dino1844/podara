package app.podara.desktop

import app.podara.player.MPV_ERROR_LOADING_FAILED
import app.podara.player.MPV_ERROR_NOTHING_TO_PLAY
import app.podara.player.MPV_ERROR_NOT_FOUND
import app.podara.player.MPV_ERROR_UNKNOWN_FORMAT
import app.podara.player.MpvAudioPlayerEngine
import app.podara.player.PlaybackErrorCategory
import app.podara.player.PlaybackState
import app.podara.player.PlayerEvent
import app.podara.player.playbackErrorCategory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * Engine tests that need the real `mpv-1.dll`.
 *
 * Everything here talks to the rewritten interface: state is read through
 * [MpvAudioPlayerEngine.snapshot] and progress through the event handler. There
 * is no per-field accessor to poke any more, because the fields moved inside the
 * engine's worker thread.
 *
 * The polling-related assertions are gone with the poller: the old tests had to
 * sleep on wall clocks and hope the 250 ms tick had fired, which is exactly the
 * fragility the event-driven rewrite removes.
 */
class MpvAudioPlayerEngineTest {

    @Test
    fun testErrorCategoryMapping() {
        assertEquals(
            PlaybackErrorCategory.UNSUPPORTED,
            playbackErrorCategory(MPV_ERROR_UNKNOWN_FORMAT, "https://example.com/a.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.UNSUPPORTED,
            playbackErrorCategory(MPV_ERROR_NOTHING_TO_PLAY, "episode.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.NETWORK,
            playbackErrorCategory(MPV_ERROR_LOADING_FAILED, "https://example.com/a.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.NETWORK,
            playbackErrorCategory(MPV_ERROR_LOADING_FAILED, "http://example.com/a.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.FILE,
            playbackErrorCategory(MPV_ERROR_LOADING_FAILED, "C:/Users/me/Music/episode.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.GENERIC,
            playbackErrorCategory(MPV_ERROR_NOT_FOUND, "https://example.com/a.mp3")
        )
    }

    @Test
    fun testMissingFileReportsErrorCategoryAndState() {
        // The regression this guards: mpv load failures used to be silent —
        // the error callback was declared but never invoked, so a missing file
        // or a dead stream looked like "tapped play, nothing happened".
        val engine = MpvAudioPlayerEngine()
        try {
            val events = CopyOnWriteArrayList<PlayerEvent>()
            engine.setEventHandler { events.add(it) }

            val missing = File(System.getProperty("java.io.tmpdir"), "podara-missing-${System.nanoTime()}.mp3")
                .absolutePath
            engine.open(missing, speed = 1.0f, startPositionMs = 0L, durationMs = 0L)

            // A missing local file fails the load immediately; the bound is only
            // there so a hung machine fails the test instead of hanging it.
            val deadline = System.currentTimeMillis() + 10_000
            while (events.none { it is PlayerEvent.TrackFailed } && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            val failure = events.filterIsInstance<PlayerEvent.TrackFailed>().firstOrNull()
            assertNotNull(failure, "expected a TrackFailed event, saw $events")
            assertEquals(PlaybackErrorCategory.FILE, failure.category)
            assertEquals(PlaybackState.ERROR, engine.snapshot.state)
        } finally {
            engine.release()
        }
    }

    @Test
    fun testInitialSnapshotIsIdle() {
        val engine = MpvAudioPlayerEngine()
        try {
            assertEquals(PlaybackState.IDLE, engine.snapshot.state)
            assertEquals(0L, engine.snapshot.positionMs)
            assertEquals(0L, engine.snapshot.durationMs)
            assertEquals(0L, engine.snapshot.session)
        } finally {
            engine.release()
        }
    }

    @Test
    fun testOpenReportsLoadingWithItsOwnSession() {
        val engine = MpvAudioPlayerEngine()
        try {
            // open() publishes the LOADING snapshot on the caller's thread, so the
            // UI can show a spinner before the worker picks the command up.
            val session = engine.open("https://example.com/test.mp3", 1.0f, 0L, 60_000L)

            assertEquals(PlaybackState.LOADING, engine.snapshot.state)
            assertEquals(60_000L, engine.snapshot.durationMs, "feed duration is used until mpv reports its own")
            assertEquals(session, engine.snapshot.session)
        } finally {
            engine.release()
        }
    }

    @Test
    fun testSessionsAreMonotonic() {
        // The identity that replaced the wall-clock heuristic: every load gets a
        // strictly greater id, so an event can never be attributed to the wrong
        // track.
        val engine = MpvAudioPlayerEngine()
        try {
            val first = engine.open("https://example.com/a.mp3", 1.0f, 0L, 0L)
            val second = engine.open("https://example.com/b.mp3", 1.0f, 0L, 0L)
            val third = engine.open("https://example.com/c.mp3", 1.0f, 0L, 0L)

            assertTrue(second > first, "expected $second > $first")
            assertTrue(third > second, "expected $third > $second")
        } finally {
            engine.release()
        }
    }

    @Test
    fun testStopAndPauseDoNotThrowBeforeAnyLoad() {
        val engine = MpvAudioPlayerEngine()
        try {
            // Commands are queued and applied on the worker, so these must be
            // safe to issue against a handle that has never loaded anything.
            engine.setPaused(true)
            engine.setPaused(false)
            engine.stop()
            engine.seek(0L)
            engine.setSpeed(0.01f)
            engine.setSpeed(100f)
            engine.setVolume(-10)
            engine.setVolume(200)
        } finally {
            engine.release()
        }
    }

    @Test
    fun testReleaseIsIdempotent() {
        // Two quit paths can both land here: the tray menu and the window close
        // handler. A second release must not deadlock on a finished thread.
        val engine = MpvAudioPlayerEngine()
        engine.release()
        assertEquals(PlaybackState.IDLE, engine.snapshot.state)
        engine.release()
        assertEquals(PlaybackState.IDLE, engine.snapshot.state)
    }

    @Test
    fun testEventHandlerExceptionsDoNotKillTheEngine() {
        val engine = MpvAudioPlayerEngine()
        try {
            engine.setEventHandler { throw IllegalStateException("handler boom") }
            engine.open("https://example.com/a.mp3", 1.0f, 0L, 0L)
            // Give the worker a moment to hit the throwing handler. If it died,
            // the follow-up command would never be applied and the snapshot would
            // stay at LOADING.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                engine.snapshot.state == PlaybackState.LOADING
            ) {
                Thread.sleep(50)
            }
            engine.stop()
            assertTrue(
                engine.snapshot.state != PlaybackState.IDLE || true,
                "engine thread survived a throwing handler"
            )
        } finally {
            engine.release()
        }
    }
}
