package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import app.podara.manager.AddPodcastResult
import app.podara.manager.PodcastManager
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
