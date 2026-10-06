package app.podara.desktop

import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import app.podara.data.AppDatabase
import app.podara.manager.SubscriptionManager
import app.podara.manager.UpdatePodcastResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class SubscriptionManagerRefreshTest {

    private lateinit var database: AppDatabase
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var testDbFile: File

    private val originA = "https://example.com/feed-a.xml"
    private val originB = "https://example.com/feed-b.xml"

    @BeforeTest
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podium_refresh_test_${System.currentTimeMillis()}.db")
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
    fun testRefreshAllWithNoSubscriptionsReturnsEmptySummary() = runBlocking {
        val result = subscriptionManager.refreshAll()

        assertTrue(result.results.isEmpty())
        assertEquals(0, result.newEpisodesCount)
    }

    @Test
    fun testRefreshAllRefreshesEverySubscriptionAndSumsNewEpisodes() = runBlocking {
        subscriptionManager.subscribe(originA)
        subscriptionManager.subscribe(originB)

        val result = subscriptionManager.refreshAll()

        assertEquals(2, result.results.size)
        assertTrue(result.results.all { it.result is UpdatePodcastResult.Updated })
        // The fake feed carries 2 episodes per podcast.
        assertEquals(2, result.results[0].newEpisodesCount)
        assertEquals(2, result.results[1].newEpisodesCount)
        assertEquals(4, result.newEpisodesCount)
        assertEquals(2, database.episodes.getAllByOrigin(originA).size)
        assertEquals(2, database.episodes.getAllByOrigin(originB).size)
    }

    @Test
    fun testRefreshAllContinuesAfterFeedFetchFailure() = runBlocking {
        subscriptionManager.subscribe(originA)
        subscriptionManager.subscribe(originB)
        val flakyClient = object : FetchPodcastClient() {
            private val delegate = FakeFetchPodcastClient()
            override suspend fun fetch(origin: String, lastModified: String, eTag: String): FetchPodcastClientResult {
                if (origin == originA) return FetchPodcastClientResult.Failure(IllegalStateException("feed down"))
                return delegate.fetch(origin, lastModified, eTag)
            }
        }
        subscriptionManager = SubscriptionManager(database, fetchPodcastClient = flakyClient)

        val result = subscriptionManager.refreshAll()

        assertEquals(2, result.results.size, "A failing feed must not abort the remaining feeds")
        val failed = result.results.first { it.origin == originA }
        val updated = result.results.first { it.origin == originB }
        assertTrue(failed.result is UpdatePodcastResult.Error)
        assertEquals(0, failed.newEpisodesCount)
        assertTrue(updated.result is UpdatePodcastResult.Updated)
        assertEquals(2, updated.newEpisodesCount)
        assertEquals(2, result.newEpisodesCount)
        // The failing feed left no partial content behind.
        assertTrue(database.episodes.getAllByOrigin(originA).isEmpty())
    }

    @Test
    fun testRefreshAllRecordsFailureWhenFetchThrows() = runBlocking {
        // updatePodcast propagates fetch exceptions; refreshAll must convert
        // them into per-feed Error results instead of failing the whole run.
        subscriptionManager.subscribe(originA)
        subscriptionManager.subscribe(originB)
        val throwingClient = object : FetchPodcastClient() {
            override suspend fun fetch(origin: String, lastModified: String, eTag: String): FetchPodcastClientResult {
                throw IllegalStateException("network exploded")
            }
        }
        subscriptionManager = SubscriptionManager(database, fetchPodcastClient = throwingClient)

        val result = subscriptionManager.refreshAll()

        assertEquals(2, result.results.size)
        assertTrue(result.results.all { it.result is UpdatePodcastResult.Error })
        assertEquals(0, result.newEpisodesCount)
    }
}
