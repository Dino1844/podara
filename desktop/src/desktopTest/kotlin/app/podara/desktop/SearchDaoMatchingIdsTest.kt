package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SearchDao.matchingEpisodeIds`, the membership query the History and Favorites
 * screens filter with.
 *
 * The FTS index was maintained on every episode write but never read by the UI:
 * both screens filtered their already-loaded rows with `title.contains`, which
 * cannot see a word that only appears in the show notes. That is the gap these
 * tests pin — plus the two ways a membership query can go quietly wrong: it may
 * only ever return ids that were asked about, and it must survive a candidate
 * list larger than one `IN (…)` chunk.
 */
class SearchDaoMatchingIdsTest {

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
    fun testFindsAMatchThatOnlyExistsInTheShowNotes() = runBlocking {
        database.episodes.insert(episode("ep-rust", title = "Episode 12", description = "<p>We talk about Rust and borrow checking.</p>"))
        database.episodes.insert(episode("ep-go", title = "Episode 13", description = "<p>Goroutines and channels.</p>"))

        val matched = database.search.matchingEpisodeIds("Rust", listOf("ep-rust", "ep-go"))

        assertEquals(setOf("ep-rust"), matched, "a word in the description must be searchable")
    }

    @Test
    fun testOnlyReturnsIdsFromTheCandidateList() = runBlocking {
        database.episodes.insert(episode("ep-in-list", title = "Rust one", description = "body"))
        database.episodes.insert(episode("ep-not-in-list", title = "Rust two", description = "body"))

        val matched = database.search.matchingEpisodeIds("Rust", listOf("ep-in-list"))

        assertEquals(setOf("ep-in-list"), matched)
    }

    @Test
    fun testFindsAPrefixWhileTheUserIsStillTyping() = runBlocking {
        database.episodes.insert(episode("ep-ts", title = "Typescript is fine", description = "body"))

        assertEquals(
            setOf("ep-ts"),
            database.search.matchingEpisodeIds("typescr", listOf("ep-ts")),
            "FTS matches prefixes, which is what makes search-as-you-type usable"
        )
    }

    @Test
    fun testBlankQueryAndEmptyCandidatesMatchNothing() = runBlocking {
        database.episodes.insert(episode("ep-1", title = "Anything", description = "body"))

        assertTrue(database.search.matchingEpisodeIds("   ", listOf("ep-1")).isEmpty())
        assertTrue(database.search.matchingEpisodeIds("Anything", emptyList()).isEmpty())
    }

    @Test
    fun testQuerySyntaxInUserInputIsInert() = runBlocking {
        // A MATCH string is SQL syntax, not a value. Raw input used to reach it,
        // so an apostrophe or a stray bracket crashed the screen.
        database.episodes.insert(episode("ep-1", title = "Rust and C++", description = "body"))

        val matched = database.search.matchingEpisodeIds("Rust\" OR *", listOf("ep-1"))

        assertTrue(matched.isEmpty(), "the sanitizer must quote the input instead of passing operators through")
    }

    @Test
    fun testCandidateListLargerThanOneParameterChunk() = runBlocking {
        // ID_CHUNK is 400; 900 candidates means the query is split, and an id in
        // the last chunk must still be found.
        val total = 900
        repeat(total) { index ->
            val title = if (index == total - 1) "Needle episode" else "Filler $index"
            database.episodes.insert(episode("ep-$index", title = title, description = "body"))
        }

        val candidates = (0 until total).map { "ep-$it" }
        val matched = database.search.matchingEpisodeIds("Needle", candidates)

        assertEquals(setOf("ep-${total - 1}"), matched)
    }

    private fun episode(id: String, title: String, description: String) = PodcastEpisode(
        id = id,
        guid = id,
        origin = "feed",
        link = "https://example.com/$id",
        title = title,
        description = description,
        author = "author",
        pubDate = 1L,
        duration = 60,
        audioUrl = "https://example.com/$id.mp3",
        podcastTitle = "Show"
    )
}
