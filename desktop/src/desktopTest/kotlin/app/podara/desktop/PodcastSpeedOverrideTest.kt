package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.player.MediaPlayerState
import app.podara.player.QueueItem
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-podcast speed memory at the state layer.
 *
 * A subscription row can pin a speed for one show. [MediaPlayerState] must
 * apply it to every load of that show's episodes (play, context play, queue
 * advance, restored session) through the engine's existing `setSpeed` path —
 * the state layer decides the value, the engine obeys. And a speed change the
 * *user* made must be written back onto the loaded episode's show, while a
 * restore or an applied override must never write back, or the restore would
 * rewrite the row it just read.
 *
 * Assertions read the values the engine actually received
 * ([FakeAudioEngine.receivedSpeeds]) and the (episodeId, speed) pairs the
 * injected writer captured — nothing here touches the real engine.
 */
class PodcastSpeedOverrideTest {

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }

    @Test
    fun testPlayingAnEpisodeAppliesItsPodcastOverrideThroughTheEngine() {
        val engine = FakeAudioEngine()
        val writes = ConcurrentLinkedQueue<Pair<String, Float>>()
        val state = MediaPlayerState(
            player = engine,
            speedOverrideLookup = { episodeId -> if (episodeId == "ep-1") 1.5f else null },
            speedOverrideWriter = { episodeId, speed -> writes.add(episodeId to speed) }
        )
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1", episodeId = "ep-1")

            awaitUntil { state.playbackSpeed == 1.5f }
            assertEquals(1.5f, engine.receivedSpeeds.last(), "the engine must be told the override speed")
            assertTrue(writes.isEmpty(), "applying an override is not a user gesture and must not write back")
        } finally {
            state.release()
        }
    }

    @Test
    fun testPlayingWithoutAnOverrideKeepsTheSessionSpeed() {
        val engine = FakeAudioEngine()
        val lookedUp = CountDownLatch(1)
        val state = MediaPlayerState(
            player = engine,
            // Signal when the override check for this load has finished, so the
            // "nothing happened" assertions below are not racing it.
            speedOverrideLookup = { lookedUp.countDown(); null }
        )
        try {
            state.play(url = "https://example.com/2.mp3", title = "Episode 2", episodeId = "ep-2")

            assertTrue(lookedUp.await(5, TimeUnit.SECONDS), "the override lookup must run for every load")
            assertEquals(1.0f, state.playbackSpeed, "no override means the session speed stands")
            assertTrue(engine.receivedSpeeds.isEmpty(), "no override must not touch the engine speed")
        } finally {
            state.release()
        }
    }

    @Test
    fun testManualSpeedChangeIsPinnedToTheCurrentPodcast() {
        val engine = FakeAudioEngine()
        val writes = ConcurrentLinkedQueue<Pair<String, Float>>()
        val state = MediaPlayerState(
            player = engine,
            speedOverrideLookup = { null },
            speedOverrideWriter = { episodeId, speed -> writes.add(episodeId to speed) }
        )
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1", episodeId = "ep-1")
            state.changePlaybackSpeed(1.75f)

            awaitUntil { writes.isNotEmpty() }
            assertEquals("ep-1" to 1.75f, writes.peek(), "the speed must be pinned to the loaded episode's show")
            assertEquals(1.75f, state.playbackSpeed)
        } finally {
            state.release()
        }
    }

    @Test
    fun testKeyboardStepIsAlsoPinnedToTheCurrentPodcast() {
        val engine = FakeAudioEngine()
        val writes = ConcurrentLinkedQueue<Pair<String, Float>>()
        val state = MediaPlayerState(
            player = engine,
            speedOverrideLookup = { null },
            speedOverrideWriter = { episodeId, speed -> writes.add(episodeId to speed) }
        )
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1", episodeId = "ep-1")
            state.stepPlaybackSpeed(up = true)

            awaitUntil { writes.isNotEmpty() }
            assertEquals("ep-1" to 1.25f, writes.peek(), "the shortcut steps to the next preset and pins it")
        } finally {
            state.release()
        }
    }

    @Test
    fun testManualSpeedChangeWithNothingLoadedWritesNothing() {
        val engine = FakeAudioEngine()
        val writes = ConcurrentLinkedQueue<Pair<String, Float>>()
        val state = MediaPlayerState(
            player = engine,
            speedOverrideWriter = { episodeId, speed -> writes.add(episodeId to speed) }
        )
        try {
            // No episode is loaded, so no show exists to pin the speed to. The
            // guard returns before launching anything, so this is synchronous.
            state.changePlaybackSpeed(1.25f)

            assertTrue(writes.isEmpty(), "a speed change with no episode must not be pinned anywhere")
            assertEquals(listOf(1.25f), engine.receivedSpeeds, "the engine still plays the chosen speed")
        } finally {
            state.release()
        }
    }

    @Test
    fun testQueueAdvancePicksUpEachEpisodeSPOwnOverride() {
        val engine = FakeAudioEngine()
        val secondLookupDone = CountDownLatch(1)
        val state = MediaPlayerState(
            player = engine,
            speedOverrideLookup = { episodeId ->
                if (episodeId == "ep-2") secondLookupDone.countDown()
                if (episodeId == "ep-1") 1.5f else null
            }
        )
        try {
            state.playWithContext(
                context = listOf(
                    QueueItem(url = "https://example.com/1.mp3", title = "Episode 1", episodeId = "ep-1"),
                    QueueItem(url = "https://example.com/2.mp3", title = "Episode 2", episodeId = "ep-2")
                ),
                targetUrl = "https://example.com/1.mp3",
                title = "Episode 1",
                episodeId = "ep-1"
            )
            awaitUntil { state.playbackSpeed == 1.5f }

            // The next show has no override: the session speed stands.
            state.playFromQueue(1)
            assertTrue(secondLookupDone.await(5, TimeUnit.SECONDS), "the next episode's override check must run")
            assertEquals("ep-2", state.currentEpisodeId)
            assertEquals(1.5f, state.playbackSpeed, "no override on the next show keeps the speed as it is")
            assertEquals(listOf(1.5f), engine.receivedSpeeds, "nothing further may reach the engine")
        } finally {
            state.release()
        }
    }

    @Test
    fun testRestoreAppliesThePodcastOverrideWithoutWritingItBack() {
        val engine = FakeAudioEngine()
        val writes = ConcurrentLinkedQueue<Pair<String, Float>>()
        val state = MediaPlayerState(
            player = engine,
            speedOverrideLookup = { episodeId -> if (episodeId == "ep-1") 1.5f else null },
            speedOverrideWriter = { episodeId, speed -> writes.add(episodeId to speed) }
        )
        val database = AppDatabase.buildInMemory()
        val savingState = MediaPlayerState(FakeAudioEngine())
        try {
            runBlocking {
                savingState.play(url = "https://example.com/1.mp3", title = "Episode 1", episodeId = "ep-1")
                savingState.saveSession(database)
                state.restoreSession(database)
            }

            awaitUntil { state.playbackSpeed == 1.5f }
            assertEquals(1.5f, engine.receivedSpeeds.last(), "a resumed show must play at its stored speed")
            assertTrue(writes.isEmpty(), "a restore must not rewrite the override it applied")
        } finally {
            database.close()
            savingState.release()
            state.release()
        }
    }
}
