package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.DownloadTask
import app.podara.manager.DownloadManager
import app.podara.manager.DownloadNaming
import app.podara.manager.sha256
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.*

class DownloadManagerTest {

    private lateinit var database: AppDatabase
    private lateinit var downloadManager: DownloadManager
    private lateinit var testDbFile: File
    private lateinit var testDownloadsDir: File

    @BeforeTest
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podium_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        testDownloadsDir = File(System.getProperty("java.io.tmpdir"), "podium_test_downloads_${System.currentTimeMillis()}")
        testDownloadsDir.mkdirs()
        testDownloadsDir.deleteOnExit()
        database = AppDatabase.build(testDbFile)
        downloadManager = DownloadManager(database, testDownloadsDir)
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
        testDownloadsDir.deleteRecursively()
    }

    @Test
    fun testGetDownloadFileReturnsCorrectPath() {
        val file = getDownloadFile("https://example.com/feed.xml", "https://example.com/audio.mp3")
        assertTrue(file.absolutePath.contains(testDownloadsDir.absolutePath))
        assertTrue(file.name.isNotEmpty())
    }

    @Test
    fun testGetDownloadFileConsistentPath() {
        val file1 = getDownloadFile("https://example.com/feed.xml", "https://example.com/audio.mp3")
        val file2 = getDownloadFile("https://example.com/feed.xml", "https://example.com/audio.mp3")
        assertEquals(file1.absolutePath, file2.absolutePath)
    }

    @Test
    fun testGetDownloadFileDifferentUrls() {
        val file1 = getDownloadFile("https://example.com/feed.xml", "https://example.com/audio1.mp3")
        val file2 = getDownloadFile("https://example.com/feed.xml", "https://example.com/audio2.mp3")
        assertNotEquals(file1.absolutePath, file2.absolutePath)
    }

    @Test
    fun testGetDownloadFileWithTitles() {
        val origin = "https://example.com/feed.xml"
        val episodeId = "episode-45"
        val file = getDownloadFile(
            origin,
            "https://example.com/audio.m4a",
            episodeTitle = "E45 Test Episode",
            podcastTitle = "Test Podcast",
            episodeId = episodeId
        )
        assertEquals("Test Podcast-${origin.sha256()}", file.parentFile.name)
        assertEquals("E45 Test Episode-${episodeId.sha256()}.m4a", file.name)
    }

    @Test
    fun testGetDownloadFileWithSpecialCharacters() {
        val file = getDownloadFile(
            "https://example.com/feed.xml",
            "https://example.com/audio.mp3",
            episodeTitle = "Episode: Test Special",
            podcastTitle = "Podcast With Backslash"
        )
        assertTrue(file.parentFile.name.startsWith("Podcast With Backslash-"))
        assertTrue(file.name.startsWith("Episode_ Test Special-"))
        assertTrue(file.name.endsWith(".mp3"))
    }

    @Test
    fun testGetDownloadFileFallsBackToMp3ForUrlWithoutPathExtension() {
        val file = getDownloadFile(
            "https://example.com/feed.xml",
            "https://media.example.com/download?id=123",
            episodeTitle = "Episode Without Extension",
            podcastTitle = "Test Podcast"
        )

        assertTrue(file.name.startsWith("Episode Without"))
        assertTrue(file.name.endsWith(".mp3"))
    }

    @Test
    fun testGetDownloadFileIgnoresDomainDotsWhenChoosingExtension() {
        val file = getDownloadFile(
            "https://example.com/feed.xml",
            "https://media.example.com/download",
            episodeTitle = "Domain Dot Episode",
            podcastTitle = "Test Podcast"
        )

        assertTrue(file.name.startsWith("Domain Dot Episode-"))
        assertTrue(file.name.endsWith(".mp3"))
    }

    @Test
    fun testGetDownloadFileFallsBackToMp3ForUnsafeExtension() {
        val file = getDownloadFile(
            "https://example.com/feed.xml",
            "https://example.com/audio.exe",
            episodeTitle = "Unsafe Extension",
            podcastTitle = "Test Podcast"
        )

        assertTrue(file.name.startsWith("Unsafe Extension-"))
        assertTrue(file.name.endsWith(".mp3"))
    }

    @Test
    fun testDownloadEpisodeWithoutPathExtensionUsesMp3File() = runBlocking {
        val bytes = "audio bytes".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/download") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val result = downloadManager.downloadEpisode(
                episodeId = "no-extension-ep",
                audioUrl = "http://127.0.0.1:${server.address.port}/download?id=123",
                origin = "https://example.com/no-extension.xml",
                episodeTitle = "No Extension Episode",
                podcastTitle = "No Extension Podcast"
            )

            assertTrue(result.isSuccess)
            val file = result.getOrThrow()
            assertTrue(file.name.startsWith("No Extension Episode-"))
            assertTrue(file.name.endsWith(".mp3"))
            assertContentEquals(bytes, file.readBytes())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testDownloadEpisodeWithTitles() = runBlocking {
        val bytes = "test audio".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/audio.mp3") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val result = downloadManager.downloadEpisode(
                episodeId = "test-ep",
                audioUrl = "http://127.0.0.1:${server.address.port}/audio.mp3",
                origin = "https://example.com/feed.xml",
                episodeTitle = "Test Episode",
                podcastTitle = "My Podcast"
            )

            assertTrue(result.isSuccess)
            val file = result.getOrThrow()
            assertTrue(file.exists(), "Downloaded file should exist")
            assertContentEquals(bytes, file.readBytes())
            assertTrue(file.parentFile.name.startsWith("My Podcast-"))
            assertTrue(file.name.startsWith("Test Episode-"))
            assertTrue(file.name.endsWith(".mp3"))

            // Verify DB record was created
            val record = database.downloads.getByEpisodeId("test-ep")
            assertNotNull(record)
            assertEquals("Test Episode", record.episodeTitle)
            assertEquals(file.absolutePath, record.filePath)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testGetDownloadFileWhenNotDownloaded() {
        val file = getDownloadFile(
            "https://example.com/feed.xml",
            "https://example.com/nonexistent.mp3"
        )
        assertFalse(file.exists())
    }

    @Test
    fun testSameTitlesWithDifferentEpisodeIdsHaveDifferentPaths() {
        val first = getDownloadFile(
            origin = "https://example.com/feed.xml",
            audioUrl = "https://example.com/audio.mp3",
            episodeTitle = "Repeated title",
            podcastTitle = "Podcast",
            episodeId = "episode-1"
        )
        val second = getDownloadFile(
            origin = "https://example.com/feed.xml",
            audioUrl = "https://example.com/audio.mp3",
            episodeTitle = "Repeated title",
            podcastTitle = "Podcast",
            episodeId = "episode-2"
        )

        assertNotEquals(first.absolutePath, second.absolutePath)
        assertTrue(first.name.contains("episode-1".sha256()))
        assertTrue(second.name.contains("episode-2".sha256()))
    }

    @Test
    fun testDownloadingSameTitleEpisodesDoesNotOverwriteFiles() = runBlocking {
        val firstBytes = "first episode".toByteArray()
        val secondBytes = "second episode".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/first.mp3") { exchange ->
                exchange.sendResponseHeaders(200, firstBytes.size.toLong())
                exchange.responseBody.use { it.write(firstBytes) }
            }
            createContext("/second.mp3") { exchange ->
                exchange.sendResponseHeaders(200, secondBytes.size.toLong())
                exchange.responseBody.use { it.write(secondBytes) }
            }
            start()
        }

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val first = downloadManager.downloadEpisode(
                episodeId = "same-title-1",
                audioUrl = "$baseUrl/first.mp3",
                origin = "https://example.com/same-title.xml",
                episodeTitle = "Same title",
                podcastTitle = "Same podcast"
            ).getOrThrow()
            val second = downloadManager.downloadEpisode(
                episodeId = "same-title-2",
                audioUrl = "$baseUrl/second.mp3",
                origin = "https://example.com/same-title.xml",
                episodeTitle = "Same title",
                podcastTitle = "Same podcast"
            ).getOrThrow()

            assertNotEquals(first.absolutePath, second.absolutePath)
            assertContentEquals(firstBytes, first.readBytes())
            assertContentEquals(secondBytes, second.readBytes())
            assertEquals(first.absolutePath, database.downloads.getByEpisodeId("same-title-1")?.filePath)
            assertEquals(second.absolutePath, database.downloads.getByEpisodeId("same-title-2")?.filePath)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testMaliciousAndReservedTitlesStayInsideDownloadDirectory() {
        val root = testDownloadsDir.canonicalFile.toPath()
        val malicious = getDownloadFile(
            origin = "https://example.com/feed.xml",
            audioUrl = "https://example.com/audio.mp3",
            episodeTitle = "..",
            podcastTitle = "..",
            episodeId = "malicious-episode"
        )
        val reserved = getDownloadFile(
            origin = "https://example.com/feed.xml",
            audioUrl = "https://example.com/audio.mp3",
            episodeTitle = "NUL",
            podcastTitle = "CON",
            episodeId = "reserved-episode"
        )

        assertTrue(malicious.canonicalFile.toPath().startsWith(root))
        assertTrue(reserved.canonicalFile.toPath().startsWith(root))
        assertTrue(malicious.parentFile.name.startsWith("podcast-"))
        assertTrue(malicious.name.startsWith("episode-"))
        assertTrue(reserved.parentFile.name.startsWith("podcast-"))
        assertTrue(reserved.name.startsWith("episode-"))
    }

    // ── Pause tests ──

    @Test
    fun testPauseDownloadCreatesTaskRecord() = runBlocking {
        val bytes = ByteArray(16 * 1024) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/audio.mp3") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            var paused = false
            val result = downloadManager.downloadEpisode(
                episodeId = "pause-test-ep",
                audioUrl = "http://127.0.0.1:${server.address.port}/audio.mp3",
                origin = "https://example.com/pause-test.xml",
                episodeTitle = "Pause Test Episode",
                podcastTitle = "Pause Test Podcast",
                isPaused = { paused }
            ) { current, _ ->
                if (current > 0) paused = true
            }

            assertTrue(result.isFailure, "Paused download should return failure")
            assertEquals("Download paused", result.exceptionOrNull()?.message)

            val task = database.downloadTasks.getByEpisodeId("pause-test-ep")
            assertNotNull(task, "Paused download should have a task record in DB")
            assertEquals("PAUSED", task.state, "Task state should be PAUSED")
            assertTrue(task.downloadedBytes > 0, "Should have downloaded some bytes before pausing")

            val partialFile = File(task.targetFilePath)
            assertTrue(partialFile.exists(), "Partial file should exist after pause")
            assertTrue(partialFile.length() > 0, "Partial file should have some content")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testResumeRestartsWhenServerIgnoresRange() = runBlocking {
        val bytes = "complete audio".toByteArray()
        var receivedRange: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/audio.mp3") { exchange ->
                receivedRange = exchange.requestHeaders.getFirst("Range")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val partialDir = File(testDownloadsDir, "Range Podcast")
            partialDir.mkdirs()
            val partialFile = File(partialDir, "Range Episode.mp3")
            partialFile.writeText("part")

            val now = System.currentTimeMillis()
            database.downloadTasks.insert(DownloadTask(
                episodeId = "range-restart-ep",
                origin = "https://example.com/range.xml",
                audioUrl = "http://127.0.0.1:${server.address.port}/audio.mp3",
                podcastTitle = "Range Podcast",
                episodeTitle = "Range Episode",
                targetFilePath = partialFile.absolutePath,
                downloadedBytes = partialFile.length(),
                totalBytes = bytes.size.toLong(),
                state = "PAUSED",
                createdAt = now,
                updatedAt = now
            ))

            val result = downloadManager.resumeDownload("range-restart-ep")

            assertTrue(result.isSuccess, "Resume should restart and complete when server ignores Range")
            val downloadedFile = result.getOrThrow()
            assertEquals("bytes=4-", receivedRange)
            assertEquals(partialFile.absolutePath, downloadedFile.absolutePath)
            assertContentEquals(bytes, downloadedFile.readBytes())
            assertEquals(bytes.size.toLong(), downloadedFile.length())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testCancelDownloadCleansUp() = runBlocking {
        val bytes = "cancel test audio".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/audio.mp3") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val result = downloadManager.downloadEpisode(
                episodeId = "cancel-test-ep",
                audioUrl = "http://127.0.0.1:${server.address.port}/audio.mp3",
                origin = "https://example.com/cancel-test.xml",
                episodeTitle = "Cancel Test",
                podcastTitle = "Cancel Podcast"
            ) { _, _ ->
                downloadManager.cancelDownload("cancel-test-ep")
            }

            assertTrue(result.isFailure)
            assertEquals("Download cancelled", result.exceptionOrNull()?.message)

            val record = database.downloads.getByEpisodeId("cancel-test-ep")
            assertNull(record, "No download record should exist after cancel")

            val task = database.downloadTasks.getByEpisodeId("cancel-test-ep")
            assertNull(task, "No task record should remain after cancel")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testCleanupPausedTaskRemovesPartialFileAndRecord() = runBlocking {
        // Arrange: insert a simulated paused task with a partial file
        val partialDir = File(testDownloadsDir, "Cleanup Podcast")
        partialDir.mkdirs()
        val partialFile = File(partialDir, "Cleanup_Episode.mp3")
        partialFile.writeText("partial content")
        partialFile.deleteOnExit()

        val now = System.currentTimeMillis()
        val task = app.podara.data.DownloadTask(
            episodeId = "cleanup-ep",
            origin = "https://example.com/cleanup.xml",
            audioUrl = "https://example.com/cleanup.mp3",
            podcastTitle = "Cleanup Podcast",
            episodeTitle = "Cleanup Episode",
            targetFilePath = partialFile.absolutePath,
            downloadedBytes = 500,
            totalBytes = 1000,
            state = "PAUSED",
            createdAt = now,
            updatedAt = now
        )
        database.downloadTasks.insert(task)
        assertNotNull(database.downloadTasks.getByEpisodeId("cleanup-ep"), "Task should exist")
        assertTrue(partialFile.exists(), "Partial file should exist")

        // Act
        downloadManager.cleanupPausedTask("cleanup-ep")

        // Assert
        assertNull(database.downloadTasks.getByEpisodeId("cleanup-ep"), "Task should be removed")
        assertFalse(partialFile.exists(), "Partial file should be deleted")
    }

    @Test
    fun testCleanupPausedTaskWithMissingFile() = runBlocking {
        // Arrange: task record but no partial file
        val now = System.currentTimeMillis()
        val task = app.podara.data.DownloadTask(
            episodeId = "missing-cleanup",
            origin = "https://example.com/missing-cleanup.xml",
            audioUrl = "https://example.com/missing-cleanup.mp3",
            podcastTitle = "Missing",
            episodeTitle = "Missing",
            targetFilePath = File(testDownloadsDir, "nonexistent.mp3").absolutePath,
            downloadedBytes = 100,
            totalBytes = 200,
            state = "PAUSED",
            createdAt = now,
            updatedAt = now
        )
        database.downloadTasks.insert(task)

        // Act — should not throw
        downloadManager.cleanupPausedTask("missing-cleanup")

        // Assert
        assertNull(database.downloadTasks.getByEpisodeId("missing-cleanup"), "Task should be removed")
    }

    // ── Delete tests ──

    @Test
    fun testDeleteDownloadedEpisodeRemovesFileAndRecord() = runBlocking {
        // Arrange: simulate a completed download by inserting a record + file
        val testFile = File(testDownloadsDir, "delete-test-song.mp3")
        testFile.writeText("test audio content")
        testFile.deleteOnExit()

        val testEpisodeId = "delete-test-ep"
        database.downloads.insert(
            episodeId = testEpisodeId,
            origin = "https://example.com/delete-test.xml",
            filePath = testFile.absolutePath,
            podcastTitle = "Delete Test Podcast",
            episodeTitle = "Delete Test Episode"
        )
        assertTrue(database.downloads.getByEpisodeId(testEpisodeId) != null, "Record should exist")
        assertTrue(testFile.exists(), "File should exist")

        // Act: delete via DownloadManager
        val deleted = downloadManager.deleteDownloadedEpisode(testEpisodeId)

        // Assert
        assertTrue(deleted, "deleteDownloadedEpisode should return true")
        assertFalse(testFile.exists(), "File should be deleted")
        assertNull(database.downloads.getByEpisodeId(testEpisodeId), "Record should be deleted")
    }

    @Test
    fun testDeleteDownloadedEpisodeWithMissingFile() = runBlocking {
        // Arrange: record points to non-existent file
        val testEpisodeId = "missing-file-delete"
        database.downloads.insert(
            episodeId = testEpisodeId,
            origin = "https://example.com/missing.xml",
            filePath = File(testDownloadsDir, "does-not-exist.mp3").absolutePath,
            podcastTitle = "Missing Podcast",
            episodeTitle = "Missing Episode"
        )
        assertTrue(database.downloads.getByEpisodeId(testEpisodeId) != null, "Record should exist")

        // Act
        val deleted = downloadManager.deleteDownloadedEpisode(testEpisodeId)

        // Assert: should still succeed and remove record
        assertTrue(deleted, "Should return true even when file missing")
        assertNull(database.downloads.getByEpisodeId(testEpisodeId), "Record should be deleted")
    }

    @Test
    fun testDeleteByOriginRemovesAllRecordsAndFiles() = runBlocking {
        // Arrange: create multiple download records for the same origin
        val origin = "https://example.com/batch-podcast.xml"
        val files = listOf(
            createTestDownload("batch-ep-1", origin, "Episode 1"),
            createTestDownload("batch-ep-2", origin, "Episode 2"),
            createTestDownload("batch-ep-3", origin, "Episode 3")
        )

        // Verify all records and files exist
        assertEquals(3, database.downloads.getAllByOrigin(origin).size)
        files.forEach { assertTrue(it.exists(), "File should exist before deletion") }

        // Act
        val count = downloadManager.deleteDownloadedByOrigin(origin)

        // Assert
        assertEquals(3, count, "Should delete 3 records")
        assertEquals(0, database.downloads.getAllByOrigin(origin).size, "No records should remain")
        files.forEach { assertFalse(it.exists(), "File should be deleted") }
    }

    @Test
    fun testDeleteByOriginWithEmptyOrigin() = runBlocking {
        val count = downloadManager.deleteDownloadedByOrigin("https://example.com/nonexistent.xml")
        assertEquals(0, count, "Should return 0 for non-existent origin")
    }

    @Test
    fun testGetAllValidFiltersStaleRecords() = runBlocking {
        // Arrange: one valid download + one stale
        val validFile = File(testDownloadsDir, "valid-song.mp3")
        validFile.writeText("real content")
        validFile.deleteOnExit()

        database.downloads.insert("valid-ep", "origin-1", validFile.absolutePath, "Podcast A", "Valid Episode")
        database.downloads.insert("stale-ep", "origin-1", File(testDownloadsDir, "nonexistent.mp3").absolutePath, "Podcast A", "Stale Episode")

        // Act
        val validList = database.downloads.getAllValid()

        // Assert
        assertEquals(1, validList.size, "Only valid records should remain")
        assertEquals("valid-ep", validList[0].episodeId)
        assertNull(database.downloads.getByEpisodeId("stale-ep"), "Stale record should be cleaned up")
    }

    @Test
    fun testGetTotalDownloadedBytes() = runBlocking {
        // Arrange: create two downloaded files with known sizes
        val file1 = File(testDownloadsDir, "song1.mp3").apply { writeText("A") }
        val file2 = File(testDownloadsDir, "song2.mp3").apply { writeText("BB") }
        file1.deleteOnExit(); file2.deleteOnExit()

        database.downloads.insert("ep-1", "origin-x", file1.absolutePath, "Podcast X", "Ep 1")
        database.downloads.insert("ep-2", "origin-x", file2.absolutePath, "Podcast X", "Ep 2")

        // Act
        val total = database.downloads.getTotalDownloadedBytes()

        // Assert: each char = 1 byte in UTF-8
        assertEquals(3L, total, "Total should equal sum of file lengths")
    }

    // ── Download task DAO tests ──

    @Test
    fun testDownloadTaskCrud() = runBlocking {
        val now = System.currentTimeMillis()
        val task = DownloadTask(
            episodeId = "task-ep", origin = "origin-task",
            audioUrl = "https://example.com/audio.mp3",
            podcastTitle = "Task Podcast", episodeTitle = "Task Episode",
            targetFilePath = "/tmp/task-test.mp3", downloadedBytes = 500, totalBytes = 1000,
            state = "DOWNLOADING", createdAt = now, updatedAt = now
        )

        // Create
        database.downloadTasks.insert(task)
        var loaded = database.downloadTasks.getByEpisodeId("task-ep")
        assertNotNull(loaded)
        assertEquals(500, loaded!!.downloadedBytes)
        assertEquals("DOWNLOADING", loaded.state)

        // Update progress
        database.downloadTasks.updateProgress("task-ep", 750, 1000)
        loaded = database.downloadTasks.getByEpisodeId("task-ep")
        assertEquals(750, loaded!!.downloadedBytes)

        // Update state
        database.downloadTasks.updateState("task-ep", "PAUSED")
        loaded = database.downloadTasks.getByEpisodeId("task-ep")
        assertEquals("PAUSED", loaded!!.state)

        // Get all active (state != COMPLETED)
        val activeTasks = database.downloadTasks.getAllActive()
        assertTrue(activeTasks.any { it.episodeId == "task-ep" })

        // Delete
        database.downloadTasks.delete("task-ep")
        assertNull(database.downloadTasks.getByEpisodeId("task-ep"))
    }

    @Test
    fun testActiveTasksHaveStableCreationOrder() = runBlocking {
        database.downloadTasks.insert(DownloadTask(
            episodeId = "older-task", origin = "origin", audioUrl = "https://example.com/older.mp3",
            podcastTitle = "Podcast", episodeTitle = "Older", state = "PAUSED",
            createdAt = 1L, updatedAt = 1L
        ))
        database.downloadTasks.insert(DownloadTask(
            episodeId = "newer-task", origin = "origin", audioUrl = "https://example.com/newer.mp3",
            podcastTitle = "Podcast", episodeTitle = "Newer", state = "PAUSED",
            createdAt = 2L, updatedAt = 2L
        ))

        database.downloadTasks.updateState("older-task", "FAILED")

        assertEquals(
            listOf("newer-task", "older-task"),
            database.downloadTasks.getAllActive().map { it.episodeId }
        )
    }

    // ── Helpers ──

    /** [DownloadNaming.buildDownloadFile] with the episode ID defaulting to the audio URL. */
    private fun getDownloadFile(
        origin: String,
        audioUrl: String,
        episodeTitle: String = "",
        podcastTitle: String = "",
        episodeId: String = audioUrl
    ): File = DownloadNaming.buildDownloadFile(
        testDownloadsDir, origin, episodeId, audioUrl, episodeTitle, podcastTitle
    )

    /** Create a test download record + dummy file, return the file reference. */
    private suspend fun createTestDownload(episodeId: String, origin: String, episodeTitle: String): File {
        val file = File(testDownloadsDir, "$episodeId.mp3")
        file.writeText("dummy content for $episodeId")
        file.deleteOnExit()
        database.downloads.insert(
            episodeId = episodeId, origin = origin,
            filePath = file.absolutePath, podcastTitle = "Batch Podcast",
            episodeTitle = episodeTitle
        )
        return file
    }
}
