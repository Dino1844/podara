package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batched lookups for the Downloads screen.
 *
 * It needs one artwork URL per downloaded episode and used to call
 * `episodes.getById` per row. That is a `SELECT *`, so each call pulled a
 * multi-KB description out of SQLite on the single-threaded database dispatcher
 * for the sake of one short column — 500 downloads meant 500 round trips.
 */
class EpisodeImageUrlLookupTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File

    @BeforeTest
    fun setup() {
        testDbFile = File(
            System.getProperty("java.io.tmpdir"),
            "podara_image_urls_test_${System.nanoTime()}.db"
        )
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    private suspend fun insertEpisode(id: String, imageUrl: String?) {
        database.episodes.insert(
            PodcastEpisode(
                id = id, guid = id, origin = "feed", link = "https://example.com/$id",
                title = "Episode $id", description = "d".repeat(4096), imageUrl = imageUrl,
                author = "author", pubDate = 0L, duration = 60,
                audioUrl = "https://example.com/$id.mp3", podcastTitle = "feed",
                imageSeedColor = 0, isNew = false
            )
        )
    }

    private suspend fun insertDownload(episodeId: String, filePath: String) {
        database.downloads.insert(episodeId, "feed", filePath, "feed", "Episode $episodeId")
    }

    @Test
    fun testReturnsUrlsKeyedByEpisodeId() = runBlocking {
        insertEpisode("e1", "https://example.com/1.jpg")
        insertEpisode("e2", "https://example.com/2.jpg")

        val urls = database.episodes.getImageUrlsByIds(listOf("e1", "e2"))

        assertEquals("https://example.com/1.jpg", urls["e1"])
        assertEquals("https://example.com/2.jpg", urls["e2"])
    }

    @Test
    fun testMissingEpisodeAndMissingArtworkAreDistinguishable() = runBlocking {
        insertEpisode("has-art", "https://example.com/a.jpg")
        insertEpisode("no-art", null)

        val urls = database.episodes.getImageUrlsByIds(listOf("has-art", "no-art", "no-such-episode"))

        // The whole point of keeping null entries: the caller can tell "we have
        // this episode but it has no cover" from "this id is not in the table".
        assertTrue("no-art" in urls, "an episode without artwork should still appear")
        assertNull(urls["no-art"])
        assertFalse("no-such-episode" in urls, "an unknown id should be absent")
    }

    @Test
    fun testEmptyIdListDoesNotQuery() = runBlocking {
        val urls = database.episodes.getImageUrlsByIds(emptyList())

        assertTrue(urls.isEmpty())
    }

    @Test
    fun testHandlesMoreIdsThanSqliteVariableLimit() = runBlocking {
        // The query is chunked; an unchunked IN(...) would blow the limit.
        val ids = (1..1200).map { "bulk-$it" }
        insertEpisode(ids[0], "https://example.com/bulk.jpg")

        val urls = database.episodes.getImageUrlsByIds(ids)

        assertEquals(1, urls.size, "only the seeded episode exists")
        assertEquals("https://example.com/bulk.jpg", urls[ids[0]])
    }

    @Test
    fun testDuplicateIdsAreHarmless() = runBlocking {
        insertEpisode("e1", "https://example.com/1.jpg")

        val urls = database.episodes.getImageUrlsByIds(listOf("e1", "e1", "e1"))

        assertEquals(1, urls.size)
        assertEquals("https://example.com/1.jpg", urls["e1"])
    }

    @Test
    fun testPodcastSurvivesUnrelatedLookup() = runBlocking {
        database.podcasts.insert(
            Podcast(
                origin = "feed", link = "https://example.com", title = "feed",
                description = "desc", author = "author", imageUrl = "", imageSeedColor = 0,
                languageCode = "en", fileSize = 0
            )
        )
        insertEpisode("e1", "https://example.com/1.jpg")

        database.episodes.getImageUrlsByIds(listOf("e1"))

        assertTrue(database.podcasts.getAllSync().any { it.origin == "feed" })
    }
}
