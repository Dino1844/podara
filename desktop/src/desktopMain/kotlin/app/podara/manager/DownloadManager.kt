package app.podara.manager

import app.podara.APP_VERSION
import app.podara.data.AppDatabase
import app.podara.data.DownloadTask
import app.podara.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DownloadManager"

/**
 * User-Agent sent with download requests.
 *
 * Some hosts return 403 to the JDK's default agent, and a recognisable name
 * with a project URL is what podcast-hosting providers expect from a client.
 *
 * Built from [APP_VERSION] rather than spelled out: this literal previously said
 * 1.0.3 while the app moved on, so downloads identified themselves as an older
 * release than the one making them. Version strings belong in one place.
 */
internal const val USER_AGENT = "Podara/$APP_VERSION (+https://github.com/kazimics/podara)"

/**
 * Raised when a download for the same episode is already running.
 *
 * Distinct from a transfer failure: the caller should treat it as "already
 * handled" rather than surfacing an error to the user.
 */
class AlreadyDownloadingException(episodeId: String) :
    Exception("A download is already in progress for $episodeId")

/**
 * Raised when the server answers with something that is not audio.
 *
 * Podcast hosts sometimes serve an HTML error page with a 200, or a login wall
 * with a redirect; writing those bytes to a `.mp3` and recording it as a
 * finished download produced files that looked fine in the UI and failed only
 * when played.
 */
class NotAudioException(message: String) : Exception(message)

/**
 * Whether a Content-Type is definitely not audio.
 *
 * Deliberately conservative: an absent or generic type (`application/octet-
 * stream`, `binary/octet-stream`) is allowed through, because plenty of CDNs
 * serve audio that way and rejecting it would break working downloads. Only
 * types that are unambiguously markup or plain text are refused.
 */
internal fun isObviouslyNotAudio(contentType: String): Boolean {
    val type = contentType.substringBefore(';').trim().lowercase()
    if (type.startsWith("audio/")) return false
    if (type.startsWith("video/")) return false          // mp4/m4a containers
    if (type.startsWith("application/ogg")) return false
    return type == "text/html" ||
        type == "text/plain" ||
        type == "application/xhtml+xml" ||
        type == "application/json" ||
        type == "application/xml" ||
        type == "text/xml"
}

class DownloadManager(
    private val db: AppDatabase,
    private var downloadsDir: File,
    private var speedLimitKbps: Int = 0
) {
    // ── Thread-safe pause/cancel tracking ──
    private val pausedDownloads = ConcurrentHashMap.newKeySet<String>()
    private val cancelledDownloads = ConcurrentHashMap.newKeySet<String>()

    /**
     * Episodes with a transfer in flight.
     *
     * The UI's `downloadingEpisodes` guard reads a Compose snapshot, so two
     * calls in the same frame both pass it. Without a real mutex here they
     * would open two streams onto the same path in overwrite mode, interleave
     * their bytes, and both report success. A failed acquire means "someone is
     * already downloading this one", which is exactly the caller's intent.
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** True while [episodeId] has a transfer running in this manager instance. */
    fun isDownloading(episodeId: String): Boolean = episodeId in inFlight

    /**
     * Retargets an existing manager at a new download directory or speed limit.
     *
     * Changing either setting used to rebuild the whole manager from a
     * `remember(downloadPath, speedLimitKbps)`, which stranded every running
     * transfer: pause/cancel flags live in this instance, so the UI's calls
     * went to the new object while the old one kept writing. Mutating the
     * existing instance keeps the flags and the in-flight set intact.
     */
    fun reconfigure(downloadsDir: File, speedLimitKbps: Int) {
        this.downloadsDir = downloadsDir
        this.speedLimitKbps = speedLimitKbps
        downloadsDir.mkdirs()
    }

    /**
     * Download an episode from [audioUrl] and save to local storage.
     * Supports pause via [isPaused] callback and HTTP Range resume.
     */
    suspend fun downloadEpisode(
        episodeId: String,
        audioUrl: String,
        origin: String,
        episodeTitle: String = episodeId,
        podcastTitle: String = "Unknown",
        isPaused: () -> Boolean = { episodeId in pausedDownloads },
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        // Claim this episode before doing anything else. Released in finally.
        if (!inFlight.add(episodeId)) {
            Logger.w(TAG, "Download already in flight for $episodeId; ignoring duplicate request")
            return@withContext Result.failure(AlreadyDownloadingException(episodeId))
        }
        try {
            downloadEpisodeLocked(
                episodeId = episodeId,
                audioUrl = audioUrl,
                origin = origin,
                episodeTitle = episodeTitle,
                podcastTitle = podcastTitle,
                isPaused = isPaused,
                onProgress = onProgress
            )
        } finally {
            inFlight.remove(episodeId)
        }
    }

    private suspend fun downloadEpisodeLocked(
        episodeId: String,
        audioUrl: String,
        origin: String,
        episodeTitle: String,
        podcastTitle: String,
        isPaused: () -> Boolean,
        onProgress: ((Long, Long) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // A task owns its target path for its entire lifetime. In particular, do not
            // derive a different path after a title changes while a download is paused.
            val existingTask = db.downloadTasks.getByEpisodeId(episodeId)
            val generatedFile = DownloadNaming.buildDownloadFile(downloadsDir, origin, episodeId, audioUrl, episodeTitle, podcastTitle)
            val storedTarget = existingTask
                ?.targetFilePath
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { DownloadNaming.isInsideDownloadsDir(downloadsDir, it) }
            val outputFile = storedTarget ?: generatedFile
            val podcastDir = outputFile.parentFile
            podcastDir.mkdirs()

            // Check if we have a paused task to resume from
            var downloadedBytes = 0L
            var totalBytes = 0L

            if (
                existingTask != null && storedTarget != null &&
                existingTask.state == "PAUSED" && existingTask.downloadedBytes > 0 &&
                outputFile.isFile
            ) {
                // Resume from partial download
                downloadedBytes = existingTask.downloadedBytes
                totalBytes = existingTask.totalBytes
                db.downloadTasks.updateState(episodeId, "DOWNLOADING")
                Logger.i(TAG, "Resuming download from byte $downloadedBytes: ${outputFile.absolutePath}")
            } else {
                // Fresh download — create task record
                val now = System.currentTimeMillis()
                db.downloadTasks.insert(DownloadTask(
                    episodeId = episodeId, origin = origin, audioUrl = audioUrl,
                    podcastTitle = podcastTitle, episodeTitle = episodeTitle,
                    targetFilePath = outputFile.absolutePath, downloadedBytes = 0, totalBytes = 0,
                    state = "DOWNLOADING", createdAt = now, updatedAt = now
                ))
                // Delete partial file if exists
                if (outputFile.exists()) outputFile.delete()
            }

            Logger.i(TAG, "Downloading to: ${outputFile.absolutePath}")
            onProgress?.invoke(downloadedBytes, totalBytes)

            val url = URL(audioUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            // Some podcast hosts reject requests without a browser-ish agent,
            // and identifying ourselves is the convention for podcast clients.
            connection.setRequestProperty("User-Agent", USER_AGENT)

            // HttpURLConnection has no close(); without disconnect() the
            // responseCode/stream failure paths leaked the socket.
            try {

                // Set Range header for resume
                if (downloadedBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                }

                val responseCode = connection.responseCode

                // A non-2xx is a failure, not a file. Previously this was only
                // consulted to decide whether Range was honoured, so a 404 or
                // 500 body was written to disk and recorded as a download.
                val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
                val isFull = responseCode == HttpURLConnection.HTTP_OK
                if (!isPartial && !isFull) {
                    val body = try {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                    } catch (_: Exception) {
                        null
                    }
                    throw Exception("HTTP $responseCode${body?.let { ": $it" } ?: ""}")
                }

                if (downloadedBytes > 0 && !isPartial) {
                    Logger.w(TAG, "Server did not honor Range request (HTTP $responseCode); restarting download")
                    downloadedBytes = 0L
                    totalBytes = 0L
                    if (outputFile.exists()) outputFile.delete()
                }

                // Refuse anything that is plainly not audio. Hosts that answer
                // with an HTML error page or a redirect to a login form would
                // otherwise leave a corrupt file that only fails at play time.
                val contentType = connection.contentType?.lowercase()
                if (contentType != null && isObviouslyNotAudio(contentType)) {
                    throw NotAudioException("Server returned $contentType for $audioUrl")
                }

                val contentLength = connection.contentLength.toLong()
                if (totalBytes == 0L && contentLength > 0L) totalBytes = contentLength
                if (totalBytes == 0L && downloadedBytes > 0) {
                    // Server may not return content-length with Range — use original task total
                    totalBytes = existingTask?.totalBytes ?: 0L
                }

                Logger.i(TAG, "HTTP $responseCode, Content-Length: $contentLength, total: $totalBytes, resume offset: $downloadedBytes")

                // Open input stream (for Range response, server returns 206 — inputStream reads from offset)
                val inputStream = connection.inputStream
                val buffer = ByteArray(8192)

                val fileOutputStream = if (downloadedBytes > 0) {
                    java.io.FileOutputStream(outputFile, true) // append mode for resume
                } else {
                    outputFile.outputStream() // overwrite for fresh download
                }

                // Per-task rate limiter
                val rateLimiter = if (speedLimitKbps > 0) {
                    RateLimiter(speedLimitKbps.toLong() * 1024)
                } else null

                try {
                    while (true) {
                        if (cancelledDownloads.remove(episodeId)) {
                            // Cancelled — clean up partial file
                            fileOutputStream.close()
                            inputStream.close()
                            outputFile.delete()
                            db.downloadTasks.delete(episodeId)
                            Logger.i(TAG, "Download cancelled: $episodeId")
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }

                        if (isPaused()) {
                            // Paused — save state and keep partial file
                            fileOutputStream.close()
                            inputStream.close()
                            pausedDownloads.remove(episodeId) // clear pause flag so future pause works correctly
                            db.downloadTasks.updateProgress(episodeId, downloadedBytes, totalBytes)
                            db.downloadTasks.updateState(episodeId, "PAUSED")
                            Logger.i(TAG, "Download paused: $episodeId (${downloadedBytes}/${totalBytes})")
                            return@withContext Result.failure(Exception("Download paused"))
                        }

                        if (!isActive) {
                            // Coroutine cancelled externally
                            fileOutputStream.close()
                            inputStream.close()
                            outputFile.delete()
                            db.downloadTasks.delete(episodeId)
                            Logger.i(TAG, "Download cancelled (coroutine): $episodeId")
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }

                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        fileOutputStream.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress?.invoke(downloadedBytes, totalBytes)
                        rateLimiter?.throttle(read) { episodeId in pausedDownloads || episodeId in cancelledDownloads }
                    }
                } finally {
                    fileOutputStream.close()
                    inputStream.close()
                }

                Logger.i(TAG, "Download complete: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                db.downloads.insert(
                    episodeId = episodeId, origin = origin,
                    filePath = outputFile.absolutePath,
                    podcastTitle = podcastTitle, episodeTitle = episodeTitle
                )
                db.downloadTasks.delete(episodeId) // clean up task
                cancelledDownloads.remove(episodeId)
                pausedDownloads.remove(episodeId)
                Result.success(outputFile)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Download failed: $audioUrl", e)
            cancelledDownloads.remove(episodeId)
            pausedDownloads.remove(episodeId)
            // Mark task as FAILED (not cancelled/paused)
            try {
                val task = db.downloadTasks.getByEpisodeId(episodeId)
                if (task != null && task.state == "DOWNLOADING") {
                    db.downloadTasks.updateState(episodeId, "FAILED")
                }
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /** Pause a running download — keeps partial file on disk. */
    fun pauseDownload(episodeId: String) {
        pausedDownloads.add(episodeId)
    }

    /** Resume a previously paused download. Returns success if download completes. */
    suspend fun resumeDownload(
        episodeId: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val task = db.downloadTasks.getByEpisodeId(episodeId)
            ?: return@withContext Result.failure(Exception("No paused task found for $episodeId"))

        val partialFile = File(task.targetFilePath)
        if (!partialFile.exists()) {
            // Partial file was deleted — restart from scratch
            db.downloadTasks.delete(episodeId)
            return@withContext downloadEpisode(
                episodeId = task.episodeId, audioUrl = task.audioUrl,
                origin = task.origin, episodeTitle = task.episodeTitle,
                podcastTitle = task.podcastTitle, onProgress = onProgress
            )
        }

        // Clear the pause flag so downloadEpisode doesn't immediately pause again
        pausedDownloads.remove(episodeId)

        Logger.i(TAG, "Resuming download: $episodeId from byte ${task.downloadedBytes}")

        // Delegate to downloadEpisode which handles resume logic
        downloadEpisode(
            episodeId = task.episodeId, audioUrl = task.audioUrl,
            origin = task.origin, episodeTitle = task.episodeTitle,
            podcastTitle = task.podcastTitle, onProgress = onProgress
        )
    }

    /**
     * Delete a downloaded episode: remove the file, the DB record, and any lingering task.
     *
     * The file delete result is checked now. On Windows a delete fails while
     * the file is open — most obviously when it is the episode being played —
     * and the old code logged "Deleted" regardless, then dropped the DB record.
     * The file stayed on disk forever with nothing left pointing at it.
     *
     * @return true if the record was found and deleted (file may already have been missing).
     */
    suspend fun deleteDownloadedEpisode(episodeId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val record = db.downloads.getByEpisodeId(episodeId)
            if (record != null) {
                val file = File(record.filePath)
                if (file.exists()) {
                    if (file.delete()) {
                        Logger.i(TAG, "Deleted file: ${file.absolutePath}")
                    } else {
                        // Keep the record so the user can retry once whatever
                        // holds the file (usually playback) lets go of it.
                        Logger.w(TAG, "Could not delete file, keeping the record: ${file.absolutePath}")
                        return@withContext false
                    }
                }
            }
            db.downloads.delete(episodeId)
            db.downloadTasks.delete(episodeId)
            cancelledDownloads.remove(episodeId)
            pausedDownloads.remove(episodeId)
            Logger.i(TAG, "Deleted download record: $episodeId")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete download: $episodeId", e)
            false
        }
    }

    /**
     * Delete all downloaded episodes for a podcast origin:
     * removes files, DB records, and any lingering tasks.
     * @return number of records deleted.
     */
    suspend fun deleteDownloadedByOrigin(origin: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val records = db.downloads.getAllByOrigin(origin)
            for (record in records) {
                val file = File(record.filePath)
                if (file.exists()) {
                    file.delete()
                }
                db.downloads.delete(record.episodeId)
                db.downloadTasks.delete(record.episodeId)
                cancelledDownloads.remove(record.episodeId)
                pausedDownloads.remove(record.episodeId)
                count++
            }
            Logger.i(TAG, "Deleted $count downloads for origin: $origin")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete downloads for: $origin", e)
        }
        count
    }

    /** Cancel an active download. Cleans up the partial file and DB task immediately. */
    fun cancelDownload(episodeId: String) {
        cancelledDownloads.add(episodeId)
        pausedDownloads.remove(episodeId)
    }

    /**
     * Clean up a paused or failed download task: removes the partial file,
     * the DB task record, and any lingering download record.
     * This is needed when the user cancels a task that is already paused/failed
     * (where the download coroutine has already finished).
     */
    suspend fun cleanupPausedTask(episodeId: String) {
        try {
            val task = db.downloadTasks.getByEpisodeId(episodeId)
            if (task != null) {
                val partialFile = File(task.targetFilePath)
                if (partialFile.exists()) {
                    partialFile.delete()
                    Logger.i(TAG, "Cleaned up partial file: ${partialFile.absolutePath}")
                }
                db.downloadTasks.delete(episodeId)
            }
            db.downloads.delete(episodeId)
            cancelledDownloads.remove(episodeId)
            pausedDownloads.remove(episodeId)
            Logger.i(TAG, "Cleaned up paused task: $episodeId")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to clean up paused task: $episodeId", e)
        }
    }
}
