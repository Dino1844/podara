package app.podara.player

import app.podara.data.AppDatabase
import app.podara.data.PlayerQueueRow
import app.podara.util.Logger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*

private const val TAG = "MediaPlayerState"

data class QueueItem(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val podcastArtworkUrl: String? = null,
    val episodeId: String? = null,
    val isDownloaded: Boolean = false,
    val historyId: Int? = null,
    /**
     * The remote stream URL for an item whose [url] is a local download path.
     *
     * Kept so a deleted download can fall back to streaming instead of failing
     * with a FILE error. Null for items that were never downloaded, and for
     * restored sessions from builds that did not record it.
     */
    val fallbackUrl: String? = null
)

class MediaPlayerState(
    private val player: AudioPlayerEngine = MpvAudioPlayerEngine()
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var sleepTimerJob: Job? = null

    var isPlaying by mutableStateOf(false)
        private set
    var currentPosition by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(1L)
        private set
    var volume by mutableStateOf(100)
        private set
    private var previousVolumeBeforeMute = 100
    var playbackSpeed by mutableFloatStateOf(1.0f)
        private set
    // True between a play attempt and the first position report (or an error).
    // The engine reports PLAYING optimistically before the file has actually
    // loaded, so this is cleared by onPositionChanged rather than by the play
    // state change — otherwise it would be reset within the same call.
    var isLoading by mutableStateOf(false)
        private set

    /**
     * Last playback failure as a [PlaybackErrorCategory] constant, or null.
     * Deliberately machine-readable: the UI maps it to human copy via Strings.
     * Cleared by any new play attempt and by stop().
     */
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * Wall-clock time of the last [play]. Read by the mpv poll thread to decide
     * whether a stop transition is real or stale, written from the UI thread —
     * hence @Volatile, for the same reason [isUserPaused] has it. Without it
     * the poll thread could keep reading a stale timestamp and either drop a
     * real end-of-track or advance the queue prematurely.
     */
    @Volatile
    private var lastPlayStartMs = 0L

    var currentUrl by mutableStateOf<String?>(null)
        private set
    var currentTitle by mutableStateOf<String?>(null)
        private set
    var currentSubtitle by mutableStateOf<String?>(null)
        private set
    var currentArtworkUrl by mutableStateOf<String?>(null)
        private set
    var currentEpisodeId by mutableStateOf<String?>(null)
        private set

    var sleepTimerTrigger by mutableStateOf<Long?>(null)
        private set
    var sleepTimerMinutes by mutableStateOf<Int?>(null)
        private set

    val queue = mutableStateListOf<QueueItem>()
    var queueIndex by mutableIntStateOf(-1)
        private set

    // Read by the mpv poll thread, written from the UI. Not volatile, the poll
    // could keep reading a stale value out of a register.
    @Volatile
    private var isUserPaused = false

    init {
        Logger.d(TAG, "MediaPlayerState initialized")
        player.onPlayStateChanged = { playing ->
            Logger.d(TAG, "Play state changed: playing=$playing")
            isPlaying = playing
            if (!playing) {
                isLoading = false
                val elapsed = System.currentTimeMillis() - lastPlayStartMs
                when (val decision = PlaybackRules.decideStopTransition(elapsed, isUserPaused)) {
                    is StopTransition.Ignore ->
                        Logger.d(TAG, "Ignoring playState(false) — ${elapsed}ms since last play(), likely stale")
                    is StopTransition.AdvanceToNext -> {
                        Logger.i(TAG, "Auto-advancing to next track")
                        playNext()
                    }
                    is StopTransition.StayPaused -> Unit
                }
            }
        }
        player.onPositionChanged = { pos, dur ->
            currentPosition = pos
            duration = dur
            isLoading = false
        }
        player.onError = { category ->
            // The engine does not fire onPlayStateChanged for a failure (it must
            // not trigger the queue auto-advance), so mirror the playing state here.
            Logger.e(TAG, "Playback error: $category")
            isPlaying = false
            isLoading = false
            error = category
        }
    }

    fun play(url: String, title: String? = null, subtitle: String? = null, artworkUrl: String? = null, podcastArtworkUrl: String? = null, durationMs: Long = 0L, episodeId: String? = null) {
        Logger.i(TAG, "play() title=$title, url=$url, durationMs=$durationMs")
        currentUrl = url
        currentTitle = title
        currentSubtitle = subtitle
        currentArtworkUrl = artworkUrl ?: podcastArtworkUrl
        currentEpisodeId = episodeId
        isLoading = true
        error = null
        isUserPaused = false

        val existingIndex = queue.indexOfFirst { it.url == url }
        if (existingIndex >= 0) {
            queueIndex = existingIndex
            // Keep the existing queue item's episodeId up-to-date.
            // This covers cross-session scenarios where the item was restored from DB
            // without an episodeId (from a previous build) and the same URL is played again.
            if (episodeId != null) {
                queue[existingIndex] = queue[existingIndex].copy(episodeId = episodeId)
            }
        } else {
            queue.add(QueueItem(url, title ?: "Unknown", subtitle = subtitle, artworkUrl = currentArtworkUrl, podcastArtworkUrl = podcastArtworkUrl, episodeId = episodeId))
            queueIndex = queue.size - 1
        }

        // Pass the current speed explicitly. The engine's default is 1.0x and it
        // writes that to the player on every load, so without this a new track
        // silently reverted to 1x while the UI still displayed the old speed.
        player.play(url, speed = playbackSpeed, durationMs = durationMs)
        // Record when playback started so the onPlayStateChanged(false) handler
        // can distinguish false transitions (loading glitches, stale EOF from a
        // previous file after playNext()) from real EOF. Transitions within 3s
        // are considered stale and ignored.
        lastPlayStartMs = System.currentTimeMillis()
    }

    /**
     * Replace the entire queue with a context list and play the target item.
     * Used when playing from a context-aware page (History, Favorites, PodcastDetail, Downloads)
     * so the user sees the full list as their playback queue and can playNext through it.
     * Items added via addToQueue() are appended after the context items.
     */
    fun playWithContext(
        context: List<QueueItem>,
        targetUrl: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUrl: String? = null,
        podcastArtworkUrl: String? = null,
        durationMs: Long = 0L,
        episodeId: String? = null,
        targetHistoryId: Int? = null
    ) {
        Logger.i(TAG, "playWithContext() targetUrl=$targetUrl, contextSize=${context.size}")
        currentUrl = targetUrl
        currentTitle = title
        currentSubtitle = subtitle
        currentArtworkUrl = artworkUrl ?: podcastArtworkUrl
        currentEpisodeId = episodeId
        isLoading = true
        error = null
        isUserPaused = false

        queue.clear()
        queue.addAll(context)
        queueIndex = targetHistoryId?.let { historyId ->
            queue.indexOfFirst { it.historyId == historyId }
        } ?: -1
        if (queueIndex < 0) {
            queueIndex = queue.indexOfFirst { it.url == targetUrl }
        }
        // Fallback: the target may be the same episode as a context item but
        // with a different URL — playing a downloaded episode swaps the stream
        // URL for a local file path. Without this the episode would be appended
        // a second time and "next" would replay it from the network.
        if (queueIndex < 0 && episodeId != null) {
            queueIndex = queue.indexOfFirst { it.episodeId == episodeId }
        }
        if (queueIndex < 0) {
            queue.add(QueueItem(targetUrl, title ?: "Unknown", subtitle = subtitle, artworkUrl = currentArtworkUrl, podcastArtworkUrl = podcastArtworkUrl, episodeId = episodeId, historyId = targetHistoryId))
            queueIndex = queue.size - 1
        }

        player.play(targetUrl, speed = playbackSpeed, durationMs = durationMs)
        lastPlayStartMs = System.currentTimeMillis()
    }

    fun playFromQueue(index: Int) {
        if (index < 0 || index >= queue.size) {
            Logger.w(TAG, "playFromQueue: invalid index=$index, queueSize=${queue.size}")
            return
        }
        queueIndex = index
        val item = queue[index]
        Logger.i(TAG, "playFromQueue: index=$index, title=${item.title}")
        val playableUrl = resolvePlayableUrl(item)
        currentUrl = playableUrl
        currentTitle = item.title
        currentSubtitle = item.subtitle
        currentArtworkUrl = item.artworkUrl ?: item.podcastArtworkUrl
        currentEpisodeId = item.episodeId
        isLoading = true
        error = null
        isUserPaused = false
        // Reset the previous track's timing. mpv only reports the new duration
        // once the file loads, and until then `seekForward` would clamp against
        // the old episode's length — jumping past the end of a shorter one.
        currentPosition = 0L
        duration = 0L
        player.play(playableUrl, speed = playbackSpeed)
        lastPlayStartMs = System.currentTimeMillis()
    }

    /**
     * The URL to actually hand to the engine for [item].
     *
     * Queue items captured a local file path when the episode was downloaded,
     * but downloads can be deleted afterwards. Playing the stale path fails
     * with a FILE error even though the network stream is still perfectly
     * usable, so a missing local file falls back to the remote URL.
     */
    private fun resolvePlayableUrl(item: QueueItem): String {
        val url = item.url
        val isLocalPath = !url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        if (!isLocalPath) return url

        if (java.io.File(url).exists()) return url

        val fallback = item.fallbackUrl
        if (!fallback.isNullOrBlank()) {
            Logger.w(TAG, "Local file is gone, falling back to the stream: $url")
            return fallback
        }
        Logger.w(TAG, "Local file is gone and no stream URL is known: $url")
        return url
    }

    fun addToQueue(
        url: String,
        title: String,
        artworkUrl: String? = null,
        podcastArtworkUrl: String? = null,
        episodeId: String? = null,
        isDownloaded: Boolean = false
    ) {
        Logger.d(TAG, "addToQueue: title=$title")
        queue.add(QueueItem(url, title, artworkUrl = artworkUrl, podcastArtworkUrl = podcastArtworkUrl, episodeId = episodeId, isDownloaded = isDownloaded))
    }

    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= queue.size) return
        Logger.d(TAG, "removeFromQueue: index=$index")
        val outcome = PlaybackRules.indexAfterRemoval(
            queueSizeAfterRemoval = queue.size - 1,
            removedIndex = index,
            currentIndex = queueIndex
        )
        queue.removeAt(index)
        queueIndex = outcome.nextIndex
        if (outcome.playbackWasRemoved) {
            if (outcome.shouldStopPlayback) stop() else playFromQueue(outcome.nextIndex)
        }
    }

    fun playNext() {
        PlaybackRules.nextIndexToPlay(queueIndex, queue.size)?.let { next ->
            playFromQueue(next)
        } ?: Logger.d(TAG, "playNext: no more items in queue")
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        val item = queue.removeAt(fromIndex)
        queue.add(toIndex, item)
        queueIndex = PlaybackRules.indexAfterMove(queueIndex, fromIndex, toIndex)
    }

    fun removeSelectedFromQueue(selectedIndices: Set<Int>) {
        val outcome = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = (queue.size - selectedIndices.count { it in queue.indices }).coerceAtLeast(0),
            removedIndices = selectedIndices,
            currentIndex = queueIndex
        )
        val sorted = selectedIndices.sortedDescending()
        for (index in sorted) {
            if (index in queue.indices) {
                queue.removeAt(index)
            }
        }
        queueIndex = outcome.nextIndex
        if (outcome.playbackWasRemoved) {
            if (outcome.shouldStopPlayback) stop() else playFromQueue(outcome.nextIndex)
        }
    }

    fun clearQueue() {
        queue.clear()
        queueIndex = -1
        stop()
    }

    fun playPrevious() {
        when (val action = PlaybackRules.decidePrevious(currentPosition, queueIndex)) {
            is PreviousAction.RestartCurrent -> {
                Logger.d(TAG, "playPrevious: restarting current track (pos=${currentPosition}ms)")
                seek(0)
            }
            is PreviousAction.PlayIndex -> playFromQueue(action.index)
            is PreviousAction.AtBeginning -> Logger.d(TAG, "playPrevious: at beginning of queue")
        }
    }

    fun pause() {
        Logger.d(TAG, "pause()")
        isUserPaused = true
        player.pause()
    }

    fun resume() {
        Logger.d(TAG, "resume()")
        // A failed track cannot be unpaused — the engine is idle after a load
        // failure — so the play button would do nothing. Re-attempt the load
        // instead; play() clears error on success.
        if (error != null && !isPlaying) {
            retry()
            return
        }
        isUserPaused = false
        // If the track had already reached EOF, resuming in place would
        // immediately re-report the end and auto-advance instead of replaying.
        // Rewind first so pressing play at the end of an episode replays it.
        if (currentPosition >= duration && duration > 0) {
            player.seek(0)
        }
        // Refresh the start marker so the resulting state change is treated as
        // fresh rather than as a stale end-of-track notification.
        lastPlayStartMs = System.currentTimeMillis()
        player.resume()
    }

    fun togglePlayPause() {
        Logger.d(TAG, "togglePlayPause: isPlaying=$isPlaying")
        if (isPlaying) pause() else resume()
    }

    /** Re-attempts to load the current track after a playback failure. */
    fun retry() {
        val url = currentUrl ?: return
        Logger.i(TAG, "retry() url=$url")
        play(url, currentTitle, currentSubtitle, currentArtworkUrl, durationMs = 0L, episodeId = currentEpisodeId)
    }

    fun stop() {
        Logger.d(TAG, "stop()")
        player.stop()
        // The engine now reports this too, but the state holder owns the field:
        // if a future engine stops silently, `isPlaying` must still end up
        // false or the play button stays inverted and disabled forever.
        isPlaying = false
        currentUrl = null
        currentTitle = null
        currentArtworkUrl = null
        currentEpisodeId = null
        error = null
        isLoading = false
        // Position/duration belong to the episode that was playing; leaving
        // them set made the next track inherit the previous one's length.
        currentPosition = 0L
        duration = 0L
    }

    fun seek(positionMs: Long) {
        player.seek(positionMs)
    }

    fun seekBack(incrementMs: Long = 10000L) {
        val newPos = (currentPosition - incrementMs).coerceAtLeast(0L)
        seek(newPos)
    }

    fun seekForward(incrementMs: Long = 10000L) {
        val newPos = (currentPosition + incrementMs).coerceAtMost(duration)
        seek(newPos)
    }

    @Suppress("FunctionName")
    fun changeVolume(vol: Int) {
        player.setVolume(vol)
        volume = vol
    }

    fun toggleMute() {
        if (volume > 0) {
            previousVolumeBeforeMute = volume
            changeVolume(0)
        } else {
            changeVolume(previousVolumeBeforeMute)
        }
    }

    @Suppress("FunctionName")
    fun changePlaybackSpeed(speed: Float) {
        player.setSpeed(speed)
        playbackSpeed = speed
        Logger.d(TAG, "changePlaybackSpeed: speed=$speed")
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            Logger.d(TAG, "setSleepTimer: cancelled")
            sleepTimerTrigger = null
            sleepTimerMinutes = null
            return
        }
        val triggerTime = PlaybackRules.sleepTimerDeadline(System.currentTimeMillis(), minutes)
        if (triggerTime == null) {
            Logger.d(TAG, "setSleepTimer: cancelled")
            sleepTimerTrigger = null
            sleepTimerMinutes = null
            return
        }
        sleepTimerTrigger = triggerTime
        sleepTimerMinutes = minutes
        Logger.i(TAG, "setSleepTimer: ${minutes} minutes")

        sleepTimerJob = scope.launch {
            delay((triggerTime - System.currentTimeMillis()).coerceAtLeast(0L))
            // No withContext(Dispatchers.Main): the desktop module has no Main
            // dispatcher provider (only coroutines-core is on the classpath), so
            // that call threw IllegalStateException and the SupervisorJob scope
            // swallowed it — the sleep timer silently never fired. Snapshot
            // state writes and pause() are thread-safe here anyway.
            Logger.i(TAG, "Sleep timer triggered, pausing playback")
            pause()
            sleepTimerTrigger = null
            sleepTimerMinutes = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerTrigger = null
        sleepTimerMinutes = null
    }

    fun getProgress(): Float {
        return if (duration > 0) {
            currentPosition.toFloat() / duration
        } else 0f
    }

    // ── Session persistence ──

    /**
     * Save current queue and playback state to database.
     */
    suspend fun saveSession(database: AppDatabase) {
        Logger.d(TAG, "saveSession: saving queue (${queue.size} items), queueIndex=$queueIndex, pos=${currentPosition}ms")
        val rows = queue.mapIndexed { index, item ->
            PlayerQueueRow(
                queueOrder = index,
                url = item.url,
                title = item.title,
                subtitle = item.subtitle,
                artworkUrl = item.artworkUrl,
                podcastArtworkUrl = item.podcastArtworkUrl,
                episodeId = item.episodeId,
                isDownloaded = item.isDownloaded
            )
        }
        database.playerQueue.saveQueue(rows)
        database.playerSession.saveSession(
            queueIndex = queueIndex,
            currentPositionMs = currentPosition,
            playbackSpeed = playbackSpeed,
            volume = volume,
            currentEpisodeId = currentEpisodeId
        )
        saveEpisodeProgress(database)
    }

    /**
     * Records the current episode's resume point.
     *
     * `playerSession` only remembers whatever the player holds *now*, so
     * switching episodes used to discard the previous position. This writes the
     * per-episode row that "continue listening" and per-episode progress bars
     * read, and it is safe to call often — [EpisodeProgressDao.record] decides
     * for itself whether the episode counts as finished.
     */
    suspend fun saveEpisodeProgress(database: AppDatabase) {
        val episodeId = currentEpisodeId ?: return
        val origin = database.episodes.getById(episodeId)?.origin ?: return
        if (currentPosition <= 0L && duration <= 0L) return
        database.progress.record(
            episodeId = episodeId,
            origin = origin,
            positionMs = currentPosition,
            durationMs = duration
        )
    }

    /**
     * Restore queue and playback state from database.
     * Restores to a paused state at the saved position, ready for the user to tap play.
     */
    suspend fun restoreSession(database: AppDatabase) {
        val session = database.playerSession.loadSession() ?: run {
            Logger.d(TAG, "restoreSession: no saved session found")
            return
        }
        val rows = database.playerQueue.loadQueue()
        Logger.d(TAG, "restoreSession: loaded ${rows.size} queue items, queueIndex=${session.queueIndex}")

        // Restore speed and volume regardless
        playbackSpeed = session.playbackSpeed
        volume = session.volume
        changePlaybackSpeed(session.playbackSpeed)
        changeVolume(session.volume)

        if (rows.isEmpty()) {
            Logger.d(TAG, "restoreSession: empty queue, nothing to restore")
            return
        }

        // Rebuild queue in-memory
        queue.clear()
        rows.forEach { row ->
            queue.add(QueueItem(
                url = row.url,
                title = row.title,
                subtitle = row.subtitle,
                artworkUrl = row.artworkUrl,
                podcastArtworkUrl = row.podcastArtworkUrl,
                episodeId = row.episodeId,
                isDownloaded = row.isDownloaded
            ))
        }

        val idx = PlaybackRules.clampRestoredIndex(queue.size, session.queueIndex)
        queueIndex = idx
        val item = queue[idx]

        // Restore current display state
        currentUrl = item.url
        currentTitle = item.title
        currentSubtitle = item.subtitle
        currentArtworkUrl = item.artworkUrl ?: item.podcastArtworkUrl
        currentEpisodeId = item.episodeId ?: session.currentEpisodeId

        // Load into player at saved position, then pause.
        // IMPORTANT: set isUserPaused BEFORE pause() so the onPlayStateChanged(false)
        // callback does NOT trigger playNext().
        Logger.i(TAG, "restoreSession: restoring playback at idx=$idx, pos=${session.currentPositionMs}ms")
        isUserPaused = true
        player.play(item.url, startPositionMs = session.currentPositionMs, durationMs = 0L)
        player.pause()
        isPlaying = false
    }

    /**
     * Lightweight position-only save for periodic heartbeats.
     *
     * Also records the per-episode resume point, which is what actually
     * survives switching tracks.
     */
    suspend fun savePosition(database: AppDatabase) {
        database.playerSession.updatePosition(currentPositionMs = currentPosition)
        saveEpisodeProgress(database)
    }

    fun release() {
        Logger.d(TAG, "release()")
        sleepTimerJob?.cancel()
        scope.cancel()
        player.release()
    }
}
