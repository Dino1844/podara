package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.PodcastDownload
import app.podara.manager.DownloadManager
import app.podara.manager.SubscriptionManager
import app.podara.manager.UpdatePodcastResult
import app.podara.manager.downloadsBeyondKeepLimit
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "keep latest N downloads" policy for one podcast.
 *
 * Two layers, tested separately:
 *
 * - [downloadsBeyondKeepLimit] is the whole policy — newest by download time,
 *   keep N, exempt the playing episode — and is pure, so the ordering and
 *   exemption rules are asserted without files or a database.
 * - [DownloadManager.pruneDownloadsToKeepLimit] owns the side effects: it must
 *   only ever touch the origin it was given, and it must go through the same
 *   delete path as the Downloads screen, where a file that refuses to be
 *   deleted (Windows: still open, i.e. playing) keeps its record for the next
 *   round instead of orphaning the row.
 */
class KeepDownloadsCleanupTest {

    private lateinit var dbFile: File
    private lateinit var downloadsDir: File

    @BeforeTest
    fun setup() {
        dbFile = File(System.getProperty("java.io.tmpdir"), "podara_keep_test_${System.nanoTime()}.db")
        dbFile.deleteOnExit()
        downloadsDir = File(System.getProperty("java.io.tmpdir"), "podara_keep_files_${System.nanoTime()}")
        downloadsDir.mkdirs()
    }

    @AfterTest
    fun teardown() {
        downloadsDir.deleteRecursively()
        dbFile.delete()
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
    }

    // ── Pure policy ──

    @Test
    fun testKeepLimitRetainsTheNewestByDownloadTime() {
        // Download time, not publish time: what is retained is "the N most
        // recent downloads", so the oldest *download* goes first regardless of
        // how old its episode is.
        val downloads = listOf(
            download("ep-1", timestamp = 1_000L),
            download("ep-2", timestamp = 5_000L),
            download("ep-3", timestamp = 3_000L),
            download("ep-4", timestamp = 2_000L),
            download("ep-5", timestamp = 4_000L)
        )

        val toPrune = downloadsBeyondKeepLimit(downloads, keep = 2, protectedEpisodeIds = emptySet())

        // The selection is what the policy defines; the order of the returned
        // list is not part of the contract, so assertions compare as sets.
        // 5 downloads with keep=2 leaves the three oldest over the limit.
        assertEquals(setOf("ep-1", "ep-3", "ep-4"), toPrune.map { it.episodeId }.toSet())
    }

    @Test
    fun testEqualDownloadTimesBreakTiesByEpisodeId() {
        // Three downloads in the same millisecond: which one survives must not
        // depend on list order or a sort that happens to be unstable.
        val downloads = listOf(
            download("ep-b", timestamp = 42L),
            download("ep-c", timestamp = 42L),
            download("ep-a", timestamp = 42L)
        )

        val toPrune = downloadsBeyondKeepLimit(downloads, keep = 1, protectedEpisodeIds = emptySet())

        assertEquals(setOf("ep-a", "ep-b"), toPrune.map { it.episodeId }.toSet())
    }

    @Test
    fun testExactFitAndDisabledKeepPruneNothing() {
        val downloads = listOf(
            download("ep-1", timestamp = 1_000L),
            download("ep-2", timestamp = 2_000L),
            download("ep-3", timestamp = 3_000L)
        )

        // At the limit there is nothing over it.
        assertTrue(
            downloadsBeyondKeepLimit(downloads, keep = 3, protectedEpisodeIds = emptySet()).isEmpty(),
            "exactly N downloads must leave nothing to prune"
        )
        // 0 is the "no automatic cleanup" tier, and any nonsense below it too.
        assertTrue(
            downloadsBeyondKeepLimit(downloads, keep = 0, protectedEpisodeIds = emptySet()).isEmpty(),
            "keep=0 disables the cleanup"
        )
        assertTrue(
            downloadsBeyondKeepLimit(downloads, keep = -1, protectedEpisodeIds = emptySet()).isEmpty()
        )
    }

    @Test
    fun testPlayingEpisodeIsExemptForTheRound() {
        val downloads = listOf(
            download("ep-1", timestamp = 1_000L),
            download("ep-2", timestamp = 2_000L),
            download("ep-3", timestamp = 3_000L),
            download("ep-4", timestamp = 4_000L),
            download("ep-5", timestamp = 5_000L)
        )

        // Over the limit are ep-1, ep-2 and ep-3; ep-1 is playing, so the
        // other two go. It is exempt, not counted: the keep set does not grow
        // to compensate, and the next prune reconsiders ep-1.
        val toPrune = downloadsBeyondKeepLimit(downloads, keep = 2, protectedEpisodeIds = setOf("ep-1"))

        assertEquals(setOf("ep-2", "ep-3"), toPrune.map { it.episodeId }.toSet())

        // Protecting an episode that is kept anyway changes nothing.
        val keptProtected = downloadsBeyondKeepLimit(downloads, keep = 2, protectedEpisodeIds = setOf("ep-5"))
        assertEquals(setOf("ep-1", "ep-2", "ep-3"), keptProtected.map { it.episodeId }.toSet())
    }

    // ── Through the manager, against real files ──

    @Test
    fun testPruneTouchesOnlyItsOwnPodcastAndKeepsTheNewest() = runBlocking {
        withDatabase { database ->
            seedFiles(database, "feed-a", listOf("a-1" to 1_000L, "a-2" to 2_000L, "a-3" to 3_000L, "a-4" to 4_000L))
            seedFiles(database, "feed-b", listOf("b-1" to 5_000L, "b-2" to 6_000L))
            val manager = DownloadManager(database, downloadsDir)

            val pruned = manager.pruneDownloadsToKeepLimit("feed-a", keep = 2, protectedEpisodeIds = emptySet())

            assertEquals(setOf("a-1", "a-2"), pruned.toSet(), "the two oldest downloads of feed-a go")
            assertNull(database.downloads.getByEpisodeId("a-1")?.filePath)
            assertNull(database.downloads.getByEpisodeId("a-2")?.filePath)
            assertNotNull(database.downloads.getByEpisodeId("a-3"))
            assertNotNull(database.downloads.getByEpisodeId("a-4"))
            assertTrue(File(filePathFor("a-3")).isFile, "kept downloads keep their files")
            assertTrue(File(filePathFor("a-4")).isFile)
            // The other show is nobody's business.
            assertNotNull(database.downloads.getByEpisodeId("b-1"))
            assertNotNull(database.downloads.getByEpisodeId("b-2"))
            assertTrue(File(filePathFor("b-1")).isFile, "another podcast's files must not be touched")
            assertTrue(File(filePathFor("b-2")).isFile)
        }
    }

    @Test
    fun testZeroKeepLeavesEverythingUntouched() = runBlocking {
        withDatabase { database ->
            seedFiles(database, "feed-a", listOf("a-1" to 1_000L, "a-2" to 2_000L))
            seedFiles(database, "feed-b", listOf("b-1" to 3_000L))
            val manager = DownloadManager(database, downloadsDir)

            val pruned = manager.pruneDownloadsToKeepLimit("feed-a", keep = 0, protectedEpisodeIds = emptySet())

            assertTrue(pruned.isEmpty(), "keep=0 is the off tier")
            for (id in listOf("a-1", "a-2", "b-1")) {
                assertNotNull(database.downloads.getByEpisodeId(id), "$id must keep its record")
                assertTrue(File(filePathFor(id)).isFile, "$id must keep its file")
            }
        }
    }

    @Test
    fun testThePlayingEpisodeSurvivesThePrune() = runBlocking {
        withDatabase { database ->
            seedFiles(database, "feed-a", listOf("a-1" to 1_000L, "a-2" to 2_000L, "a-3" to 3_000L, "a-4" to 4_000L))
            val manager = DownloadManager(database, downloadsDir)

            val pruned = manager.pruneDownloadsToKeepLimit(
                "feed-a", keep = 2, protectedEpisodeIds = setOf("a-1")
            )

            assertEquals(listOf("a-2"), pruned, "the playing episode is skipped, the rest of the over-limit set is not")
            assertNotNull(database.downloads.getByEpisodeId("a-1"), "the playing episode keeps its record")
            assertTrue(File(filePathFor("a-1")).isFile, "the playing episode keeps its file")
            assertNull(database.downloads.getByEpisodeId("a-2"))
        }
    }

    @Test
    fun testLockedFileKeepsItsRecordUntilTheNextRound() = runBlocking {
        withDatabase { database ->
            seedFiles(database, "feed-a", listOf("a-1" to 1_000L, "a-2" to 2_000L, "a-3" to 3_000L, "a-4" to 4_000L))
            val manager = DownloadManager(database, downloadsDir)

            // Windows refuses to delete a file another handle holds open — the
            // playing case. The record must stay so database and disk agree.
            val heldOpen = File(filePathFor("a-1")).inputStream()
            try {
                val pruned = manager.pruneDownloadsToKeepLimit("feed-a", keep = 2, protectedEpisodeIds = emptySet())

                assertEquals(listOf("a-2"), pruned, "only the deletable file goes this round")
                assertNotNull(database.downloads.getByEpisodeId("a-1"), "a locked download keeps its record")
                assertTrue(File(filePathFor("a-1")).isFile, "a locked download keeps its file")
            } finally {
                heldOpen.close()
            }

            // The record that stayed is not stranded: once the handle is gone,
            // the next prune (the same trigger that runs after every refresh
            // and download) picks it up.
            val nextRound = manager.pruneDownloadsToKeepLimit("feed-a", keep = 2, protectedEpisodeIds = emptySet())
            assertEquals(listOf("a-1"), nextRound)
            assertNull(database.downloads.getByEpisodeId("a-1"))
            assertTrue(!File(filePathFor("a-1")).isFile)
        }
    }

    // ── The refresh trigger ──

    @Test
    fun testRefreshPrunesOnlyWhenTheTierIsSetAndEpisodesArrived() = runBlocking {
        withDatabase { database ->
            val origin = "https://feed.example/rss"
            database.subscriptions.insert(origin, false, false)
            database.subscriptions.setKeepDownloads(origin, 2)
            val prunedOrigins = mutableListOf<String>()
            val manager = SubscriptionManager(
                db = database,
                fetchPodcastClient = FakeFetchPodcastClient(),
                downloadsPruner = { prunedOrigins.add(it) }
            )

            val first = manager.updatePodcast(origin, 0)
            assertTrue(first is UpdatePodcastResult.Updated && first.newEpisodesCount > 0)
            assertEquals(
                listOf(origin), prunedOrigins,
                "a refresh that added episodes prunes a subscription with a tier set"
            )

            // The fake feed has not changed, so this refresh adds nothing and
            // must not prune — the policy acts on arrivals, not on schedule.
            prunedOrigins.clear()
            val second = manager.updatePodcast(origin, 0)
            assertTrue(second is UpdatePodcastResult.Updated)
            assertTrue(prunedOrigins.isEmpty(), "a refresh with no new episodes must not prune")
        }
    }

    @Test
    fun testRefreshWithTheTierOffNeverPrunes() = runBlocking {
        withDatabase { database ->
            val origin = "https://feed.example/rss"
            database.subscriptions.insert(origin, false, false)
            val prunedOrigins = mutableListOf<String>()
            val manager = SubscriptionManager(
                db = database,
                fetchPodcastClient = FakeFetchPodcastClient(),
                downloadsPruner = { prunedOrigins.add(it) }
            )

            val result = manager.updatePodcast(origin, 0)
            assertTrue(result is UpdatePodcastResult.Updated && result.newEpisodesCount > 0)

            assertTrue(prunedOrigins.isEmpty(), "keep=0 is the off tier; the trigger must not fire")
        }
    }

    // ── Helpers ──

    private fun download(episodeId: String, timestamp: Long) = PodcastDownload(
        episodeId = episodeId,
        origin = "feed-test",
        filePath = filePathFor(episodeId),
        podcastTitle = "Show",
        timestamp = timestamp
    )

    private fun filePathFor(episodeId: String) =
        File(downloadsDir, "feed-$episodeId.mp3").absolutePath

    /** Creates the real files and inserts their records with chosen download times. */
    private fun seedFiles(database: AppDatabase, origin: String, entries: List<Pair<String, Long>>) {
        entries.forEach { (episodeId, _) -> File(filePathFor(episodeId)).writeText("audio-$episodeId") }
        withRawConnection { conn ->
            entries.forEach { (episodeId, timestamp) ->
                // `DownloadDao.insert` stamps `System.currentTimeMillis()` itself;
                // the ordering tests need chosen times, so the row is written directly.
                conn.prepareStatement(
                    "INSERT INTO podcastDownload (episodeId, origin, filePath, podcastTitle, episodeTitle, timestamp) " +
                        "VALUES (?, ?, ?, 'Show', '', ?)"
                ).use { ps ->
                    ps.setString(1, episodeId); ps.setString(2, origin)
                    ps.setString(3, filePathFor(episodeId)); ps.setLong(4, timestamp)
                    ps.executeUpdate()
                }
            }
        }
    }

    private suspend fun <T> withDatabase(block: suspend (AppDatabase) -> T): T {
        val database = AppDatabase.build(dbFile)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    /** A second connection to the same file; WAL lets it coexist with the app's. */
    private fun <T> withRawConnection(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use(block)
}
