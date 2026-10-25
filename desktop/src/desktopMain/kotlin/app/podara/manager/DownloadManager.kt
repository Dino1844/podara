package app.podara.manager

import app.podara.APP_VERSION
import app.podara.data.AppDatabase
import app.podara.data.DownloadTask
import app.podara.data.PodcastDownload
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
 * Suffix for a transfer that is still in flight.
 *
 * Downloads stream into `<target>.part` and are moved onto the target only when
 * the full body has arrived, so the target path never holds a truncated file.
 */
private const val PARTIAL_SUFFIX = ".part"

/**
 * How long a blocked read waits before the transfer re-checks pause/cancel.
 *
 * `HttpURLConnection.disconnect()` does not unblock a read that is already in
 * progress — the JDK only closes a connection that is idle at that moment — so
 * the flags were previously observed only between reads, and a paused download
 * could keep streaming for the full 30 s readTimeout. A read that timed out
 * consumes nothing, so a short timeout is a safe wake-up tick: the next read
 * still returns the same bytes.
 */
private const val READ_POLL_TIMEOUT_MS = 1_000

/** How long to keep waiting for a response head before failing the request. */
private const val RESPONSE_HEAD_TIMEOUT_MS = 30_000

/** Longest single wait for a response head once retries have started. */
private const val RESPONSE_HEAD_RETRY_TIMEOUT_MS = 5_000

/** A transfer that receives no bytes at all for this long is dead, not slow. */
private const val STALL_TIMEOUT_MS = 60_000

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

/**
 * Which of [downloads] the "keep latest N" policy deletes this round.
 *
 * The ordering key is the download's own `timestamp` — when the file landed,
 * written once by `DownloadDao.insert` and refreshed on a re-download — not the
 * episode's publish date: the policy retains the *N most recent downloads*, so
 * an old episode the user fetched deliberately moments ago is the newest thing
 * they have, not the first thing to go. `episodeId` breaks timestamp ties so
 * two downloads in the same millisecond cannot swap which of them survives.
 *
 * Episode ids in [protectedEpisodeIds] — currently the episode being played —
 * are exempt for this round and stay on disk and in the database; the next
 * prune reconsiders them. 0 or a negative [keep] disables the cleanup
 * entirely, and podcasts never see each other's rows because callers pass one
 * origin's records at a time.
 *
 * Pure so the policy (ordering, exemption, the N boundary) can be tested
 * without files or a database; [DownloadManager.pruneDownloadsToKeepLimit]
 * owns the side effects.
 */
internal fun downloadsBeyondKeepLimit(
    downloads: List<PodcastDownload>,
    keep: Int,
    protectedEpisodeIds: Set<String>
): List<PodcastDownload> {
    if (keep <= 0 || downloads.size <= keep) return emptyList()
    val newestFirst = downloads.sortedWith(
        compareByDescending<PodcastDownload> { it.timestamp }.thenByDescending { it.episodeId }
    )
    return newestFirst.drop(keep).filterNot { it.episodeId in protectedEpisodeIds }
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

    /**
     * The live connection of each running transfer.
     *
     * Pause/cancel use it on a best-effort basis ([abortInFlightConnection]),
     * but the mechanism that actually bounds their latency is
     * [READ_POLL_TIMEOUT_MS]: a blocked read wakes up on a short timeout and the
     * copy loop re-checks the flags. Kept because closing the socket is still
     * the right thing to do when the connection is between reads, and because
     * `disconnect()` is how the non-2xx and failure paths release the socket.
     */
    private val liveConnections = ConcurrentHashMap<String, HttpURLConnection>()

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
        // Cleanup state is hoisted out of the try block on purpose: a pause or
        // cancel disconnects the live connection, so the blocked read throws and
        // control lands in the catch at the bottom instead of at the loop's flag
        // checks. Both exits have to clean up identically, so they share
        // [finishCancelled] / [finishPaused] and read these values.
        var outputFile: File? = null
        var transferFile: File? = null
        var downloadedBytes = 0L
        var totalBytes = 0L
        try {
            // An episode that is already on disk is not re-downloaded. The old
            // code deleted the target path *before* it had talked to the server,
            // so a re-download that then failed (offline, 404, moved host) left
            // the user with neither the old file nor a new one.
            db.downloads.getByEpisodeId(episodeId)?.let { record ->
                val downloaded = File(record.filePath)
                if (downloaded.isFile) {
                    // A task row here is a leftover from a run killed between
                    // recording the download and clearing the task; the file is
                    // complete, so drop the ghost row and report success.
                    db.downloadTasks.delete(episodeId)
                    Logger.i(TAG, "Already downloaded, keeping existing file: ${downloaded.absolutePath}")
                    return@withContext Result.success(downloaded)
                }
            }

            // A task owns its target path for its entire lifetime. In particular, do not
            // derive a different path after a title changes while a download is paused.
            val existingTask = db.downloadTasks.getByEpisodeId(episodeId)
            val generatedFile = DownloadNaming.buildDownloadFile(downloadsDir, origin, episodeId, audioUrl, episodeTitle, podcastTitle)
            val storedTarget = existingTask
                ?.targetFilePath
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { DownloadNaming.isInsideDownloadsDir(downloadsDir, it) }
            val outputTarget = storedTarget ?: generatedFile
            val podcastDir = outputTarget.parentFile
            podcastDir.mkdirs()
            // Mirror of the target path for the cleanup paths; assigned once, so
            // it cannot drift from `outputTarget`.
            outputFile = outputTarget

            // Bytes are streamed into a sibling `.part` file and only moved onto
            // outputFile once the whole body has arrived, so an interrupted
            // transfer can never leave a truncated file where the app (or the
            // user's file manager) expects finished audio.
            val partialFile = File(podcastDir, "${outputTarget.name}$PARTIAL_SUFFIX")
            transferFile = partialFile

            // Check if we have a paused task to resume from
            // Where this transfer writes, and whether it appends to bytes that
            // are already there.
            var append = false

            // The byte count in the task row is the offset to send in Range, so
            // the file on disk has to be exactly that long; a mismatch (crash
            // mid-write, truncated write) restarts instead of splicing garbage.
            val canResume = existingTask != null && storedTarget != null &&
                existingTask.state == "PAUSED" && existingTask.downloadedBytes > 0 &&
                outputTarget.isFile && outputTarget.length() == existingTask.downloadedBytes
            if (canResume) {
                // Resume from partial download
                downloadedBytes = existingTask.downloadedBytes
                totalBytes = existingTask.totalBytes
                // A paused task's partial lives at its target path (parked there
                // on pause), so appending to it is not a risk to a finished file.
                transferFile = outputTarget
                append = true
                db.downloadTasks.updateState(episodeId, "DOWNLOADING")
                Logger.i(TAG, "Resuming download from byte $downloadedBytes: ${outputTarget.absolutePath}")
            } else {
                // Fresh download — create task record
                val now = System.currentTimeMillis()
                db.downloadTasks.insert(DownloadTask(
                    episodeId = episodeId, origin = origin, audioUrl = audioUrl,
                    podcastTitle = podcastTitle, episodeTitle = episodeTitle,
                    targetFilePath = outputTarget.absolutePath, downloadedBytes = 0, totalBytes = 0,
                    state = "DOWNLOADING", createdAt = now, updatedAt = now
                ))
                // Only our own leftover .part is discarded; anything at the
                // target path is left for the atomic replace on success.
                deleteIfPresent(partialFile)
            }

            Logger.i(TAG, "Downloading to: ${outputTarget.absolutePath}")
            onProgress?.invoke(downloadedBytes, totalBytes)

            // The connection is opened (and its response head read) by this
            // helper because a short readTimeout is what makes pause/cancel
            // prompt, while a response head needs a longer, retried wait.
            val connection = openTransferConnection(
                audioUrl = audioUrl,
                resumeFrom = downloadedBytes,
                isStopRequested = { cancelledDownloads.contains(episodeId) || isPaused() }
            )
            liveConnections[episodeId] = connection

            // HttpURLConnection has no close(); without disconnect() the
            // responseCode/stream failure paths leaked the socket.
            try {

                // Cached from openTransferConnection: no second round trip.
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
                    // Throw away the resumed prefix and start over in the .part
                    // file. The target path keeps whatever it held until the new
                    // copy is complete.
                    downloadedBytes = 0L
                    totalBytes = 0L
                    transferFile = partialFile
                    append = false
                    deleteIfPresent(partialFile)
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
                // Non-null: both paths above set it, and the Range-restart
                // branch below re-points the hoisted var before this line.
                val transferPath = checkNotNull(transferFile)

                val fileOutputStream = if (append) {
                    java.io.FileOutputStream(transferPath, true) // append mode for resume
                } else {
                    transferPath.outputStream() // overwrite for fresh download
                }

                // Per-task rate limiter
                val rateLimiter = if (speedLimitKbps > 0) {
                    RateLimiter(speedLimitKbps.toLong() * 1024)
                } else null

                // Wall-clock of the last byte that actually arrived. A read that
                // times out repeatedly without bytes is a dead connection, not a
                // slow one.
                var lastProgressAt = System.nanoTime()

                try {
                    while (true) {
                        if (cancelledDownloads.remove(episodeId)) {
                            // Cancelled — clean up partial file
                            fileOutputStream.close()
                            inputStream.close()
                            finishCancelled(episodeId, transferFile)
                            Logger.i(TAG, "Download cancelled: $episodeId")
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }

                        if (isPaused()) {
                            // Paused — save state and keep partial file
                            fileOutputStream.close()
                            inputStream.close()
                            finishPaused(episodeId, outputFile, transferFile, downloadedBytes, totalBytes)
                            Logger.i(TAG, "Download paused: $episodeId (${downloadedBytes}/${totalBytes})")
                            return@withContext Result.failure(Exception("Download paused"))
                        }

                        if (!isActive) {
                            // Coroutine cancelled externally
                            fileOutputStream.close()
                            inputStream.close()
                            finishCancelled(episodeId, transferFile)
                            Logger.i(TAG, "Download cancelled (coroutine): $episodeId")
                            return@withContext Result.failure(Exception("Download cancelled"))
                        }

                        // A timed-out read consumes nothing, so the stream stays
                        // usable; the wake-up is what lets the checks above see a
                        // pause/cancel within roughly one [READ_POLL_TIMEOUT_MS].
                        // Bytes that are merely arranged in slow bursts still
                        // arrive between timeouts and finish normally.
                        val read = try {
                            inputStream.read(buffer)
                        } catch (timeout: java.net.SocketTimeoutException) {
                            if (System.nanoTime() - lastProgressAt >= STALL_TIMEOUT_MS * 1_000_000L) {
                                throw java.io.IOException(
                                    "Download stalled: no data received for ${STALL_TIMEOUT_MS / 1000}s",
                                    timeout
                                )
                            }
                            continue
                        }
                        if (read == -1) break
                        fileOutputStream.write(buffer, 0, read)
                        downloadedBytes += read
                        lastProgressAt = System.nanoTime()
                        onProgress?.invoke(downloadedBytes, totalBytes)
                        rateLimiter?.throttle(read) { episodeId in pausedDownloads || episodeId in cancelledDownloads }
                    }
                } finally {
                    fileOutputStream.close()
                    inputStream.close()
                }

                // The whole body is on disk: move the .part over the target in
                // one step, so the target path only ever holds a complete file.
                val completedFile = checkNotNull(outputFile)
                publishPartial(checkNotNull(transferFile), completedFile)

                Logger.i(TAG, "Download complete: ${completedFile.absolutePath} (${completedFile.length()} bytes)")
                // Both rows must move together. Inserting the download and
                // deleting the task in separate autocommitted statements meant a
                // kill in between left a task row pointing at finished audio,
                // which the cleanup path then treated as a partial (P1-3).
                db.transaction {
                    downloads.insert(
                        episodeId = episodeId, origin = origin,
                        filePath = completedFile.absolutePath,
                        podcastTitle = podcastTitle, episodeTitle = episodeTitle
                    )
                    downloadTasks.delete(episodeId) // clean up task
                }
                cancelledDownloads.remove(episodeId)
                pausedDownloads.remove(episodeId)
                Result.success(completedFile)
            } finally {
                // `remove(key, value)` so a newer transfer for the same episode
                // (possible after a cancel + immediate retry) is not evicted.
                liveConnections.remove(episodeId, connection)
                connection.disconnect()
            }
        } catch (e: Exception) {
            // A pause/cancel disconnects the live connection, so a blocked
            // read/connect throws instead of reaching the loop's flag checks.
            // The user's intent wins over the exception: clean up exactly like
            // the in-loop branches and report FAILED only when neither flag was
            // set.
            val cancelled = cancelledDownloads.remove(episodeId)
            val paused = !cancelled && (pausedDownloads.remove(episodeId) || isPaused())
            try {
                if (cancelled) {
                    Logger.i(TAG, "Download cancelled while reading: $episodeId")
                    finishCancelled(episodeId, transferFile)
                    return@withContext Result.failure(Exception("Download cancelled"))
                }
                if (paused) {
                    Logger.i(TAG, "Download paused while reading: $episodeId (${downloadedBytes}/${totalBytes})")
                    finishPaused(episodeId, outputFile, transferFile, downloadedBytes, totalBytes)
                    return@withContext Result.failure(Exception("Download paused"))
                }
            } catch (cleanupFailure: Exception) {
                // The stop itself must not turn into a crash; report what went
                // wrong with the cleanup and keep the original failure below.
                Logger.e(TAG, "Cleanup after stopping $episodeId failed", cleanupFailure)
                e.addSuppressed(cleanupFailure)
            }

            Logger.e(TAG, "Download failed: $audioUrl", e)
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

    /**
     * Cleanup for a transfer the user cancelled: drop the partial bytes and the
     * task row, and forget both request flags.
     *
     * Shared by the in-loop check, the coroutine-cancel check and the abort path
     * (an IOException raised by [abortInFlightConnection]), so those exits
     * cannot drift apart.
     */
    private suspend fun finishCancelled(episodeId: String, partialFile: File?) {
        if (partialFile != null) deleteIfPresent(partialFile)
        db.downloadTasks.delete(episodeId)
        cancelledDownloads.remove(episodeId)
        pausedDownloads.remove(episodeId)
    }

    /**
     * Cleanup for a transfer the user paused: park the received bytes at the
     * task's target path — where [resumeDownload] looks for them — and record
     * the progress plus the PAUSED state.
     *
     * Shared by the in-loop check and the abort path, so a pause behaves the
     * same whether it was noticed between reads or by breaking a blocked read.
     */
    private suspend fun finishPaused(
        episodeId: String,
        outputFile: File?,
        transferFile: File?,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        if (outputFile != null && transferFile != null) {
            publishPartial(transferFile, outputFile)
        }
        // Clear the pause flag so a later resume does not immediately pause again.
        pausedDownloads.remove(episodeId)
        db.downloadTasks.updateProgress(episodeId, downloadedBytes, totalBytes)
        db.downloadTasks.updateState(episodeId, "PAUSED")
    }

    /** Pause a running download — keeps partial file on disk. */
    fun pauseDownload(episodeId: String) {
        pausedDownloads.add(episodeId)
        // The copy loop notices this within one read-poll timeout; dropping the
        // connection is a best-effort shortcut when it is not mid-read.
        abortInFlightConnection(episodeId)
    }

    /**
     * Opens [audioUrl] and returns a connection whose response head has been
     * read, sending `Range: bytes=resumeFrom-` when resuming.
     *
     * The read timeout doubles as the pause/cancel wake-up interval, so it is
     * short ([READ_POLL_TIMEOUT_MS]). The same timeout governs the response
     * head, and a header read that times out leaves that connection unusable —
     * `getResponseCode` cannot be retried on it (verified against the JDK: every
     * later call times out again). So a slow host gets a fresh request with a
     * longer timeout rather than a failed download, and the initial short
     * attempt keeps the common case prompt.
     *
     * [isStopRequested] aborts the wait between attempts, so a cancel during a
     * long header wait does not have to sit through it.
     */
    private fun openTransferConnection(
        audioUrl: String,
        resumeFrom: Long,
        isStopRequested: () -> Boolean
    ): HttpURLConnection {
        val deadline = System.nanoTime() + RESPONSE_HEAD_TIMEOUT_MS * 1_000_000L
        var timeout = READ_POLL_TIMEOUT_MS
        while (true) {
            // Served by the catch below: the outer handler classifies by the
            // pause/cancel flags and cleans up exactly like an in-loop stop.
            if (isStopRequested()) throw java.net.SocketTimeoutException("Transfer stopped before the response head")
            val connection = URL(audioUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = timeout
            // Some podcast hosts reject requests without a browser-ish agent,
            // and identifying ourselves is the convention for podcast clients.
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (resumeFrom > 0) {
                connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            try {
                connection.responseCode
                return connection
            } catch (timeoutFailure: java.net.SocketTimeoutException) {
                connection.disconnect()
                if (System.nanoTime() >= deadline) throw timeoutFailure
                val next = (timeout * 2).coerceAtMost(RESPONSE_HEAD_RETRY_TIMEOUT_MS)
                Logger.w(
                    TAG,
                    "No response head for $audioUrl within ${timeout}ms; retrying with ${next}ms"
                )
                timeout = next
            }
        }
    }

    /**
     * Drops the socket of a transfer that is blocked in connect/read.
     *
     * Best effort only: on the JDK's `HttpURLConnection` this does not interrupt
     * a read that is already blocked, which is why the copy loop relies on
     * [READ_POLL_TIMEOUT_MS] instead. It still releases an idle connection and
     * keeps the in-loop/non-2xx paths tidy.
     */
    private fun abortInFlightConnection(episodeId: String) {
        val connection = liveConnections[episodeId] ?: return
        try {
            connection.disconnect()
        } catch (_: Exception) {
            // Nothing useful to do here: the transfer's own error path decides
            // the outcome, and the request flags decide whether it was a stop.
        }
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
     *
     * A file that refuses to be deleted (on Windows: still open, usually because
     * it is the episode being played) keeps its record rather than being dropped
     * from the database. Deleting the row regardless is how a download screen
     * ends up with nothing listed while the disk still holds the files.
     *
     * @return number of records deleted.
     */
    suspend fun deleteDownloadedByOrigin(origin: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val records = db.downloads.getAllByOrigin(origin)
            for (record in records) {
                val file = File(record.filePath)
                if (!deleteIfPresent(file)) {
                    Logger.w(TAG, "Could not delete file, keeping the record: ${file.absolutePath}")
                    continue
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

    /**
     * Prune one show's downloads to its "keep latest N" limit.
     *
     * Runs after a feed refresh added episodes and whenever one of the show's
     * downloads completes. Selection is [downloadsBeyondKeepLimit]'s; each
     * chosen record then goes through [deleteDownloadedEpisode], the same path
     * the Downloads screen uses — so a file that refuses to be deleted (on
     * Windows: still open in a player) keeps its record and is simply retried
     * by the next prune, leaving database and disk consistent.
     *
     * @param protectedEpisodeIds episodes whose files must stay this round,
     *   above all the one playing — a playing file cannot be deleted on
     *   Windows, and the exemption also stops the policy from fighting
     *   playback.
     * @return episode ids whose records were actually removed; the caller
     *   uses them to drop the same ids from its in-memory completed set.
     */
    suspend fun pruneDownloadsToKeepLimit(
        origin: String,
        keep: Int,
        protectedEpisodeIds: Set<String> = emptySet()
    ): List<String> {
        if (keep <= 0) return emptyList()
        val toPrune = downloadsBeyondKeepLimit(db.downloads.getAllByOrigin(origin), keep, protectedEpisodeIds)
        val pruned = mutableListOf<String>()
        for (record in toPrune) {
            if (deleteDownloadedEpisode(record.episodeId)) {
                pruned.add(record.episodeId)
            }
        }
        if (pruned.isNotEmpty()) {
            Logger.i(TAG, "Keep-latest-$keep pruned ${pruned.size} download(s) for $origin")
        }
        return pruned
    }

    /** Cancel an active download. Cleans up the partial file and DB task immediately. */
    fun cancelDownload(episodeId: String) {
        cancelledDownloads.add(episodeId)
        pausedDownloads.remove(episodeId)
        // Same as pause: the loop sees the flag on its next wake-up, and the
        // disconnect helps when the transfer happens not to be inside a read.
        abortInFlightConnection(episodeId)
    }

    /**
     * Clean up a paused or failed download task: removes its partial file and
     * the task record.
     *
     * A task row is not proof that the target path is disposable. The row can be
     * a leftover from a run killed between recording a finished download and
     * clearing the task (before that pairing became a transaction), and then
     * `targetFilePath` points at the complete audio file. Deleting it from here
     * made the ✕ on a stale row destroy the user's downloaded episode, so the
     * file is only touched when no download record backs it.
     *
     * This is needed when the user cancels a task that is already paused/failed
     * (where the download coroutine has already finished).
     */
    suspend fun cleanupPausedTask(episodeId: String) {
        try {
            val task = db.downloadTasks.getByEpisodeId(episodeId)
            val record = db.downloads.getByEpisodeId(episodeId)
            if (task != null) {
                if (record == null && task.state != "COMPLETED") {
                    // No download record: the target can only be a partial
                    // (plus a `.part` left by a failed transfer). If it cannot
                    // be deleted, keep the task row so the cleanup stays
                    // retryable instead of orphaning the file.
                    val partials = buildList {
                        if (task.targetFilePath.isNotBlank()) {
                            add(File(task.targetFilePath))
                            add(File(task.targetFilePath + PARTIAL_SUFFIX))
                        }
                    }
                    for (partial in partials) {
                        if (!deleteIfPresent(partial)) {
                            Logger.w(TAG, "Could not delete partial file, keeping the task row: ${partial.absolutePath}")
                            return
                        }
                    }
                } else {
                    Logger.i(
                        TAG,
                        "Task $episodeId belongs to a completed download; keeping ${task.targetFilePath}"
                    )
                }
                db.downloadTasks.delete(episodeId)
            }
            cancelledDownloads.remove(episodeId)
            pausedDownloads.remove(episodeId)
            Logger.i(TAG, "Cleaned up paused task: $episodeId")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to clean up paused task: $episodeId", e)
        }
    }

    /**
     * Deletes [file] if it exists.
     *
     * @return true when the file is gone from the path (deleted or was never
     *   there), false when the delete was refused — the caller must then keep
     *   whatever database row points at it.
     */
    private fun deleteIfPresent(file: File): Boolean {
        if (!file.exists()) return true
        if (file.delete()) {
            Logger.i(TAG, "Deleted file: ${file.absolutePath}")
            return true
        }
        return false
    }

    /**
     * Moves a finished `.part` transfer onto its final path, replacing any older
     * file in one step. Falls back to a non-atomic replace when the filesystem
     * will not do it atomically.
     */
    private fun publishPartial(from: File, to: File) {
        if (from.absolutePath == to.absolutePath || !from.exists()) return
        try {
            java.nio.file.Files.move(
                from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Atomic move from ${from.name} failed (${e.message}); retrying non-atomically")
            if (!from.exists() && to.isFile) return
            java.nio.file.Files.move(
                from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
