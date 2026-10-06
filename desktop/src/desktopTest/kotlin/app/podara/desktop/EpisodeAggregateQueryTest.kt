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
import kotlin.test.assertTrue

/**
 * The Home screen's per-podcast aggregates.
 *
 * It used to call getAllByOrigin once per podcast to build two maps — episode
 * count and newest pubDate. That query is `SELECT *`, so each episode's title,
 * description and audio URL came out of SQLite as well, and descriptions are
 * routinely several KB of HTML. All of that to compute two numbers in Kotlin, on
 * the single-threaded database dispatcher.
 *
 * getCountsAndLatestByOrigin does it in one grouped query instead.
 */
class EpisodeAggregateQueryTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File

    @BeforeTest
    fun setup() {
        testDbFile = File(
            System.getProperty("java.io.tmpdir"),
            "podara_aggregate_test_${System.currentTimeMillis()}.db"
        )
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    private suspend fun seedPodcast(origin: String, episodes: List<Triple<String, String, Long>>) {
        database.podcasts.insert(
            Podcast(
                origin = origin, link = "https://example.com", title = origin,
                description = "desc", author = "author", imageUrl = "", imageSeedColor = 0,
                languageCode = "en", fileSize = 0
            )
        )
        episodes.forEach { (id, description, pubDate) ->
            database.episodes.insert(
                PodcastEpisode(
                    id = "$origin:$id", guid = id, origin = origin, link = "https://example.com/$id",
                    title = "Episode $id", description = description, imageUrl = null, author = "author",
                    pubDate = pubDate, duration = 3600, audioUrl = "https://example.com/$id.mp3",
                    podcastTitle = origin, imageSeedColor = 0, isNew = false
                )
            )
        }
    }

    @Test
    fun testCountsAndLatestPerOrigin() = runBlocking {
        seedPodcast("feed-a", listOf(Triple("e1", "x", 100L), Triple("e2", "x", 300L)))
        seedPodcast("feed-b", listOf(Triple("e1", "x", 50L)))

        val (counts, latest) = database.episodes.getCountsAndLatestByOrigin(listOf("feed-a", "feed-b"))

        assertEquals(2, counts["feed-a"])
        assertEquals(1, counts["feed-b"])
        assertEquals(300L, latest["feed-a"], "should report the newest pubDate, not the first row")
        assertEquals(50L, latest["feed-b"])
    }

    @Test
    fun testOriginWithNoEpisodesIsOmitted() = runBlocking {
        seedPodcast("feed-a", listOf(Triple("e1", "x", 100L)))

        val (counts, latest) = database.episodes.getCountsAndLatestByOrigin(listOf("feed-a", "feed-empty"))

        assertEquals(1, counts.size)
        assertTrue("feed-empty" !in counts, "an empty origin should not report a zero count")
        assertTrue("feed-empty" !in latest)
    }

    @Test
    fun testMatchesTheRowByRowAnswer() = runBlocking {
        // The aggregate must agree with what the old per-origin loop computed,
        // or the Home screen would show different numbers than before.
        val origins = (1..25).map { "feed-$it" }
        origins.forEachIndexed { index, origin ->
            val episodeCount = (index % 4) + 1
            seedPodcast(
                origin,
                (1..episodeCount).map { Triple("e$it", "d", (index * 100 + it).toLong()) }
            )
        }

        val (counts, latest) = database.episodes.getCountsAndLatestByOrigin(origins)

        origins.forEachIndexed { index, origin ->
            val rows = database.episodes.getAllByOrigin(origin)
            assertEquals(rows.size, counts[origin], "count mismatch for $origin")
            assertEquals(rows.maxOf { it.pubDate }, latest[origin], "latest mismatch for $origin")
        }
    }

    @Test
    fun testHandlesMoreOriginsThanSqliteVariableLimit() = runBlocking {
        // The query is chunked; an unchunked IN(...) would blow the limit.
        val originCount = 1200
        val origins = (1..originCount).map { "bulk-$it" }
        seedPodcast(origins[0], listOf(Triple("e1", "x", 10L)))

        val (counts, _) = database.episodes.getCountsAndLatestByOrigin(origins)

        assertEquals(1, counts[origins[0]], "the seeded origin should be counted")
        assertEquals(1, counts.size, "no other origin has episodes")
    }

    @Test
    fun testEmptyOriginListIsHandled() = runBlocking {
        val (counts, latest) = database.episodes.getCountsAndLatestByOrigin(emptyList())
        assertTrue(counts.isEmpty())
        assertTrue(latest.isEmpty())
    }
}