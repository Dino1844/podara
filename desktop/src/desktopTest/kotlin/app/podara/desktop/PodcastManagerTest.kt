package app.podara.desktop

import app.podara.api.apple.ApplePodcastClient
import app.podara.api.model.PodcastPreviewModel
import app.podara.data.AppDatabase
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import app.podara.manager.AddPodcastResult
import app.podara.manager.PodcastManager
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
    private val fakeOrigin = "https://fake-podcast.example.com/feed.xml"

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
        val result = manager.addPodcast("https://invalid-url.example.com/feed.xml", null)
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
