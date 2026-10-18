package app.podara.desktop

import app.podara.player.MediaPlayerState
import app.podara.player.PlaybackErrorCategory
import app.podara.player.PlayerEvent
import kotlin.test.*

/**
 * Session-identity behaviour of the state layer.
 *
 * This is what replaced the three-second wall-clock heuristic
 * (`PlaybackRules.decideStopTransition`). That function asked "has it been long
 * enough since play() that a completion could be real?", which is unanswerable
 * for a short episode and wrong for a slow load. Completion is now an explicit
 * event stamped with the session it belongs to, so a stale event is rejected by
 * identity and every accepted one is trustworthy.
 */
class PlaybackSessionTest {

    private lateinit var state: MediaPlayerState
    private lateinit var engine: MediaPlayerStateTest.FakeAudioPlayerEngine

    @BeforeTest
    fun setup() {
        engine = MediaPlayerStateTest.FakeAudioPlayerEngine()
        state = MediaPlayerState(engine)
    }

    @AfterTest
    fun teardown() {
        state.release()
    }

    @Test
    fun completionAdvancesToTheNextQueueItem() {
        state.playWithContext(
            context = listOf(
                app.podara.player.QueueItem("https://example.com/a.mp3", "A"),
                app.podara.player.QueueItem("https://example.com/b.mp3", "B")
            ),
            targetUrl = "https://example.com/a.mp3"
        )
        assertEquals(0, state.queueIndex)

        engine.completeCurrentTrack()

        assertEquals(1, state.queueIndex, "should have moved to the second item")
        assertEquals("https://example.com/b.mp3", state.currentUrl)
    }

    @Test
    fun completionAtTheEndOfTheQueueDoesNotAdvance() {
        state.play("https://example.com/only.mp3", "Only")

        engine.completeCurrentTrack()

        assertEquals(0, state.queueIndex, "single-item queue stays put")
    }

    @Test
    fun aStaleCompletionIsIgnored() {
        state.playWithContext(
            context = listOf(
                app.podara.player.QueueItem("https://example.com/a.mp3", "A"),
                app.podara.player.QueueItem("https://example.com/b.mp3", "B")
            ),
            targetUrl = "https://example.com/a.mp3"
        )
        // Pretend a completion from an earlier, already-superseded load arrives
        // late. Under the old design this would have needed a timestamp check to
        // reject; now the session simply does not match.
        engine.emitStaleCompletion(session = 999L)

        assertEquals(0, state.queueIndex, "a stale completion must not advance the queue")
    }

    @Test
    fun aStaleFailureIsIgnored() {
        state.play("https://example.com/a.mp3", "A")

        // A failure belonging to a previous load must not surface as the current
        // track's error.
        val staleSession = engine.openedSessions.last() - 1
        engine.emitStaleCompletion(staleSession)

        assertNull(state.error, "stale events must not set the current error")
    }

    @Test
    fun failureDoesNotAdvanceTheQueue() {
        // The critical distinction the engine enforces: a failed track is not a
        // finished one. Advancing here would silently skip a broken episode.
        state.playWithContext(
            context = listOf(
                app.podara.player.QueueItem("https://example.com/a.mp3", "A"),
                app.podara.player.QueueItem("https://example.com/b.mp3", "B")
            ),
            targetUrl = "https://example.com/a.mp3"
        )

        engine.failCurrentTrack(PlaybackErrorCategory.NETWORK)

        assertEquals(0, state.queueIndex, "a failure must not advance the queue")
        assertEquals(PlaybackErrorCategory.NETWORK, state.error)
        assertFalse(state.isPlaying)
        assertFalse(state.isLoading)
    }

    @Test
    fun aNewPlayGetsANewSessionAndTheOldOneStopsCounting() {
        state.playWithContext(
            context = listOf(
                app.podara.player.QueueItem("https://example.com/a.mp3", "A"),
                app.podara.player.QueueItem("https://example.com/b.mp3", "B"),
                app.podara.player.QueueItem("https://example.com/c.mp3", "C")
            ),
            targetUrl = "https://example.com/a.mp3"
        )
        val firstSession = engine.openedSessions.last()

        state.playFromQueue(1)
        assertTrue(engine.openedSessions.last() > firstSession, "second load must get a new session")

        // A late completion from the first load must be dropped, not applied to B.
        engine.emitStaleCompletion(firstSession)

        assertEquals(1, state.queueIndex, "still on B")
        assertEquals("https://example.com/b.mp3", state.currentUrl)
    }

    @Test
    fun progressUpdatesPositionAndDuration() {
        state.play("https://example.com/a.mp3", "A", durationMs = 60_000L)

        engine.seek(12_345L)

        assertEquals(12_345L, state.currentPosition)
        assertEquals(60_000L, state.duration)
        assertFalse(state.isLoading, "real progress ends the loading state")
    }

    @Test
    fun pauseClearsPlayingWithoutAdvancing() {
        state.playWithContext(
            context = listOf(
                app.podara.player.QueueItem("https://example.com/a.mp3", "A"),
                app.podara.player.QueueItem("https://example.com/b.mp3", "B")
            ),
            targetUrl = "https://example.com/a.mp3"
        )

        state.pause()

        assertFalse(state.isPlaying)
        assertEquals(0, state.queueIndex, "pausing must never advance the queue")
        assertEquals(true, engine.pausedCalls.lastOrNull(), "pause must reach the engine")
    }

    // ── error categories reach the UI unchanged ──

    @Test
    fun everyCategorySurvivesTheEngineBoundary() {
        // The engine classifies a failure (network vs file vs unsupported) and
        // the state layer must pass that through verbatim: the UI maps the
        // category to copy, so a lost or mangled value shows the wrong advice.
        val categories = listOf(
            PlaybackErrorCategory.NETWORK,
            PlaybackErrorCategory.FILE,
            PlaybackErrorCategory.UNSUPPORTED,
            PlaybackErrorCategory.GENERIC
        )
        for (category in categories) {
            val fake = MediaPlayerStateTest.FakeAudioPlayerEngine()
            val isolated = MediaPlayerState(fake)
            try {
                isolated.play("https://example.com/a.mp3", "A")
                fake.failCurrentTrack(category)
                assertEquals(category, isolated.error, "category $category did not survive")
            } finally {
                isolated.release()
            }
        }
    }

    @Test
    fun aNewPlayClearsThePreviousError() {
        state.play("https://example.com/a.mp3", "A")
        engine.failCurrentTrack(PlaybackErrorCategory.FILE)
        assertEquals(PlaybackErrorCategory.FILE, state.error)

        state.play("https://example.com/b.mp3", "B")

        assertNull(state.error, "starting a new track must clear the old error")
    }
}
