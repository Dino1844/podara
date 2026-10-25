package app.podara.desktop

import app.podara.player.AudioPlayerEngine
import app.podara.player.MediaPlayerState
import app.podara.player.PlaybackState
import app.podara.player.PlayerEvent
import app.podara.player.PlayerSnapshot
import app.podara.player.QueueItem
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for the concurrent queue mutation (M2).
 *
 * [MediaPlayerState.handleEngineEvent] runs on the engine's worker thread and
 * advances the queue through `playNext()`, while the UI thread mutates the same
 * `SnapshotStateList` from play/addToQueue/removeFromQueue/moveQueueItem/
 * removeSelectedFromQueue. Snapshot isolation protects readers, not two writers:
 * concurrent `add`/`removeAt` on the persistent backing list lost or duplicated
 * entries.
 *
 * These tests deliberately use a fake engine that fires `TrackCompleted` from a
 * real background thread. They are stress tests, so the assertions are the
 * invariants that must hold no matter how the threads interleave: nothing threw,
 * no append was lost, and the drawer's key stays unique.
 */
class QueueConcurrencyTest {

    @Test
    fun concurrentAppendsDoNotLoseQueueItems() {
        val state = MediaPlayerState(RacingAudioEngine())
        val threadCount = 4
        val appendsPerThread = 100
        val failures = ConcurrentLinkedQueue<Throwable>()
        val gate = CountDownLatch(1)
        try {
            val workers = (0 until threadCount).map { thread ->
                startThread("append-$thread", gate, failures) {
                    repeat(appendsPerThread) { index ->
                        state.addToQueue(
                            url = "https://example.com/$thread-$index.mp3",
                            title = "Episode $thread-$index",
                            episodeId = "ep-$thread-$index"
                        )
                    }
                }
            }
            gate.countDown()
            workers.forEach { it.join(30_000L) }

            assertTrue(workers.none { it.isAlive }, "all appending threads must finish")
            assertTrue(failures.isEmpty(), "concurrent appends threw: ${describe(failures)}")
            assertEquals(
                threadCount * appendsPerThread,
                state.queue.size,
                "concurrent appends lost items"
            )
            val keys = state.queue.map { it.episodeId ?: it.url }
            assertEquals(keys.size, keys.distinct().size, "queue keys must stay unique: $keys")
        } finally {
            state.release()
        }
    }

    @Test
    fun endOfTrackRacingUiMutationsStaysConsistent() {
        val engine = RacingAudioEngine()
        val state = MediaPlayerState(engine)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val gate = CountDownLatch(1)
        try {
            state.playWithContext(
                context = (1..8).map { number ->
                    QueueItem(
                        url = "https://example.com/$number.mp3",
                        title = "Episode $number",
                        episodeId = "ep-$number"
                    )
                },
                targetUrl = "https://example.com/1.mp3",
                title = "Episode 1",
                episodeId = "ep-1"
            )

            val workers = listOf(
                // What the mpv worker does on end-of-track: advance the queue.
                startThread("engine-thread", gate, failures) {
                    repeat(150) {
                        engine.completeCurrentTrack()
                        Thread.yield()
                    }
                },
                startThread("append", gate, failures) {
                    repeat(150) { index ->
                        state.addToQueue(
                            url = "https://example.com/new-$index.mp3",
                            title = "New $index",
                            episodeId = "new-$index"
                        )
                    }
                },
                startThread("remove", gate, failures) {
                    repeat(150) { index -> state.removeFromQueue(index % 3) }
                },
                startThread("batch-remove", gate, failures) {
                    repeat(80) { index -> state.removeSelectedFromQueue(setOf(index % 3)) }
                },
                startThread("drag-reorder", gate, failures) {
                    repeat(80) { index -> state.moveQueueItem(index % 2, (index + 1) % 3) }
                }
            )
            gate.countDown()
            workers.forEach { it.join(30_000L) }

            assertTrue(workers.none { it.isAlive }, "all queue-mutating threads must finish")
            assertTrue(failures.isEmpty(), "concurrent queue mutation threw: ${describe(failures)}")

            val keys = state.queue.map { it.episodeId ?: it.url }
            assertEquals(keys.size, keys.distinct().size, "queue keys must stay unique: $keys")
            assertTrue(
                state.queueIndex in -1 until state.queue.size,
                "queueIndex=${state.queueIndex} is outside a queue of ${state.queue.size}"
            )
        } finally {
            state.release()
        }
    }

    private fun startThread(
        name: String,
        gate: CountDownLatch,
        failures: ConcurrentLinkedQueue<Throwable>,
        body: () -> Unit
    ): Thread = Thread({
        try {
            gate.await()
            body()
        } catch (failure: Throwable) {
            failures.add(failure)
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    private fun describe(failures: ConcurrentLinkedQueue<Throwable>): String =
        failures.joinToString("\n") { it.stackTraceToString() }

    /**
     * An engine that records loads and can fire events from any thread.
     *
     * It only does what the state layer relies on: every `open` gets a new
     * session id, `PLAYING` is reported once the load is accepted, and
     * [completeCurrentTrack] delivers end-of-track for the load that is current
     * *at the moment of the call* — which is what lets the test drive the
     * engine thread concurrently with UI mutations.
     */
    private class RacingAudioEngine : AudioPlayerEngine {
        private val currentSession = AtomicLong(0)
        private val handlerRef = AtomicReference<((PlayerEvent) -> Unit)?>(null)

        override var snapshot: PlayerSnapshot = PlayerSnapshot()
            private set

        override fun setEventHandler(handler: (PlayerEvent) -> Unit) {
            handlerRef.set(handler)
        }

        override fun open(session: Long, url: String, speed: Float, startPositionMs: Long, durationMs: Long): Long {
            currentSession.set(session)
            snapshot = PlayerSnapshot(PlaybackState.PLAYING, startPositionMs, durationMs, session)
            emit(PlayerEvent.StateChanged(session, PlaybackState.PLAYING))
            return session
        }

        override fun setPaused(paused: Boolean) {
            snapshot = snapshot.copy(state = if (paused) PlaybackState.PAUSED else PlaybackState.PLAYING)
        }

        override fun stop() {
            snapshot = snapshot.copy(state = PlaybackState.STOPPED)
        }

        override fun seek(positionMs: Long) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun setVolume(volume: Int) = Unit

        override fun setAudioFilters(skipSilence: Boolean, loudnessBoost: Boolean) = Unit

        override fun release() = Unit

        /** Reports a natural end for whatever load is current right now. */
        fun completeCurrentTrack() {
            emit(PlayerEvent.TrackCompleted(currentSession.get()))
        }

        private fun emit(event: PlayerEvent) {
            handlerRef.get()?.invoke(event)
        }
    }
}
