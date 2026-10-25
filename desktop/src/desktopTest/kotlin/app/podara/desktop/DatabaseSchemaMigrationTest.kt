package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.PlayerQueueRow
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Schema versions 4 through 7, and the migrations behind them.
 *
 * Version 4 is pinned because of two bugs. History and episode listings sort by
 * `timestamp DESC, id DESC` / `pubDate DESC, id DESC`, but v2 built indexes
 * without the trailing tiebreak column, so SQLite could not use them for the
 * second sort key and fell back to a scan plus a temp B-tree. And the migration
 * runner had no `else` branch: bumping [AppDatabase.SCHEMA_VERSION] without
 * adding a step wrote the new `user_version` anyway, which skipped that step
 * forever.
 *
 * Version 5 adds `playerQueueItem.fallbackUrl` — the stream a downloaded queue
 * item came from, without which a restored session whose download was deleted
 * could not fall back to playing it — and drops `syncAction`, which nothing
 * ever wrote.
 *
 * Version 6 adds `podcastSubscription.playbackSpeedOverride` — a show's pinned
 * playback speed. NULL (every pre-existing row) keeps the session-level speed.
 *
 * Version 7 adds `podcastSubscription.keepDownloads` — how many of a show's
 * most recent downloads to keep, the rest pruned automatically. 0 (every
 * pre-existing row) keeps the old behaviour: no automatic cleanup.
 */
class DatabaseSchemaMigrationTest {

    private lateinit var dbFile: File

    @BeforeTest
    fun setup() {
        dbFile = File(System.getProperty("java.io.tmpdir"), "podara_schema_test_${System.nanoTime()}.db")
        dbFile.deleteOnExit()
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
    }

    @Test
    fun testSchemaVersionIsSeven() = runBlocking {
        withDatabase { }
        withRawConnection { conn ->
            assertEquals(7, readUserVersion(conn))
        }
    }

    @Test
    fun testHistoryAndEpisodeIndexesCoverTheirSortTiebreaks() = runBlocking {
        withDatabase { }
        withRawConnection { conn ->
            assertTrue(
                listOf("timestamp", "id") in indexColumns(conn, "podcastHistory"),
                "podcastHistory needs (timestamp DESC, id DESC): ${indexColumns(conn, "podcastHistory")}"
            )
            assertTrue(
                listOf("origin", "pubDate", "id") in indexColumns(conn, "podcastEpisode"),
                "podcastEpisode needs (origin, pubDate DESC, id DESC): ${indexColumns(conn, "podcastEpisode")}"
            )
        }
    }

    @Test
    fun testListingQueriesDoNotSortInATempBTree() = runBlocking {
        withDatabase { database ->
            // Enough rows that "scan then sort" would be a plausible plan.
            repeat(50) { index ->
                database.podcasts.insert(
                    Podcast(
                        origin = "feed-$index",
                        link = "https://example.com/$index",
                        title = "Show $index",
                        description = "d",
                        author = "a",
                        imageUrl = "",
                        languageCode = "en"
                    )
                )
                database.history.insert("feed-$index", "ep-${index % 7}")
            }
            database.episodes.insert(
                PodcastEpisode(
                    id = "ep-1", guid = "ep-1", origin = "feed-0", link = "",
                    title = "Episode", description = "body", author = "",
                    pubDate = 1L, duration = 60, audioUrl = "https://example.com/1.mp3",
                    podcastTitle = "Show 0"
                )
            )

            withRawConnection { conn ->
                assertTrue(
                    queryPlan(conn, "SELECT * FROM podcastHistory ORDER BY timestamp DESC, id DESC")
                        .none { it.contains("TEMP B-TREE", ignoreCase = true) },
                    "history listing must be index-ordered"
                )
                assertTrue(
                    queryPlan(
                        conn,
                        "SELECT * FROM podcastEpisode WHERE origin = 'feed-0' ORDER BY pubDate DESC, id DESC"
                    ).none { it.contains("TEMP B-TREE", ignoreCase = true) },
                    "episode listing must be index-ordered"
                )
                assertTrue(
                    queryPlan(
                        conn,
                        """
                        SELECT h.id, h.origin, h.episodeId, h.timestamp,
                        e.title as epTitle, e.audioUrl as epAudioUrl, e.imageUrl as epImageUrl,
                        e.podcastTitle as epPodcastTitle, e.duration as epDuration,
                        p.imageUrl as podcastImageUrl
                        FROM podcastHistory h
                        LEFT JOIN podcastEpisode e ON h.episodeId = e.id
                        LEFT JOIN podcast p ON h.origin = p.origin
                        ORDER BY h.timestamp DESC
                        """.trimIndent()
                    ).none { it.contains("TEMP B-TREE", ignoreCase = true) },
                    "the joined history listing must be index-ordered"
                )
            }
        }
    }

    @Test
    fun testUpgradingFromV3CreatesTheNewIndexes() = runBlocking {
        // Build at v4, then rewrite it to look exactly like a v3 database: the
        // old two-column episode index, no history timestamp index, and
        // user_version = 3.
        withDatabase { }
        withRawConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DROP INDEX idx_episode_origin_pubDate")
                stmt.executeUpdate(
                    "CREATE INDEX idx_episode_origin_pubDate ON podcastEpisode(origin, pubDate DESC)"
                )
                stmt.executeUpdate("DROP INDEX idx_history_timestamp_id")
                stmt.executeUpdate("PRAGMA user_version=3")
            }
        }

        withDatabase { }
        withRawConnection { conn ->
            assertEquals(7, readUserVersion(conn))
            assertTrue(
                listOf("origin", "pubDate", "id") in indexColumns(conn, "podcastEpisode"),
                "the v4 step must replace the two-column episode index"
            )
            assertTrue(
                listOf("timestamp", "id") in indexColumns(conn, "podcastHistory"),
                "the v4 step must add the history timestamp index"
            )
        }
    }

    @Test
    fun testUpgradingFromV4AddsTheFallbackUrlColumnAndDropsSyncAction() = runBlocking {
        // Build at v5, then put it back into exactly the v4 shape: no
        // `playerQueueItem.fallbackUrl`, a `syncAction` table the old build
        // created unconditionally, and user_version = 4.
        withDatabase { }
        withRawConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE syncAction (
                        id TEXT NOT NULL PRIMARY KEY,
                        actionType TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        audioUrl TEXT,
                        position INTEGER,
                        total INTEGER,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.executeUpdate("ALTER TABLE playerQueueItem DROP COLUMN fallbackUrl")
                stmt.executeUpdate("PRAGMA user_version=4")
            }
        }

        withDatabase { }
        withRawConnection { conn ->
            assertEquals(7, readUserVersion(conn))
            assertTrue(
                columnNames(conn, "playerQueueItem").contains("fallbackUrl"),
                "the v5 step must add playerQueueItem.fallbackUrl: ${columnNames(conn, "playerQueueItem")}"
            )
            assertTrue(
                !tableExists(conn, "syncAction"),
                "the v5 step must drop the table nothing ever wrote"
            )
        }
    }

    @Test
    fun testUpgradingFromV5AddsTheSpeedOverrideColumn() = runBlocking {
        // Build at v6, then put it back into exactly the v5 shape: no
        // `podcastSubscription.playbackSpeedOverride`, and user_version = 5.
        // A pre-existing subscription row is what the migration has to carry
        // over — it must come back reading "no override", i.e. follow the
        // session speed.
        withDatabase { database ->
            database.subscriptions.insert("feed-0", false, false)
        }
        withRawConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("ALTER TABLE podcastSubscription DROP COLUMN playbackSpeedOverride")
                stmt.executeUpdate("PRAGMA user_version=5")
            }
        }

        withDatabase { database ->
            assertEquals(
                null,
                database.subscriptions.getByOriginSync("feed-0")?.playbackSpeedOverride,
                "a pre-v6 row must come back with no speed pin"
            )
            // And the column the migration added must be writable through the DAO.
            database.subscriptions.setSpeedOverride("feed-0", 1.75f)
            assertEquals(1.75f, database.subscriptions.getByOriginSync("feed-0")?.playbackSpeedOverride)
        }
        withRawConnection { conn ->
            assertEquals(7, readUserVersion(conn))
            assertTrue(
                columnNames(conn, "podcastSubscription").contains("playbackSpeedOverride"),
                "the v6 step must add podcastSubscription.playbackSpeedOverride: " +
                    columnNames(conn, "podcastSubscription")
            )
        }
    }

    @Test
    fun testUpgradingFromV6AddsTheKeepDownloadsColumn() = runBlocking {
        // Build at v7, then put it back into exactly the v6 shape: no
        // `podcastSubscription.keepDownloads`, and user_version = 6. A
        // pre-existing subscription row is what the migration has to carry
        // over — it must come back reading "no automatic cleanup", i.e. 0,
        // so nothing the user already downloaded is touched by the upgrade.
        withDatabase { database ->
            database.subscriptions.insert("feed-0", false, false)
        }
        withRawConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("ALTER TABLE podcastSubscription DROP COLUMN keepDownloads")
                stmt.executeUpdate("PRAGMA user_version=6")
            }
        }

        withDatabase { database ->
            assertEquals(
                0,
                database.subscriptions.getByOriginSync("feed-0")?.keepDownloads,
                "a pre-v7 row must come back with the cleanup disabled"
            )
            // And the column the migration added must be writable through the DAO.
            database.subscriptions.setKeepDownloads("feed-0", 10)
            assertEquals(10, database.subscriptions.getByOriginSync("feed-0")?.keepDownloads)
        }
        withRawConnection { conn ->
            assertEquals(7, readUserVersion(conn))
            assertTrue(
                columnNames(conn, "podcastSubscription").contains("keepDownloads"),
                "the v7 step must add podcastSubscription.keepDownloads: " +
                    columnNames(conn, "podcastSubscription")
            )
        }
    }

    @Test
    fun testSpeedOverridePinsAndClearsSurviveAReopen() = runBlocking {
        withDatabase { database ->
            database.subscriptions.insert("feed-0", false, false)
            // A row that never had a pin reads as "follow the session speed".
            assertEquals(null, database.subscriptions.getByOriginSync("feed-0")?.playbackSpeedOverride)
            assertEquals(null, database.subscriptions.getAllSync().single().playbackSpeedOverride)

            database.subscriptions.setSpeedOverride("feed-0", 1.5f)
            assertEquals(1.5f, database.subscriptions.getByOriginSync("feed-0")?.playbackSpeedOverride)
            assertEquals(1.5f, database.subscriptions.getAllSync().single().playbackSpeedOverride)
        }
        // The pin exists so a show keeps its speed across restarts; it must
        // survive the close/reopen it is stored for.
        withDatabase { database ->
            assertEquals(1.5f, database.subscriptions.getByOriginSync("feed-0")?.playbackSpeedOverride)
            database.subscriptions.clearSpeedOverride("feed-0")
        }
        withDatabase { database ->
            assertEquals(null, database.subscriptions.getByOriginSync("feed-0")?.playbackSpeedOverride)
        }
    }

    @Test
    fun testFreshDatabaseHasNoSyncActionTable() = runBlocking {
        // The table was created by `createTables` on every install, including
        // fresh ones, so it must be gone there too — not only on the upgrade path.
        withDatabase { }
        withRawConnection { conn ->
            assertTrue(!tableExists(conn, "syncAction"), "nothing writes syncAction, so it must not be created")
        }
    }

    @Test
    fun testQueueRowsSurviveACloseAndReopenWithTheirFallbackUrl() = runBlocking {
        // v5 exists so a downloaded queue row can remember the stream it came
        // from; that is only useful if it survives the restart it is meant for.
        withDatabase { database ->
            database.playerQueue.saveQueue(
                listOf(
                    PlayerQueueRow(
                        queueOrder = 0,
                        url = "/downloads/episode.mp3",
                        title = "Downloaded",
                        episodeId = "ep-1",
                        isDownloaded = true,
                        fallbackUrl = "https://example.com/episode.mp3"
                    )
                )
            )
        }

        withDatabase { database ->
            val row = database.playerQueue.loadQueue().single()
            assertEquals("/downloads/episode.mp3", row.url)
            assertEquals("https://example.com/episode.mp3", row.fallbackUrl)
        }
    }

    @Test
    fun testLegacyDownloadTableGainsEpisodeTitleColumn() = runBlocking {
        // An install from before `podcastDownload.episodeTitle` existed. The
        // column used to be added by a bare `try { ALTER } catch (_: Exception) {}`
        // that ran *before* the CREATE TABLE, so on fresh databases it always
        // threw "no such table" and swallowed it, and on real failures (locked
        // database, wrong column) it swallowed those too.
        withRawConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE podcastDownload (
                        episodeId TEXT NOT NULL PRIMARY KEY,
                        origin TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        podcastTitle TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.executeUpdate(
                    "INSERT INTO podcastDownload (episodeId, origin, filePath, podcastTitle, timestamp) " +
                        "VALUES ('legacy-download', 'origin', '/tmp/legacy.mp3', 'Legacy', 1)"
                )
                stmt.executeUpdate("PRAGMA user_version=3")
            }
        }

        withDatabase { database ->
            // Reading through the DAO proves the column exists and was defaulted.
            val record = database.downloads.getByEpisodeId("legacy-download")
            assertEquals("", record?.episodeTitle)
            assertEquals("/tmp/legacy.mp3", record?.filePath)
        }
        withRawConnection { conn ->
            assertTrue(
                columnNames(conn, "podcastDownload").contains("episodeTitle"),
                "the legacy table must gain episodeTitle"
            )
        }
    }

    @Test
    fun testRefusesDatabaseWrittenByANewerVersion() = runBlocking {
        withDatabase { }
        withRawConnection { conn ->
            conn.createStatement().use { it.executeUpdate("PRAGMA user_version=99") }
        }

        val failure = assertFailsWith<IllegalStateException> { AppDatabase.build(dbFile) }
        assertTrue(
            failure.message.orEmpty().contains("newer"),
            "the downgrade message should say why: ${failure.message}"
        )
    }

    // ── Helpers ──

    private suspend fun <T> withDatabase(block: suspend (AppDatabase) -> T): T {
        val database = AppDatabase.build(dbFile)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun readUserVersion(conn: Connection): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA user_version").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    /** Column names declared on [table], in declaration order. */    private fun columnNames(conn: Connection, table: String): List<String> {
        val columns = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) columns.add(rs.getString("name"))
            }
        }
        return columns
    }

    /** Whether [table] exists in this database's schema. */
    private fun tableExists(conn: Connection, table: String): Boolean =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { rs ->
                rs.next()
            }
        }

    /** Index column lists for [table], in declaration order. */
    private fun indexColumns(conn: Connection, table: String): List<List<String>> {
        val indexes = mutableListOf<List<String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA index_list($table)").use { rs ->
                while (rs.next()) {
                    val name = rs.getString("name")
                    val columns = mutableListOf<String>()
                    conn.createStatement().use { inner ->
                        inner.executeQuery("PRAGMA index_info($name)").use { info ->
                            while (info.next()) {
                                columns.add(info.getString("name"))
                            }
                        }
                    }
                    if (columns.isNotEmpty()) indexes.add(columns)
                }
            }
        }
        return indexes
    }

    private fun queryPlan(conn: Connection, sql: String): List<String> {
        val plan = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
                while (rs.next()) plan.add(rs.getString("detail"))
            }
        }
        return plan
    }

    /**
     * Opens a second connection to the same file. Fine while the app's
     * connection is open: WAL lets readers and the writer coexist.
     */
    private fun <T> withRawConnection(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use(block)
}
