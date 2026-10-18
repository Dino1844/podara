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


    /**
     * The session id of the load currently playing, or 0 before the first play.
     *
     * Compared against the session carried by every engine event. This is what
     * replaced the previous wall-clock heuristic: an event for a superseded
     * session is dropped by identity, so a leftover end-of-track from the
     * previous file cannot advance the queue, and a genuine completion cannot be
     * mistaken for a stale one.
     */
    private var activeSession = 0L

    init {
        Logger.d(TAG, "MediaPlayerState initialized")
        player.setEventHandler { event -> handleEngineEvent(event) }
    }

    /**
     * Applies one engine event.
     *
     * Runs on the engine thread, so it must stay cheap and must not block.
     * Compose state writes are safe from any thread (they are snapshotted), and
     * the queue decision below only reads fields this class owns.
     */
    private fun handleEngineEvent(event: PlayerEvent) {
        // Anything the engine reports for a load the user has already left is
        // history. Dropping it here is cheaper and safer than teaching every
        // handler to compare ids itself.
        if (event.session != 0L && event.session != activeSession) {
            Logger.d(TAG, "dropping stale ${event::class.simpleName} for session ${event.session} (active=$activeSession)")
            return
        }
        when (event) {
            is PlayerEvent.StateChanged -> {
                Logger.d(TAG, "state changed: ${event.state}")
                isPlaying = event.state == PlaybackState.PLAYING
                if (event.state != PlaybackState.PLAYING) isLoading = false
            }

            is PlayerEvent.Progress -> {
                currentPosition = event.positionMs
                if (event.durationMs > 0) duration = event.durationMs
                isLoading = false
            }

            is PlayerEvent.TrackCompleted -> {
                // No timestamp arithmetic: the engine already proved this event
                // belongs to the track the user is listening to.
                Logger.i(TAG, "track completed, advancing")
                isPlaying = false
                isLoading = false
                playNext()
            }

            is PlayerEvent.TrackFailed -> {
                // The engine deliberately does not emit StateChanged here, so the
                // queue cannot auto-advance past an episode that simply failed.
                Logger.e(TAG, "playback failed: ${event.category}")
                isPlaying = false
                isLoading = false
                error = event.category
            }
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
        // Reflect the intent immediately: the user pressed play, so the
        // transport should show "pause" without waiting for the engine
        // round-trip. The engine's StateChanged(PLAYING) confirms it and
        // TrackFailed clears it, which is the same contract the old callbacks
        // provided — only now delivered as ordered events on one channel.
        isPlaying = true

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
        activeSession = player.open(url, speed = playbackSpeed, startPositionMs = 0L, durationMs = durationMs)
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
        // Reflect the intent immediately: the user pressed play, so the
        // transport should show "pause" without waiting for the engine
        // round-trip. The engine's StateChanged(PLAYING) confirms it and
        // TrackFailed clears it, which is the same contract the old callbacks
        // provided — only now delivered as ordered events on one channel.
        isPlaying = true

        queue.clear()
        // The drawer keys on episodeId (falling back to url), so a context list
        // containing the same episode twice would crash it the same way a
        // duplicate addToQueue() did. Favorites and History can legitimately
        // hold two rows for one episode, so this is reachable from real data.
        queue.addAll(context.distinctBy { it.episodeId ?: it.url })
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

        activeSession = player.open(targetUrl, speed = playbackSpeed, startPositionMs = 0L, durationMs = durationMs)
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
        // Same optimistic intent as play(): the transport shows pause at once.
        isPlaying = true
        // Reset the previous track's timing. mpv only reports the new duration
        // once the file loads, and until then `seekForward` would clamp against
        // the old episode's length — jumping past the end of a shorter one.
        currentPosition = 0L
        duration = 0L
        activeSession = player.open(playableUrl, speed = playbackSpeed, startPositionMs = 0L, durationMs = 0L)
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

    /**
     * Appends an episode to the playback queue.
     *
     * Refuses to append a duplicate: the queue drawer keys its list on
     * `episodeId`, and Compose throws `IllegalArgumentException: Key was already
     * used` when two items share one, which blanked the drawer. The duplicate
     * was reachable by double-clicking "add to queue", since nothing here
     * de-duplicated.
     *
     * Items without an [episodeId] are compared by [url] instead — the drawer
     * falls back to the url for its key in exactly the same way, so the two must
     * agree on what "the same episode" means or the crash comes back.
     *
     * @return true when the item was appended, false when it was already queued.
     */
    fun addToQueue(
        url: String,
        title: String,
        artworkUrl: String? = null,
        podcastArtworkUrl: String? = null,
        episodeId: String? = null,
        isDownloaded: Boolean = false
    ): Boolean {
        val duplicate = queue.any { existing ->
            if (episodeId != null && existing.episodeId != null) {
                existing.episodeId == episodeId
            } else {
                existing.url == url
            }
        }
        if (duplicate) {
            Logger.d(TAG, "addToQueue: already queued, ignoring title=$title")
            return false
        }
        Logger.d(TAG, "addToQueue: title=$title")
        queue.add(QueueItem(url, title, artworkUrl = artworkUrl, podcastArtworkUrl = podcastArtworkUrl, episodeId = episodeId, isDownloaded = isDownloaded))
        return true
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
        player.setPaused(true)
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
        // If the track had already reached EOF, resuming in place would
        // immediately re-report the end and auto-advance instead of replaying.
        // Rewind first so pressing play at the end of an episode replays it.
        if (currentPosition >= duration && duration > 0) {
            player.seek(0)
        }
        player.setPaused(false)
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

    /**
     * Steps playback speed to the neighbouring preset.
     *
     * The UI only ever offers [SPEED_PRESETS], so a keyboard shortcut must pick
     * from the same list rather than nudging the float — otherwise `-` from 1.0x
     * would land on 0.95x, a value no control can display or return to.
     *
     * @param up true for faster; false for slower.
     */
    fun stepPlaybackSpeed(up: Boolean) {
        // Find the current slot by nearest value, so a session restored from an
        // older build with an off-preset speed still steps sensibly.
        val current = SPEED_PRESETS.indexOfFirst { it >= playbackSpeed - 0.001f }
            .let { if (it < 0) SPEED_PRESETS.lastIndex else it }
        val next = if (up) current + 1 else current - 1
        val clamped = next.coerceIn(0, SPEED_PRESETS.lastIndex)
        if (clamped != current) changePlaybackSpeed(SPEED_PRESETS[clamped])
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

        // Load into the engine at the saved position, then pause.
        //
        // The pause is issued immediately after the open rather than waiting for
        // the load to complete: the engine applies commands in submission order,
        // so `pause` is guaranteed to land after `loadfile` regardless of how
        // long the load takes. There is no window in which the restored episode
        // audibly starts playing.
        Logger.i(TAG, "restoreSession: restoring playback at idx=$idx, pos=${session.currentPositionMs}ms")
        activeSession = player.open(
            item.url,
            speed = playbackSpeed,
            startPositionMs = session.currentPositionMs,
            durationMs = 0L
        )
        player.setPaused(true)
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

    companion object {
        /**
         * The playback speeds the UI offers, slowest first.
         *
         * Single-sourced here so the speed menu in both mini and full player and
         * the keyboard speed shortcut all agree. Previously the list was written
         * out twice in `PlayerUI.kt`.
         */
        val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }
}