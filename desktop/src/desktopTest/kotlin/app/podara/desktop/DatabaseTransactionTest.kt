package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transaction ownership: nesting, rollback, and the read-modify-write pairs.
 *
 * Nesting used to be detected by testing `Connection.autoCommit`, which is a
 * connection-wide JDBC flag being read as if it were call-scoped. Whether a call
 * counted as nested then depended on state somebody else had set, and restoring
 * `autoCommit = true` from the wrong frame commits implicitly. These tests pin
 * the contract that matters instead of the mechanism: a nested call joins the
 * outer transaction, only the owner commits, and an outer failure discards the
 * nested work too.
 */
class DatabaseTransactionTest {

    private lateinit var database: AppDatabase

    @BeforeTest
    fun setup() {
        database = AppDatabase.buildInMemory()
    }

    @AfterTest
    fun teardown() {
        database.close()
    }

    @Test
    fun testNestedTransactionCommitsWithItsOwner() = runBlocking {
        database.transaction {
            podcasts.insert(podcast("feed-a"))
            transaction {
                podcasts.insert(podcast("feed-b"))
            }
        }

        assertEquals(
            listOf("feed-a", "feed-b"),
            database.podcasts.getAllSync().map { it.origin }.sorted()
        )
    }

    @Test
    fun testNestedTransactionRollsBackWithItsOwner() = runBlocking {
        assertFailsWith<IllegalStateException> {
            database.transaction {
                podcasts.insert(podcast("feed-a"))
                transaction {
                    podcasts.insert(podcast("feed-b"))
                }
                error("the caller gives up")
            }
        }

        assertTrue(
            database.podcasts.getAllSync().isEmpty(),
            "a nested write must not survive the outer transaction's rollback"
        )
    }

    @Test
    fun testTogglingAFavouriteInsideARolledBackTransactionLeavesNothing() = runBlocking {
        // toggle() opens its own transaction now, so this only stays correct if
        // nesting is detected properly.
        assertFailsWith<IllegalStateException> {
            database.transaction {
                favorites.toggle(episode("ep-1"))
                error("the caller gives up")
            }
        }

        assertFalse(database.favorites.isFavorite("ep-1"))
    }

    @Test
    fun testToggleFlipsTheFavouriteBothWays() = runBlocking {
        val episode = episode("ep-1")

        assertTrue(database.favorites.toggle(episode), "the first toggle adds it")
        assertTrue(database.favorites.isFavorite("ep-1"))

        assertFalse(database.favorites.toggle(episode), "the second toggle removes it")
        assertFalse(database.favorites.isFavorite("ep-1"))
    }

    @Test
    fun testRecordingProgressAtTheEndMarksTheEpisodeCompleted() = runBlocking {
        // COMPLETION_THRESHOLD is 0.95, so 96 of 100 seconds counts as finished.
        database.progress.record("ep-1", "feed-a", positionMs = 96_000, durationMs = 100_000)

        val progress = database.progress.getById("ep-1")
        assertEquals(true, progress?.completed)
        assertEquals(0L, progress?.positionMs, "a finished episode must not offer to resume near its own end")
    }

    @Test
    fun testCompletedEpisodeStaysCompletedWhenAnEarlierPositionIsRecorded() = runBlocking {
        // The "once finished, always finished" rule is derived from the row that
        // is about to be overwritten, which is exactly why the read and the
        // upsert belong in one transaction.
        database.progress.record("ep-1", "feed-a", positionMs = 96_000, durationMs = 100_000)
        database.progress.record("ep-1", "feed-a", positionMs = 5_000, durationMs = 100_000)

        val progress = database.progress.getById("ep-1")
        assertEquals(true, progress?.completed)
        assertEquals(0L, progress?.positionMs)
    }

    @Test
    fun testProgressStartsAnewWhenTheEpisodeWasNeverPlayed() = runBlocking {
        database.progress.record("ep-1", "feed-a", positionMs = 12_000, durationMs = 100_000)

        val progress = database.progress.getById("ep-1")
        assertEquals(false, progress?.completed)
        assertEquals(12_000L, progress?.positionMs)
    }

    @Test
    fun testRecoveringFromARolledBackTransactionLeavesTheConnectionUsable() = runBlocking {
        // If the depth counter is not reset on the failure path, every later
        // transaction thinks it is nested and nothing is ever committed.
        assertFailsWith<IllegalStateException> {
            database.transaction {
                podcasts.insert(podcast("feed-discarded"))
                error("the caller gives up")
            }
        }

        database.transaction {
            podcasts.insert(podcast("feed-kept"))
        }

        assertNull(database.podcasts.getByOrigin("feed-discarded"))
        assertTrue(database.podcasts.getByOrigin("feed-kept") != null, "a later transaction must commit on its own")
    }

    private fun podcast(origin: String) = app.podara.data.model.Podcast(
        origin = origin,
        link = "https://example.com/$origin",
        title = "Show $origin",
        description = "d",
        author = "a",
        imageUrl = "",
        languageCode = "en"
    )

    private fun episode(id: String) = PodcastEpisode(
        id = id,
        guid = id,
        origin = "feed-a",
        link = "https://example.com/$id",
        title = "Episode $id",
        description = "body",
        author = "a",
        pubDate = 1L,
        duration = 60,
        audioUrl = "https://example.com/$id.mp3",
        podcastTitle = "Show feed-a"
    )
}
