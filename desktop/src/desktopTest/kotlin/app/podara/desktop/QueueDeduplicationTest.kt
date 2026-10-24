package app.podara.desktop

import app.podara.player.MediaPlayerState
import app.podara.player.QueueItem
import kotlin.test.*

/**
 * Regression cover for the duplicate-queue crash.
 *
 * The queue drawer keys its list on `episodeId` (or `url` when there is none),
 * and Compose throws `IllegalArgumentException: Key was already used` when two
 * items share one — the drawer went blank. Nothing prevented a duplicate: the
 * "add to queue" button had no disable state and `addToQueue` appended blindly,
 * so a double click was enough.
 */
class QueueDeduplicationTest {

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
    fun addToQueueRejectsTheSameEpisodeId() {
        val first = state.addToQueue(
            url = "https://example.com/a.mp3",
            title = "Episode A",
            episodeId = "podcast:episode-a"
        )
        val second = state.addToQueue(
            url = "https://example.com/a.mp3",
            title = "Episode A (again)",
            episodeId = "podcast:episode-a"
        )

        assertTrue(first, "the first add should be accepted")
        assertFalse(second, "a duplicate episodeId must be refused")
        assertEquals(1, state.queue.size)
        assertEquals("Episode A", state.queue[0].title, "the original entry must be kept")
    }

    @Test
    fun addToQueueFallsBackToUrlWhenThereIsNoEpisodeId() {
        // Callers that only have a url (tests, media-key paths) still need the
        // guard: the drawer falls back to the url for its key too.
        val first = state.addToQueue("https://example.com/a.mp3", "Episode A")
        val second = state.addToQueue("https://example.com/a.mp3", "Episode A again")

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, state.queue.size)
    }

    @Test
    fun addToQueueKeepsDistinctEpisodes() {
        state.addToQueue("https://example.com/a.mp3", "A", episodeId = "p:a")
        state.addToQueue("https://example.com/b.mp3", "B", episodeId = "p:b")

        assertEquals(2, state.queue.size)
    }

    @Test
    fun addToQueueKeepsDistinctUrlsWithoutEpisodeId() {
        state.addToQueue("https://example.com/a.mp3", "A")
        state.addToQueue("https://example.com/b.mp3", "B")

        assertEquals(2, state.queue.size)
    }

    @Test
    fun playWithContextCollapsesDuplicateEpisodeIds() {
        // Favorites and History can hold two rows for the same episode, so a
        // context list is allowed to contain a duplicate. The drawer crash was
        // reachable from that data, not only from double-clicking the button.
        val context = listOf(
            QueueItem("https://example.com/a.mp3", "A", episodeId = "p:a"),
            QueueItem("https://example.com/a.mp3", "A duplicate", episodeId = "p:a"),
            QueueItem("https://example.com/b.mp3", "B", episodeId = "p:b")
        )

        state.playWithContext(context = context, targetUrl = "https://example.com/b.mp3")

        assertEquals(2, state.queue.size, "the repeated episode must appear once")
        assertEquals(listOf("p:a", "p:b"), state.queue.map { it.episodeId })
    }

    @Test
    fun everyQueueItemKeepsAUniqueDrawerKey() {
        // The invariant the drawer depends on, checked directly: the fallback
        // expression must match PlayerUI's key exactly.
        state.addToQueue("https://example.com/a.mp3", "A", episodeId = "p:a")
        state.addToQueue("https://example.com/a.mp3", "A again", episodeId = "p:a")
        state.addToQueue("https://example.com/b.mp3", "B")
        state.addToQueue("https://example.com/b.mp3", "B again")
        state.playWithContext(
            context = listOf(
                QueueItem("https://example.com/c.mp3", "C", episodeId = "p:c"),
                QueueItem("https://example.com/c.mp3", "C duplicate", episodeId = "p:c")
            ),
            targetUrl = "https://example.com/c.mp3"
        )

        val keys = state.queue.map { it.episodeId ?: it.url }
        assertEquals(keys.distinct().size, keys.size, "queue keys must be unique: $keys")
    }

    @Test
    fun playKeepsOneQueueEntryWhenTheSameEpisodeHasTwoUrls() {
        // A downloaded copy replaces the stream URL for one episode. play() used
        // to match on url only while addToQueue matched on episodeId, so playing
        // the download of a queued stream appended a second item with the same
        // drawer key — LazyColumn then threw "Key was already used" and the
        // queue drawer went blank.
        val player = MediaPlayerState(FakeAudioEngine())
        try {
            player.addToQueue("https://example.com/stream.mp3", "Episode A", episodeId = "p:a")

            player.play("/downloads/a.mp3", "Episode A", episodeId = "p:a")

            assertEquals(1, player.queue.size, "the downloaded copy must update the queued entry, not append")
            assertEquals(0, player.queueIndex)
            assertEquals(
                "/downloads/a.mp3",
                player.queue[0].url,
                "the queue entry must point at what is actually playing"
            )
            assertEquals(listOf("p:a"), player.queue.map { it.episodeId ?: it.url })
        } finally {
            player.release()
        }
    }

    @Test
    fun playFillsInTheEpisodeIdOfARestoredQueueEntry() {
        // A queue restored from a build that did not store episodeIds has only
        // urls; playing the same url again must upgrade that entry in place.
        val player = MediaPlayerState(FakeAudioEngine())
        try {
            player.addToQueue("https://example.com/a.mp3", "Episode A")

            player.play("https://example.com/a.mp3", "Episode A", episodeId = "p:a")

            assertEquals(1, player.queue.size)
            assertEquals("p:a", player.queue[0].episodeId)
        } finally {
            player.release()
        }
    }

    @Test
    fun playAndAddToQueueAgreeOnWhatIsADuplicate() {
        // Both paths must use one rule, or an episode can enter the queue twice.
        val player = MediaPlayerState(FakeAudioEngine())
        try {
            player.play("https://example.com/a.mp3", "Episode A", episodeId = "p:a")

            assertFalse(
                player.addToQueue("https://example.com/a.mp3", "Episode A", episodeId = "p:a"),
                "addToQueue must recognise an episode that play() already queued"
            )
            assertEquals(1, player.queue.size)
        } finally {
            player.release()
        }
    }
}
