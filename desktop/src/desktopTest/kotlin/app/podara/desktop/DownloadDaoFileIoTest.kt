package app.podara.desktop

import app.podara.data.AppDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DownloadDao`'s "is the file still there?" reads.
 *
 * The existence check and `length()` are filesystem calls, and they used to run
 * inside the database dispatcher — the single thread every query shares. Opening
 * the Downloads screen therefore stat'ed every downloaded file while holding the
 * database thread, so the whole UI waited on the disk and unrelated queries
 * queued behind it. Deleting the records of missing files also took one round
 * trip per file.
 *
 * What must not change is the behaviour the screens rely on: rows whose file is
 * gone are cleaned up, the rest are returned, and the total is the sum of what
 * is actually on disk.
 */
class DownloadDaoFileIoTest {

    private lateinit var database: AppDatabase
    private val tempFiles = mutableListOf<File>()

    @BeforeTest
    fun setup() {
        database = AppDatabase.buildInMemory()
    }

    @AfterTest
    fun teardown() {
        database.close()
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun testValidDownloadsAreReturnedAndMissingOnesAreDropped() = runBlocking {
        val present = tempFile("episode-a.mp3", 100)
        val alsoPresent = tempFile("episode-b.mp3", 250)
        insertDownload("ep-a", present)
        insertDownload("ep-b", alsoPresent)
        insertDownload("ep-gone", File(present.parentFile, "deleted-episode.mp3"))

        val valid = database.downloads.getAllValid()

        assertEquals(listOf("ep-a", "ep-b"), valid.map { it.episodeId }.sorted())
        assertNull(
            database.downloads.getByEpisodeId("ep-gone"),
            "a record whose file is gone must not survive the read"
        )
    }

    @Test
    fun testDownloadedIdsSkipMissingFiles() = runBlocking {
        val present = tempFile("episode-c.mp3", 10)
        insertDownload("ep-c", present)
        insertDownload("ep-missing", File(present.parentFile, "never-written.mp3"))

        assertEquals(setOf("ep-c"), database.downloads.getAllValidDownloadedIds())
    }

    @Test
    fun testTotalBytesCountsOnlyFilesOnDisk() = runBlocking {
        insertDownload("ep-100", tempFile("one.mp3", 100))
        insertDownload("ep-250", tempFile("two.mp3", 250))
        insertDownload("ep-missing", File(tempFiles.first().parentFile, "absent.mp3"))

        assertEquals(350L, database.downloads.getTotalDownloadedBytes())
    }

    @Test
    fun testAllFilesPresentLeavesEveryRecordAlone() = runBlocking {
        insertDownload("ep-a", tempFile("keep-a.mp3", 1))
        insertDownload("ep-b", tempFile("keep-b.mp3", 1))

        assertEquals(2, database.downloads.getAllValid().size)
        assertEquals(
            setOf("ep-a", "ep-b"),
            database.downloads.getAllDownloadedIds(),
            "the cleanup must not delete rows it just validated"
        )
    }

    @Test
    fun testEmptyDownloadTableIsHandled() = runBlocking {
        assertTrue(database.downloads.getAllValid().isEmpty())
        assertTrue(database.downloads.getAllValidDownloadedIds().isEmpty())
        assertEquals(0L, database.downloads.getTotalDownloadedBytes())
    }

    private suspend fun insertDownload(episodeId: String, file: File) {
        database.downloads.insert(
            episodeId = episodeId,
            origin = "feed",
            filePath = file.absolutePath,
            podcastTitle = "Show",
            episodeTitle = "Episode $episodeId"
        )
    }

    private fun tempFile(name: String, sizeBytes: Int): File {
        val file = File(System.getProperty("java.io.tmpdir"), "podara-download-${System.nanoTime()}-$name")
        file.writeBytes(ByteArray(sizeBytes) { 0x41 })
        file.deleteOnExit()
        tempFiles.add(file)
        return file
    }
}
