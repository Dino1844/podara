package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.manager.DownloadManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pausing or cancelling must take effect while the transfer is blocked in a
 * read, not one readTimeout later.
 *
 * The manager used to look at the pause/cancel flags only between
 * `inputStream.read()` calls. A server that sends one chunk and then keeps the
 * socket open therefore kept the UI reporting "cancelling…" for the full 30 s
 * readTimeout. The manager now disconnects the live connection, which makes the
 * blocked read throw immediately; the transfer's error path recognises the flag
 * and performs its normal cleanup.
 *
 * These tests use a local HTTP server whose handler writes one chunk and then
 * holds the connection open without sending anything else.
 */
class DownloadCancelTest {

    private lateinit var database: AppDatabase
    private lateinit var downloadManager: DownloadManager
    private lateinit var testDbFile: File
    private lateinit var testDownloadsDir: File

    @BeforeTest
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podara_cancel_test_${System.nanoTime()}.db")
        testDbFile.deleteOnExit()
        testDownloadsDir = File(System.getProperty("java.io.tmpdir"), "podara_cancel_dl_${System.nanoTime()}")
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
    fun testCancelAbortsABlockedReadInsteadOfWaitingForTheReadTimeout() = runBlocking {
        withStalledServer { server, release ->
            val download = startStalledDownload(server, "cancel-abort-ep")

            val startedAt = System.nanoTime()
            downloadManager.cancelDownload("cancel-abort-ep")
            val result = withTimeout(5_000) { download.await() }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(result.isFailure, "a cancelled download is not a success")
            assertEquals("Download cancelled", result.exceptionOrNull()?.message)
            assertTrue(
                elapsedMillis < 3_000,
                "cancel took ${elapsedMillis}ms; the blocked read was not aborted (readTimeout is 30s)"
            )
            assertNull(
                database.downloadTasks.getByEpisodeId("cancel-abort-ep"),
                "a cancelled download must not leave a task row"
            )
            assertTrue(
                noPartFilesLeft(),
                "a cancelled download must not leave partial files: ${listFiles()}"
            )
            release.countDown()
        }
    }

    @Test
    fun testPauseAbortsABlockedReadAndKeepsThePartial() = runBlocking {
        withStalledServer { server, release ->
            val download = startStalledDownload(server, "pause-abort-ep")

            val startedAt = System.nanoTime()
            downloadManager.pauseDownload("pause-abort-ep")
            val result = withTimeout(5_000) { download.await() }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(result.isFailure, "a paused download is not a success")
            assertEquals("Download paused", result.exceptionOrNull()?.message)
            assertTrue(
                elapsedMillis < 3_000,
                "pause took ${elapsedMillis}ms; the blocked read was not aborted (readTimeout is 30s)"
            )

            val task = assertNotNull(database.downloadTasks.getByEpisodeId("pause-abort-ep"))
            assertEquals("PAUSED", task.state, "a paused transfer must be recorded as PAUSED, not FAILED")
            assertTrue(task.downloadedBytes > 0, "the received bytes should be recorded")
            val partial = File(task.targetFilePath)
            assertTrue(partial.isFile, "the partial must be parked where resumeDownload looks for it")
            assertTrue(partial.length() > 0, "the parked partial should contain the received bytes")
            release.countDown()
        }
    }

    @Test
    fun testSlowButSteadyTransferStillCompletes() = runBlocking {
        // The short read timeout must not turn a slow-but-healthy transfer into
        // a failure: this server sends a chunk every 250 ms, comfortably inside
        // the 1 s wake-up interval, and the download has to finish normally.
        val chunk = ByteArray(64) { it.toByte() }
        val chunks = 8
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/slow.mp3") { exchange ->
                exchange.sendResponseHeaders(200, (chunk.size * chunks).toLong())
                exchange.responseBody.use { body ->
                    repeat(chunks) {
                        body.write(chunk)
                        body.flush()
                        Thread.sleep(250)
                    }
                }
            }
            this.executor = executor
            start()
        }

        try {
            val result = downloadManager.downloadEpisode(
                episodeId = "slow-steady-ep",
                audioUrl = "http://127.0.0.1:${server.address.port}/slow.mp3",
                origin = "https://example.com/slow-steady.xml",
                episodeTitle = "Slow Steady",
                podcastTitle = "Slow Steady Podcast"
            )

            assertTrue(result.isSuccess, "a slow but progressing transfer must not be treated as stalled")
            assertEquals((chunk.size * chunks).toLong(), result.getOrThrow().length())
            assertNotNull(database.downloads.getByEpisodeId("slow-steady-ep"))
            Unit
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    /**
     * Starts a download and returns it once the client has received its first
     * chunk — at which point the next `read()` is blocked on the still-open but
     * silent socket, which is the state both tests need to interrupt.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.startStalledDownload(
        server: HttpServer,
        episodeId: String
    ): kotlinx.coroutines.Deferred<Result<File>> {
        val firstBytes = CompletableDeferred<Unit>()
        val download = async(Dispatchers.IO) {
            downloadManager.downloadEpisode(
                episodeId = episodeId,
                audioUrl = "http://127.0.0.1:${server.address.port}/stalled.mp3",
                origin = "https://example.com/stalled.xml",
                episodeTitle = "Stalled Episode",
                podcastTitle = "Stalled Podcast",
                onProgress = { current, _ -> if (current > 0) firstBytes.complete(Unit) }
            )
        }
        withTimeout(10_000) { firstBytes.await() }
        // Let the client task get from the progress callback back into read().
        delay(150)
        return download
    }

    private suspend fun withStalledServer(
        block: suspend (HttpServer, CountDownLatch) -> Unit
    ) {
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/stalled.mp3") { exchange ->
                try {
                    exchange.sendResponseHeaders(200, STALLED_BODY_BYTES)
                    exchange.responseBody.use { body ->
                        body.write(ByteArray(FIRST_CHUNK_BYTES))
                        body.flush()
                        // Hold the connection open without sending the rest:
                        // this is the "blocked read" the fix has to interrupt.
                        release.await(20, TimeUnit.SECONDS)
                    }
                } catch (_: Exception) {
                    // The client disconnecting mid-body is the expected end of
                    // this test; the handler has nothing left to do.
                }
            }
            this.executor = executor
            start()
        }

        try {
            block(server, release)
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun listFiles(): List<String> = testDownloadsDir.walkTopDown()
        .filter { it.isFile }
        .map { it.name }
        .toList()

    private fun noPartFilesLeft(): Boolean = listFiles().none { it.endsWith(".part") }

    private companion object {
        const val STALLED_BODY_BYTES = 8L * 1024 * 1024
        const val FIRST_CHUNK_BYTES = 8 * 1024
    }
}
