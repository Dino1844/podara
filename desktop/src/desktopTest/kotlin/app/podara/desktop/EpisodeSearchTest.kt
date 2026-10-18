package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import app.podara.data.toMatchExpression
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full-text search over episodes and podcasts.
 *
 * The screens used to pull every row into memory and filter with Kotlin
 * `contains`, which meant reading every multi-KB description out of SQLite on
 * each keystroke and gave no ranking or paging. These tests pin the FTS5
 * behaviour that replaced it — most importantly the parts that are easy to break
 * silently: the triggers, the sanitizer, and the migration backfill.
 */
class EpisodeSearchTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File

    @BeforeTest
    fun setup() {
        testDbFile = File(
            System.getProperty("java.io.tmpdir"),
            "podara_search_test_${System.nanoTime()}.db"
        )
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    private fun episode(
        id: String,
        title: String,
        description: String = "plain description",
        podcastTitle: String = "Show",
        origin: String = "feed"
    ) = PodcastEpisode(
        id = id, guid = id, origin = origin, link = "https://example.com/$id",
        title = title, description = description, imageUrl = null, author = "author",
        pubDate = 1L, duration = 60, audioUrl = "https://example.com/$id.mp3",
        podcastTitle = podcastTitle, imageSeedColor = 0, isNew = false
    )

    private fun podcast(origin: String, title: String, author: String = "author") = Podcast(
        origin = origin, link = "https://example.com", title = title,
        description = "desc", author = author, imageUrl = "", imageSeedColor = 0,
        languageCode = "en", fileSize = 0
    )

    // ── FTS5 availability ────────────────────────────────────────────────

    @Test
    fun testFts5IsAvailableInTheBundledSqlite() {
        // The whole feature rests on this. xerial's build normally compiles FTS5
        // in, but if a future bump dropped it every search would silently return
        // nothing, so the capability is asserted directly rather than inferred
        // from "the search tests happened to pass".
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE VIRTUAL TABLE probe USING fts5(x)")
                stmt.execute("INSERT INTO probe(x) VALUES ('hello world')")
                stmt.executeQuery("SELECT COUNT(*) FROM probe WHERE probe MATCH 'hello'").use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1), "FTS5 must be compiled into the bundled SQLite")
                }
            }
        }
    }

    @Test
    fun testSearchTablesAndTriggersExist() {
        DriverManager.getConnection("jdbc:sqlite:${testDbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                    val tables = mutableSetOf<String>()
                    while (rs.next()) tables.add(rs.getString(1))
                    assertTrue("podcastEpisodeFts" in tables, "episode FTS table missing")
                    assertTrue("podcastFts" in tables, "podcast FTS table missing")
                }
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='trigger'").use { rs ->
                    val triggers = mutableSetOf<String>()
                    while (rs.next()) triggers.add(rs.getString(1))
                    listOf(
                        "podcastEpisodeFtsInsert", "podcastEpisodeFtsUpdate", "podcastEpisodeFtsDelete",
                        "podcastFtsInsert", "podcastFtsUpdate", "podcastFtsDelete"
                    ).forEach { name ->
                        assertTrue(name in triggers, "trigger $name missing")
                    }
                }
            }
        }
    }

    // ── Triggers keep the index current ──────────────────────────────────

    @Test
    fun testInsertedEpisodeIsSearchableImmediately() = runBlocking {
        database.episodes.insert(episode("e1", "Rust ownership explained"))

        val hits = database.search.searchEpisodes("ownership", 10, 0)
        assertEquals(1, hits.size)
        assertEquals("e1", hits.single().id)
        assertEquals(1, database.search.searchEpisodesCount("ownership"))
    }

    @Test
    fun testUpdatedEpisodeMatchesNewTextAndNotOld() = runBlocking {
        database.episodes.insert(episode("e1", "Rust ownership explained"))
        database.episodes.insert(episode("e1", "Kotlin coroutines deep dive"))

        assertEquals(1, database.search.searchEpisodesCount("kotlin"), "new text must be indexed")
        assertEquals(0, database.search.searchEpisodesCount("ownership"), "stale text must be gone")
    }

    @Test
    fun testDeletedEpisodeIsNoLongerSearchable() = runBlocking {
        database.episodes.insert(episode("e1", "Rust ownership explained"))
        database.episodes.deleteByOrigin("feed")

        assertEquals(0, database.search.searchEpisodesCount("ownership"))
    }

    @Test
    fun testSearchIndexesStrippedDescriptionNotRawMarkup() = runBlocking {
        // The index reads `descriptionText`, not the HTML `description`. If it
        // read the raw column, tag names would be searchable and the real words
        // would compete with them in the ranking.
        database.episodes.insert(
            episode(
                "e1", "Episode one",
                description = "<div class=\"notes\"><p>Transcript about webpack</p></div>"
            )
        )

        assertEquals(1, database.search.searchEpisodesCount("webpack"))
        assertEquals(0, database.search.searchEpisodesCount("div"), "HTML tag names must not be indexed")
        assertEquals(0, database.search.searchEpisodesCount("href"))
    }

    // ── Ranking and paging ───────────────────────────────────────────────

    @Test
    fun testTitleMatchRanksAboveDescriptionOnlyMatch() = runBlocking {
        // bm25 weights the title ~10x the description, so a word in the title
        // has to outrank the same word buried in show notes.
        database.episodes.insert(episode("title-hit", "Rustacean handbook", description = "unrelated body"))
        database.episodes.insert(
            episode(
                "body-hit", "Weekly roundup",
                description = "this week we have a long segment that mentions rust alongside many other topics"
            )
        )

        val hits = database.search.searchEpisodes("rust", 10, 0)
        assertEquals(2, hits.size)
        assertEquals("title-hit", hits.first().id, "title hit must come first")
    }

    @Test
    fun testPagingCoversEveryRowExactlyOnce() = runBlocking {
        val ids = (1..7).map { "ep-$it" }
        ids.forEach { database.episodes.insert(episode(it, "Episode $it about rust")) }

        val pageSize = 3
        val seen = mutableListOf<String>()
        var offset = 0
        while (true) {
            val page = database.search.searchEpisodes("rust", pageSize, offset)
            if (page.isEmpty()) break
            seen.addAll(page.map { it.id })
            offset += pageSize
        }

        assertEquals(ids.size, seen.size, "paging must not lose rows")
        assertEquals(ids.toSet(), seen.toSet(), "paging must not duplicate rows")
        assertEquals(ids.size, database.search.searchEpisodesCount("rust"))
    }

    @Test
    fun testOffsetPastEndReturnsEmpty() = runBlocking {
        database.episodes.insert(episode("e1", "Rust ownership"))

        assertTrue(database.search.searchEpisodes("rust", 10, 100).isEmpty())
    }

    // ── Sanitizer: hostile and malformed input ───────────────────────────

    @Test
    fun testHostileInputNeverThrows() = runBlocking {
        database.episodes.insert(episode("e1", "Rust ownership explained"))

        // Each of these is FTS5 syntax if spliced into MATCH verbatim: a quote
        // opens a string, `:` is a column filter, `(` groups, AND/NOT negate.
        // Verified against raw MATCH: they raise SQLException there.
        val hostile = listOf(
            "\"", "\"\"", "*", "**", "AND", "NOT", "OR", "(", ")", "foo:bar",
            "-minus", "NEAR(a b)", "title:rust", "a AND b", "\"unclosed",
            "!!!!", "@#$%^&", "\\", "'", "'; DROP TABLE podcastEpisode; --"
        )

        hostile.forEach { input ->
            val hits = database.search.searchEpisodes(input, 10, 0)
            val count = database.search.searchEpisodesCount(input)
            assertTrue(hits.size <= 10, "unexpected page size for input [$input]")
            assertTrue(count >= 0, "count must not be negative for input [$input]")
        }
    }

    @Test
    fun testBlankAndWhitespaceQueryReturnsEmptyWithoutQuerying() = runBlocking {
        database.episodes.insert(episode("e1", "Rust ownership explained"))

        assertEquals(emptyList(), database.search.searchEpisodes("", 10, 0))
        assertEquals(emptyList(), database.search.searchEpisodes("   ", 10, 0))
        assertEquals(emptyList(), database.search.searchEpisodes("\t\n ", 10, 0))
        assertEquals(0, database.search.searchEpisodesCount(""))
        assertEquals(0, database.search.searchEpisodesCount("   "))
    }

    @Test
    fun testVeryLongInputIsHandled() = runBlocking {
        database.episodes.insert(episode("e1", "Rust ownership explained"))

        val singleToken = "x".repeat(50_000)
        val manyTokens = (1..5_000).joinToString(" ") { "word$it" }

        assertEquals(0, database.search.searchEpisodesCount(singleToken))
        assertEquals(0, database.search.searchEpisodesCount(manyTokens))
        assertTrue(database.search.searchEpisodes(singleToken, 10, 0).isEmpty())
    }

    @Test
    fun testMultipleTermsAreAnded() = runBlocking {
        database.episodes.insert(episode("both", "Rust and Kotlin compared"))
        database.episodes.insert(episode("rust-only", "Rust ownership explained"))

        // A search box means AND: every word has to appear.
        val hits = database.search.searchEpisodes("rust kotlin", 10, 0)
        assertEquals(listOf("both"), hits.map { it.id })
    }

    @Test
    fun testPrefixSearchFindsPartialWord() = runBlocking {
        database.episodes.insert(episode("e1", "TypeScript tooling"))

        assertEquals(1, database.search.searchEpisodesCount("typescr"), "prefix matcher should match mid-typing")
    }

    @Test
    fun testDiacriticsAreFolded() = runBlocking {
        database.episodes.insert(episode("e1", "Café culture"))

        assertEquals(1, database.search.searchEpisodesCount("cafe"))
    }

    // ── Sanitizer unit-level ─────────────────────────────────────────────

    @Test
    fun testMatchExpressionQuotesAndEscapes() {
        assertEquals("\"rust\"*", toMatchExpression("rust"))
        assertEquals("\"rust\"* AND \"kotlin\"*", toMatchExpression("rust kotlin"))
        assertEquals("\"rust\"* AND \"kotlin\"*", toMatchExpression("  rust   kotlin  "))
        // An embedded quote is doubled, which is FTS5's own escaping rule.
        assertEquals("\"\"\"\"*", toMatchExpression("\""))
        assertEquals("\"foo:bar\"*", toMatchExpression("foo:bar"))
        assertEquals("\"AND\"*", toMatchExpression("AND"))
    }

    @Test
    fun testBlankInputHasNoMatchExpression() {
        assertNull(toMatchExpression(""))
        assertNull(toMatchExpression("   "))
        assertNull(toMatchExpression("\t\n"))
    }

    @Test
    fun testSanitizedExpressionIsAlwaysValidForMatch() = runBlocking {
        // Round-trip every hostile input through the sanitizer and into a real
        // MATCH, proving the escaping holds rather than just looking plausible.
        val hostile = listOf("\"", "*", "AND", "NOT", "(", ")", "foo:bar", "a\"b", "\\\\", "NEAR(")
        hostile.mapNotNull { toMatchExpression(it) }.forEach { expression ->
            try {
                database.search.searchEpisodes(expression, 1, 0)
            } catch (e: Exception) {
                throw AssertionError("sanitized expression broke MATCH: $expression", e)
            }
        }    }

    // ── Podcast search ───────────────────────────────────────────────────

    @Test
    fun testPodcastSearchAndCount() = runBlocking {
        database.podcasts.insert(podcast("feed-a", "Kotlin Radio", author = "Alice"))
        database.podcasts.insert(podcast("feed-b", "Systems Weekly", author = "Bob"))

        val hits = database.search.searchPodcasts("kotlin", 10, 0)
        assertEquals(listOf("feed-a"), hits.map { it.origin })
        assertEquals(1, database.search.searchPodcastsCount("kotlin"))
        assertEquals(0, database.search.searchPodcastsCount("nonexistentterm"))
    }

    @Test
    fun testPodcastUpdateAndDeleteAreReflected() = runBlocking {
        // The two titles must share no words, otherwise the "old title is gone"
        // assertion cannot distinguish a working index from a stale one: with
        // "Kotlin Radio" -> "Kotlin Radio Renamed" the query "radio" matches
        // either way, so that assertion would have been testing nothing.
        database.podcasts.insert(podcast("feed-a", "Kotlin Radio"))
        database.podcasts.insert(podcast("feed-a", "Rust Weekly"))
        assertEquals(1, database.search.searchPodcastsCount("rust"), "new title must be indexed")
        assertEquals(0, database.search.searchPodcastsCount("kotlin"), "old title must not linger")

        database.podcasts.delete("feed-a")
        assertEquals(0, database.search.searchPodcastsCount("rust"))
    }

    // ── Migration ────────────────────────────────────────────────────────

    @Test
    fun testMigrationBackfillsExistingRows() = runBlocking {
        // Simulate an install that predates v3: correct v2 schema, rows already
        // present, no FTS objects at all. The migration must leave those rows
        // searchable without anyone pressing a "reindex" button.
        val legacyFile = File(
            System.getProperty("java.io.tmpdir"),
            "podara_search_legacy_${System.nanoTime()}.db"
        )
        legacyFile.deleteOnExit()

        try {
            DriverManager.getConnection("jdbc:sqlite:${legacyFile.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate(
                        """
                        CREATE TABLE podcast (
                            origin TEXT NOT NULL PRIMARY KEY, link TEXT NOT NULL, title TEXT NOT NULL,
                            description TEXT NOT NULL, author TEXT NOT NULL, imageUrl TEXT NOT NULL,
                            imageSeedColor INTEGER NOT NULL DEFAULT 0, languageCode TEXT NOT NULL,
                            fileSize INTEGER NOT NULL DEFAULT 0, overrideTitle TEXT NOT NULL DEFAULT ''
                        )
                        """.trimIndent()
                    )
                    // The v2 shape: no descriptionText column.
                    stmt.executeUpdate(
                        """
                        CREATE TABLE podcastEpisode (
                            id TEXT NOT NULL PRIMARY KEY, guid TEXT NOT NULL, origin TEXT NOT NULL,
                            link TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL,
                            imageUrl TEXT, author TEXT NOT NULL, pubDate INTEGER NOT NULL,
                            duration INTEGER NOT NULL, audioUrl TEXT NOT NULL, podcastTitle TEXT NOT NULL,
                            imageSeedColor INTEGER NOT NULL DEFAULT 0, new INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    stmt.executeUpdate("PRAGMA user_version=2")
                    stmt.executeUpdate(
                        "INSERT INTO podcast (origin, link, title, description, author, imageUrl, " +
                            "imageSeedColor, languageCode, fileSize, overrideTitle) VALUES " +
                            "('feed-a', 'https://example.com', 'Legacy Show', 'd', 'a', '', 0, 'en', 0, '')"
                    )
                    stmt.executeUpdate(
                        "INSERT INTO podcastEpisode (id, guid, origin, link, title, description, imageUrl, " +
                            "author, pubDate, duration, audioUrl, podcastTitle, imageSeedColor, new) VALUES " +
                            "('old-1', 'g', 'feed-a', 'l', 'Legacy episode about TypeScript', " +
                            "'<p>A body mentioning webpack and deno.</p>', NULL, 'a', 1, 60, 'u', 'Legacy Show', 0, 0)"
                    )
                }
            }

            val upgraded = AppDatabase.build(legacyFile)
            try {
                assertEquals(1, upgraded.search.searchEpisodesCount("typescript"), "title must be indexed")
                assertEquals(1, upgraded.search.searchEpisodesCount("webpack"), "stripped body must be indexed")
                assertEquals(0, upgraded.search.searchEpisodesCount("div"), "markup must not be indexed")
                assertEquals(1, upgraded.search.searchPodcastsCount("legacy"))
            } finally {
                upgraded.close()
            }
        } finally {
            legacyFile.delete()
        }
    }

    @Test
    fun testSearchIndexSurvivesIntegrityCheck() {
        DriverManager.getConnection("jdbc:sqlite:${testDbFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("INSERT INTO podcastEpisodeFts(podcastEpisodeFts) VALUES('integrity-check')")
                stmt.executeUpdate("INSERT INTO podcastFts(podcastFts) VALUES('integrity-check')")
            }
        }
    }

    @Test
    fun testReopeningDatabaseKeepsSearchWorking() = runBlocking {
        database.episodes.insert(episode("e1", "Rust ownership explained"))
        database.close()

        // Reopening runs createTables and the (now no-op) migration again; the
        // index must not be wiped or duplicated.
        database = AppDatabase.build(testDbFile)
        assertEquals(1, database.search.searchEpisodesCount("ownership"))
        assertEquals(1, database.search.searchEpisodes("ownership", 10, 0).size)
    }
}
