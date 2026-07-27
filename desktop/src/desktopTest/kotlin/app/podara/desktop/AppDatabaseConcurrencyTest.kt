package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.PlayerQueueRow
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppDatabaseConcurrencyTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File

    @BeforeTest
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podium_concurrency_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    @Test
    fun testConcurrentDaoOperationsAreSerialized() = runBlocking {
        val count = 50

        coroutineScope {
            (0 until count).map { index ->
                async {
                    val origin = "https://example.com/feed-$index.xml"
                    database.podcasts.insert(
                        Podcast(
                            origin = origin,
                            link = "https://example.com/$index",
                            title = "Podcast $index",
                            description = "Description $index",
                            author = "Author $index",
                            imageUrl = "https://example.com/$index.jpg",
                            languageCode = "en"
                        )
                    )
                    database.episodes.insert(
                        PodcastEpisode(
                            id = "$origin:ep-1",
                            guid = "ep-1",
                            origin = origin,
                            link = "https://example.com/$index/ep-1",
                            title = "Episode $index",
                            description = "Episode description $index",
                            author = "Author $index",
                            pubDate = index.toLong(),
                            duration = index,
                            audioUrl = "https://example.com/$index/audio.mp3",
                            podcastTitle = "Podcast $index"
                        )
                    )
                    database.history.insert(origin, "$origin:ep-1")
                }
            }.awaitAll()
        }

        assertEquals(count, database.podcasts.getAllSync().size)
        assertEquals(count, database.history.getAllSync().size)
    }

    @Test
    fun testSaveQueueRollsBackDeleteWhenBatchInsertFails() = runBlocking {
        database.playerQueue.saveQueue(
            listOf(PlayerQueueRow(queueOrder = 0, url = "https://example.com/original", title = "Original"))
        )
        DriverManager.getConnection("jdbc:sqlite:${testDbFile.absolutePath}").use { conn ->
            conn.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TRIGGER fail_queue_insert BEFORE INSERT ON playerQueueItem
                    WHEN NEW.title = 'Reject'
                    BEGIN SELECT RAISE(FAIL, 'forced queue failure'); END
                    """.trimIndent()
                )
            }
        }

        assertFailsWith<Exception> {
            database.playerQueue.saveQueue(
                listOf(
                    PlayerQueueRow(queueOrder = 0, url = "https://example.com/new", title = "New"),
                    PlayerQueueRow(queueOrder = 1, url = "https://example.com/reject", title = "Reject")
                )
            )
        }

        val queue = database.playerQueue.loadQueue()
        assertEquals(1, queue.size)
        assertEquals("Original", queue.single().title)
    }
}
