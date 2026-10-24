package app.podara.desktop

import app.podara.api.apple.ApplePodcastClient
import app.podara.api.model.PodcastPreviewModel
import app.podara.data.AppDatabase
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import app.podara.manager.AddPodcastResult
import app.podara.manager.PodcastManager
import app.podara.manager.SubscriptionManager
import app.podara.manager.UpdatePodcastResult
import app.podara.util.artworkSeedFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.*

class PodcastManagerTest {

    private lateinit var database: AppDatabase
    private lateinit var manager: PodcastManager
    private lateinit var testDbFile: File

    /**
     * A URL that cannot resolve, on purpose.
     *
     * These tests exercise what `addPodcast` does when the feed cannot be
     * fetched, so the fetch has to fail — but it does not have to fail *slowly*.
     * The previous value was a real `example.com` subdomain, so every test did a
     * genuine DNS lookup and TCP connect before getting its 404, which measured
     * 0.2 to 1.1 seconds each in the test log. `.invalid` is reserved by
     * RFC 2606 and guaranteed not to resolve, so the same failure arrives
     * without leaving the machine.
     */
    private val fakeOrigin = "https://fake-podcast.invalid/feed.xml"

    @BeforeTest
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podium_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
        manager = PodcastManager(database, fetchPodcastClient = FakeFetchPodcastClient())
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    @Test
    fun testAddPodcastCreatesNewPodcast() = runBlocking {
        val result = manager.addPodcast(fakeOrigin, null)

        assertTrue(result is AddPodcastResult.Created)
        val podcast = (result as AddPodcastResult.Created).podcast
        assertNotNull(podcast.title)
        assertTrue(podcast.title.isNotEmpty())
    }

    @Test
    fun testAddPodcastDuplicateReturnsDuplicate() = runBlocking {
        val firstResult = manager.addPodcast(fakeOrigin, null)
        assertTrue(firstResult is AddPodcastResult.Created)

        val secondResult = manager.addPodcast(fakeOrigin, null)
        assertTrue(secondResult is AddPodcastResult.Duplicate)
    }

    @Test
    fun testAddPodcastRepairsAKnownPodcastThatHasNoSubscriptionRow() = runBlocking {
        // The state the old bug left behind: a `podcast` row with no
        // `podcastSubscription` row, so refreshAll never picked it up and the
        // detail screen drew a Subscribe button for a show already in the
        // library. Re-importing it must repair that, not report Duplicate and
        // move on — otherwise an OPML import can never fix the library.
        val known = Podcast(
            origin = fakeOrigin,
            link = "",
            title = "Known but unsubscribed",
            description = "",
            author = "",
            imageUrl = "",
            languageCode = ""
        )
        database.podcasts.insert(known)
        assertNull(database.subscriptions.getByOriginSync(fakeOrigin))

        val result = manager.addPodcast(fakeOrigin, null)

        val subscription = database.subscriptions.getByOriginSync(fakeOrigin)
        assertNotNull(
            subscription,
            "re-adding a known show should leave it subscribed"
        )
        assertTrue(result is AddPodcastResult.Duplicate)
    }

    @Test
    fun testAddPodcastSavesToDatabase() = runBlocking {
        manager.addPodcast(fakeOrigin, null)

        val podcasts = database.podcasts.getAllSync()
        assertEquals(1, podcasts.size)
        assertEquals(fakeOrigin, podcasts[0].origin)
    }

    @Test
    fun testAddPodcastSavesEpisodes() = runBlocking {
        manager.addPodcast(fakeOrigin, null)

        val episodes = database.episodes.getAllByOrigin(fakeOrigin)
        assertEquals(2, episodes.size)
        val titles = episodes.map { it.title }.toSet()
        assertTrue(titles.containsAll(setOf("Episode 1", "Episode 2")))
    }

    @Test
    fun testAddInvalidUrlStillWorks() = runBlocking {
        val result = manager.addPodcast("https://invalid-url.invalid/feed.xml", null)
        assertTrue(result is AddPodcastResult.Created)
    }

    @Test
    fun testAddPodcastFromItunesLookupStoresRssUrlAsOrigin() = runBlocking {
        val feedUrl = "https://example.com/feed.xml"
        val lookupManager = PodcastManager(database, fetchPodcastClient = FakeFetchPodcastClient(), appleClient = fakeAppleClient(feedUrl))

        val result = lookupManager.addPodcast("itunes-lookup:123456", null)

        assertTrue(result is AddPodcastResult.Created)
        assertEquals(feedUrl, (result as AddPodcastResult.Created).podcast.origin)
        assertEquals(1, database.podcasts.getAllSync().size)
        assertEquals(feedUrl, database.podcasts.getAllSync()[0].origin)
    }

    @Test
    fun testAddPodcastDuplicateAcrossLookupAndPreviewEntries() = runBlocking {
        val feedUrl = "https://example.com/feed.xml"
        val lookupManager = PodcastManager(database, fetchPodcastClient = FakeFetchPodcastClient(), appleClient = fakeAppleClient(feedUrl))

        val first = lookupManager.addPodcast("itunes-lookup:123456", null)
        assertTrue(first is AddPodcastResult.Created)

        // The same show arriving through the preview entry must not create a second row.
        val preview = PodcastPreviewModel(
            fetchUrl = feedUrl,
            link = "https://example.com",
            title = "Fake Podcast",
            description = "",
            author = "",
            imageUrl = "",
            languageCode = "en"
        )
        val second = lookupManager.addPodcastFromPreview(preview, null)
        assertTrue(second is AddPodcastResult.Duplicate)
        assertEquals(1, database.podcasts.getAllSync().size)
    }

    @Test
    fun testAddPodcastDuplicateWhenRssUrlSubscribedFirst() = runBlocking {
        val feedUrl = "https://example.com/feed.xml"
        val lookupManager = PodcastManager(database, fetchPodcastClient = FakeFetchPodcastClient(), appleClient = fakeAppleClient(feedUrl))

        val first = lookupManager.addPodcast(feedUrl, null)
        assertTrue(first is AddPodcastResult.Created)

        val second = lookupManager.addPodcast("itunes-lookup:123456", null)
        assertTrue(second is AddPodcastResult.Duplicate)
        assertEquals(1, database.podcasts.getAllSync().size)
    }

    @Test
    fun testAddPodcastRecognizesLegacyItunesLookupOrigin() = runBlocking {
        // Older versions stored the raw itunes-lookup: key as origin; those rows
        // must still be recognized as duplicates instead of being re-subscribed.
        database.podcasts.insert(
            Podcast(
                origin = "itunes-lookup:123456",
                link = "https://example.com",
                title = "Old Subscription",
                description = "",
                author = "",
                imageUrl = "",
                languageCode = "en"
            )
        )

        val lookupManager = PodcastManager(database, fetchPodcastClient = FakeFetchPodcastClient(), appleClient = fakeAppleClient("https://example.com/feed.xml"))
        val result = lookupManager.addPodcast("itunes-lookup:123456", null)

        assertTrue(result is AddPodcastResult.Duplicate)
        assertEquals("Old Subscription", (result as AddPodcastResult.Duplicate).duplicate.title)
        assertEquals(1, database.podcasts.getAllSync().size)
    }

    @Test
    fun testAddPodcastCreatesSubscriptionRow() = runBlocking {
        manager.addPodcast(fakeOrigin, null)

        assertNotNull(
            database.subscriptions.getByOriginSync(fakeOrigin),
            "addPodcast must write the subscription row: refreshAll() iterates that table"
        )
        Unit
    }

    @Test
    fun testPodcastAddedByAddPodcastIsPickedUpByRefreshAll() = runBlocking {
        // OPML import and the "+" paste-URL dialog both go through addPodcast.
        // Before the subscription row was written, every such show was silently
        // frozen: refreshAll() only walks podcastSubscription.
        manager.addPodcast(fakeOrigin, null)

        val refresher = SubscriptionManager(database, fetchPodcastClient = FakeFetchPodcastClient())
        val result = refresher.refreshAll()

        assertEquals(listOf(fakeOrigin), result.results.map { it.origin })
        assertTrue(
            result.results.single().result is UpdatePodcastResult.Updated,
            "a podcast added by URL should refresh like any other subscription"
        )
    }

    @Test
    fun testAddedPodcastGetsAStableColorSeedDerivedFromItsOrigin() = runBlocking {
        manager.addPodcast(fakeOrigin, null)
        val first = assertNotNull(database.podcasts.getByOrigin(fakeOrigin)).imageSeedColor

        assertEquals(artworkSeedFor(fakeOrigin), first, "the seed should be a hash of the feed URL")
        assertTrue(
            first != 0,
            "0 is the 'no seed recorded' sentinel; every podcast would share the placeholder art"
        )

        // Removing and re-adding the same feed must land on the same colour:
        // this has to be stable across restarts, not random per insert.
        database.podcasts.delete(fakeOrigin)
        manager.addPodcast(fakeOrigin, null)
        assertEquals(
            first,
            assertNotNull(database.podcasts.getByOrigin(fakeOrigin)).imageSeedColor,
            "the seed must not change between adds of the same feed"
        )
        Unit
    }

    @Test
    fun testDifferentFeedsGetDifferentColorSeeds() = runBlocking {
        val otherOrigin = "https://another-show.invalid/feed.xml"
        manager.addPodcast(fakeOrigin, null)
        manager.addPodcast(otherOrigin, null)

        val seeds = listOf(fakeOrigin, otherOrigin).map { origin ->
            assertNotNull(database.podcasts.getByOrigin(origin)).imageSeedColor
        }
        assertNotEquals(seeds[0], seeds[1], "two shows must not share the same placeholder colour")
        assertTrue(seeds.none { it == 0 })
        Unit
    }

    /**
     * An [ApplePodcastClient] whose lookup always resolves to [feedUrl], served
     * offline by a MockEngine so the itunes-lookup paths never touch the network.
     */
    private fun fakeAppleClient(feedUrl: String): ApplePodcastClient =
        ApplePodcastClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        val lookupJson = """
                            {"resultCount":1,"results":[{
                                "feedUrl":"$feedUrl",
                                "trackViewUrl":"https://podcasts.apple.com/us/podcast/fake-show/id123456",
                                "trackName":"Fake Podcast",
                                "artistName":"Fake Author",
                                "artworkUrl600":"https://example.com/artwork.jpg",
                                "country":"US"
                            }]}
                        """.trimIndent()
                        respond(lookupJson)
                    }
                }
            }
        )

    @Test
    fun testAddPodcastRollsBackWhenEpisodeInsertFails() = runBlocking {
        val rollbackOrigin = "https://example.com/rollback.xml"
        val podcast = Podcast(
            origin = rollbackOrigin,
            link = "https://example.com/rollback",
            title = "Rollback Podcast",
            description = "Description",
            author = "Author",
            imageUrl = "https://example.com/image.jpg",
            languageCode = "en"
        )
        val episodes = listOf(
            testEpisode("rollback-1", rollbackOrigin),
            testEpisode("rollback-2", rollbackOrigin)
        )
        createFailingEpisodeInsertTrigger("rollback-2")

        assertFailsWith<Exception> {
            manager.addPodcast(podcast, episodes, null, duplicateCheck = false)
        }

        assertNull(database.podcasts.getByOrigin(rollbackOrigin))
        assertTrue(database.episodes.getAllByOrigin(rollbackOrigin).isEmpty())
    }

    private fun testEpisode(id: String, origin: String) = PodcastEpisode(
        id = id,
        guid = id,
        origin = origin,
        link = "https://example.com/$id",
        title = id,
        description = "Description",
        author = "Author",
        pubDate = 0,
        duration = 0,
        audioUrl = "https://example.com/$id.mp3",
        podcastTitle = "Rollback Podcast"
    )

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
