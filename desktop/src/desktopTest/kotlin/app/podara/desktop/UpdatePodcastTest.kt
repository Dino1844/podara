package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.manager.SubscriptionManager
import app.podara.manager.UpdatePodcastResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.*

class UpdatePodcastTest {

    private lateinit var database: AppDatabase
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var testDbFile: File

    private val origin = "https://example.com/feed.xml"

    @BeforeTest
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podium_update_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
        subscriptionManager = SubscriptionManager(database, fetchPodcastClient = FakeFetchPodcastClient())
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    @Test
    fun testUpdatePodcastReturnsNotSubscribedWhenNoSubscription() = runBlocking {
        val result = subscriptionManager.updatePodcast(origin, null)

        assertTrue(result is UpdatePodcastResult.NotSubscribed)
    }

    @Test
    fun testUpdatePodcastRecordsTheNewEpisodeCountForTheBadge() = runBlocking {
        // `newEpisodes` drove the "new" badge on the subscription card but had no
        // writer, so the badge could never appear. The refresh now stores it.
        subscriptionManager.subscribe(origin)
        assertEquals(0, database.subscriptions.getByOriginSync(origin)!!.newEpisodes)

        subscriptionManager.updatePodcast(origin, null)

        assertEquals(
            2,
            database.subscriptions.getByOriginSync(origin)!!.newEpisodes,
            "the badge count should match the episodes this refresh added"
        )
    }

    @Test
    fun testSecondRefreshWithNoNewEpisodesClearsTheBadge() = runBlocking {
        subscriptionManager.subscribe(origin)
        subscriptionManager.updatePodcast(origin, null)
        assertEquals(2, database.subscriptions.getByOriginSync(origin)!!.newEpisodes)

        // Same feed again: nothing new, so the badge must drop back to zero
        // rather than staying at the previous value.
        val second = subscriptionManager.updatePodcast(origin, null)
        assertTrue(second is UpdatePodcastResult.Updated)
        assertEquals(0, (second as UpdatePodcastResult.Updated).newEpisodesCount)
        assertEquals(0, database.subscriptions.getByOriginSync(origin)!!.newEpisodes)
    }

    @Test
    fun testClearNewEpisodeCountResetsTheBadge() = runBlocking {
        // Opening a show is the acknowledgement that clears its badge.
        subscriptionManager.subscribe(origin)
        subscriptionManager.updatePodcast(origin, null)
        assertEquals(2, database.subscriptions.getByOriginSync(origin)!!.newEpisodes)

        database.subscriptions.clearNewEpisodeCount(origin)

        assertEquals(0, database.subscriptions.getByOriginSync(origin)!!.newEpisodes)
    }

    @Test
    fun testClearNewEpisodeCountDoesNotTouchOtherSubscriptions() = runBlocking {
        val otherOrigin = "https://example.com/other.xml"
        subscriptionManager.subscribe(origin)
        subscriptionManager.subscribe(otherOrigin)
        database.subscriptions.setNewEpisodeCount(origin, 3)
        database.subscriptions.setNewEpisodeCount(otherOrigin, 5)

        database.subscriptions.clearNewEpisodeCount(origin)

        assertEquals(0, database.subscriptions.getByOriginSync(origin)!!.newEpisodes)
        assertEquals(5, database.subscriptions.getByOriginSync(otherOrigin)!!.newEpisodes)
    }

    @Test
    fun testSetNewEpisodeCountClampsNegativeValues() = runBlocking {
        // The badge count is rendered as a string; a negative would read as "-1".
        subscriptionManager.subscribe(origin)

        database.subscriptions.setNewEpisodeCount(origin, -4)

        assertEquals(0, database.subscriptions.getByOriginSync(origin)!!.newEpisodes)
    }

    @Test
    fun testUpdatePodcastFetchesAndInsertsEpisodes() = runBlocking {
        subscriptionManager.subscribe(origin)
        assertEquals(0L, database.subscriptions.getByOriginSync(origin)?.lastUpdate)

        val result = subscriptionManager.updatePodcast(origin, null)

        assertTrue(result is UpdatePodcastResult.Updated)
        assertEquals(2, (result as UpdatePodcastResult.Updated).newEpisodesCount)

        val episodes = database.episodes.getAllByOrigin(origin)
        assertEquals(2, episodes.size)
        assertTrue(database.subscriptions.getByOriginSync(origin)!!.lastUpdate > 0L)
    }

    @Test
    fun testUpdatePodcastHandlesDuplicateEpisodes() = runBlocking {
        subscriptionManager.subscribe(origin)

        // First call inserts episodes
        val firstResult = subscriptionManager.updatePodcast(origin, null)
        assertTrue(firstResult is UpdatePodcastResult.Updated)
        assertEquals(2, (firstResult as UpdatePodcastResult.Updated).newEpisodesCount)

        database.subscriptions.updateLastUpdate(origin, 0L)

        // Second call should not insert duplicates
        val secondResult = subscriptionManager.updatePodcast(origin, null)
        assertTrue(secondResult is UpdatePodcastResult.Updated)
        assertEquals(0, (secondResult as UpdatePodcastResult.Updated).newEpisodesCount,
            "Repeated update should not insert duplicate episodes")

        val episodes = database.episodes.getAllByOrigin(origin)
        assertEquals(2, episodes.size, "Total episodes should still be 2")
        assertTrue(database.subscriptions.getByOriginSync(origin)!!.lastUpdate > 0L)
    }

    @Test
    fun testUpdatePodcastUpdatesExistingPodcastAndEpisodeMetadata() = runBlocking {
        subscriptionManager.subscribe(origin)
        subscriptionManager.updatePodcast(origin, null)

        subscriptionManager = SubscriptionManager(
            database,
            fetchPodcastClient = FakeFetchPodcastClient(
                podcastTitle = "Updated Fake Podcast",
                episode1Title = "Updated Episode 1",
                episode1AudioUrl = "https://example.com/audio1-updated.mp3"
            )
        )

        val result = subscriptionManager.updatePodcast(origin, null)

        assertTrue(result is UpdatePodcastResult.Updated)
        assertEquals(0, result.newEpisodesCount)
        assertEquals("Updated Fake Podcast", database.podcasts.getByOrigin(origin)?.title)

        val episode = database.episodes.getById("$origin:ep-1")
        assertNotNull(episode)
        assertEquals("Updated Episode 1", episode.title)
        assertEquals("https://example.com/audio1-updated.mp3", episode.audioUrl)
        assertEquals("Updated Fake Podcast", episode.podcastTitle)
    }

    @Test
    fun testUpdatePodcastRollsBackContentAndCacheWhenEpisodeInsertFails() = runBlocking {
        subscriptionManager.subscribe(origin)
        database.subscriptions.updateCache(origin, "old-etag", "old-modified", "old-length")
        database.subscriptions.updateLastUpdate(origin, 123L)
        createFailingEpisodeInsertTrigger("$origin:ep-2")

        assertFailsWith<Exception> {
            subscriptionManager.updatePodcast(origin, null)
        }

        assertNull(database.podcasts.getByOrigin(origin))
        assertTrue(database.episodes.getAllByOrigin(origin).isEmpty())
        val subscription = assertNotNull(database.subscriptions.getByOriginSync(origin))
        assertEquals(123L, subscription.lastUpdate)
        assertEquals("old-etag", subscription.cacheETag)
        assertEquals("old-modified", subscription.cacheLastModified)
        assertEquals("old-length", subscription.cacheContentLength)
    }

    private fun createFailingEpisodeInsertTrigger(episodeId: String) {
        DriverManager.getConnection("jdbc:sqlite:${testDbFile.absolutePath}").use { conn ->
            conn.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TRIGGER fail_episode_insert BEFORE INSERT ON podcastEpisode
                    WHEN NEW.id = '$episodeId'
                    BEGIN SELECT RAISE(FAIL, 'forced episode insert failure'); END
                    """.trimIndent()
                )
            }
        }
    }
}
