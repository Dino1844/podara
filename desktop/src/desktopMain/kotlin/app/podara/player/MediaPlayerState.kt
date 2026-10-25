package app.podara.player

import app.podara.data.AppDatabase
import app.podara.data.PlayerQueueRow
import app.podara.util.Logger
import app.podara.util.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

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
    private val player: AudioPlayerEngine = MpvAudioPlayerEngine(),
    /**
     * Resolves the loaded episode's podcast speed override, or null when the
     * show follows the session speed.
     *
     * The state layer only knows episodes, not shows, so the database hop
     * lives behind this lambda (the same injectable-lambda pattern
     * SubscriptionManager uses for its triggers): production wires it to the
     * subscription table, tests to a map.
     */
    private val speedOverrideLookup: suspend (episodeId: String) -> Float? = { null },
    /**
     * Stores [speed] as the speed override of the show [episodeId] belongs to.
     *
     * Invoked only for changes the *user* made (speed menu, keyboard step) —
     * never for a restored session or an applied override, or a restore would
     * rewrite the row it just read.
     */
    private val speedOverrideWriter: suspend (episodeId: String, speed: Float) -> Unit = { _, _ -> }
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
    // The level to come back to after a mute. Tracked on every audible change
    // rather than only when muting: dragging the slider to 0 and then pressing
    // mute used to restore whatever the volume was the *previous* time mute was
    // pressed, which could be a level the user had long since abandoned.
    private var lastAudibleVolume = DEFAULT_VOLUME
    var playbackSpeed by mutableFloatStateOf(1.0f)
        private set

    /**
     * The Skip Silence toggle: silent stretches are trimmed by mpv's
     * `silenceremove` filter while playback runs.
     *
     * An app-level preference, so it persists in [Settings] (the properties
     * file) rather than the player-session row: it survives restarts the way
     * theme and language do, and the schema stays untouched.
     */
    var skipSilence by mutableStateOf(Settings.isSkipSilenceEnabled())
        private set

    /** The Loudness Boost toggle: a fixed +6 dB lift via mpv's `volume` filter. */
    var loudnessBoost by mutableStateOf(Settings.isLoudnessBoostEnabled())
        private set
    // True between a play attempt and the first position report (or an error).
    // The engine reports PLAYING optimistically before the file has actually
    // loaded, so this is cleared by onPositionChanged rather than by the play
    // state change — otherwise it would be reset within the same call.
    var isLoading by mutableStateOf(false)
        private set

    /**
     * mpv paused for cache mid-playback (see [PlayerEvent.Buffering]). A stall
     * the player recovers from by itself — the UI shows it without a retry
     * affordance, unlike [error].
     */
    var isBuffering by mutableStateOf(false)
        private set

    /**
     * Last playback failure as a [PlaybackErrorCategory] constant, or null.
     * Deliberately machine-readable: the UI maps it to human copy via Strings.
     * Cleared by any new play attempt and by stop().
     */
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * The engine session [error] was reported for, or null when there is none.
     *
     * An error is a fact about one load, so it is only allowed to survive while
     * that load is the one being listened to. A later `PLAYING`/`Progress` for
     * the same session proves the track recovered and clears it; a new load or
     * [stop] discards it with the session it belonged to. Without this, a
     * failure that belonged to another episode stayed on screen while a healthy
     * one was playing, and the play button "retried" playback from zero.
     */
    private var errorSession: Long? = null

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

    /**
     * Chapters mpv reported for the loaded file, empty when it has none.
     *
     * Replaced wholesale on every [PlayerEvent.ChaptersLoaded] — a file without
     * chapters reports an empty list, which is what clears the previous file's
     * entries. Every load path also resets this eagerly so a new episode never
     * shows the old one's chapters while it is still loading.
     */
    var chapters by mutableStateOf<List<MpvChapter>>(emptyList())
        private set

    /** mpv's `chapter` index into [chapters], or -1 when no chapter applies. */
    var currentChapterIndex by mutableIntStateOf(-1)
        private set

    var sleepTimerTrigger by mutableStateOf<Long?>(null)
        private set
    var sleepTimerMinutes by mutableStateOf<Int?>(null)
        private set

    val queue = mutableStateListOf<QueueItem>()
    var queueIndex by mutableIntStateOf(-1)
        private set

    /**
     * Monitor guarding every read-modify-write of [queue] and [queueIndex].
     *
     * [handleEngineEvent] runs on the mpv worker thread and advances the queue
     * through [playNext], while the UI thread mutates the same
     * [SnapshotStateList] from play/addToQueue/removeFromQueue/moveQueueItem/
     * removeSelectedFromQueue/clearQueue. Snapshot isolation protects *readers*
     * from a writer; it does not protect two writers from each other, and two
     * threads appending/removing concurrently can lose or duplicate entries in
     * the list's persistent backing.
     *
     * The lock is never held across [startLoad], [stop], [playFromQueue], or a
     * database call. Those act on the engine or suspend, and a monitor held
     * there would let a slow worker (or a slow SQLite query) block the UI thread
     * that is trying to mutate the queue. The pattern is always: decide and
     * mutate inside the lock, then perform the follow-up outside it.
     *
     * Java monitors are reentrant, so a locked helper may call another locked
     * section on the same thread, and `removeFromQueue` may call `playFromQueue`
     * after releasing. [queue] itself stays public for composition reads; those
     * are snapshot reads and need no lock.
     */
    private val queueLock = Any()

    /** What a queue mutation decided playback must do after [queueLock] is released. */
    private sealed interface QueueFollowUp {
        /** Start the item that now lives at [index]. */
        data class Play(val index: Int) : QueueFollowUp

        /** Nothing is left to play; stop the transport. */
        data object Stop : QueueFollowUp
    }

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

    /**
     * Allocates the id for the next load.
     *
     * The id is claimed here and [activeSession] is set to it *before* the
     * engine is asked to open, because the engine can deliver events from inside
     * `open()`: a fake engine does it synchronously, and a real one can for a
     * load that fails immediately. An engine-assigned id would mean those first
     * events were compared against the previous session and dropped as stale —
     * silently losing the failure report and leaving the spinner up for ever.
     */
    private val sessionCounter = AtomicLong(0)
    private fun claimSession(): Long = sessionCounter.incrementAndGet()

    /**
     * Claims a session, makes it active, then asks the engine to load.
     *
     * The order is the point: [activeSession] must already hold the id before
     * `open` can emit anything, or the first events of every load are discarded
     * as stale.
     */
    private fun startLoad(url: String, durationMs: Long, startPositionMs: Long = 0L) {
        val session = claimSession()
        activeSession = session
        player.open(
            session = session,
            url = url,
            speed = playbackSpeed,
            startPositionMs = startPositionMs,
            durationMs = durationMs
        )
    }

    init {
        Logger.d(TAG, "MediaPlayerState initialized")
        player.setEventHandler { event -> handleEngineEvent(event) }
        // The persisted enhancement toggles reach the engine before any load:
        // the command is queued ahead of every open, so a restarted app resumes
        // with the filters it saved. Writing the chain on an idle handle is
        // safe — `af` is a global property, not per-file.
        pushAudioFilters()
    }

    /**
     * Applies one engine event.
     *
     * Runs on the engine thread, so it must stay cheap and must not block.
     * Compose state writes are safe from any thread (they are snapshotted), and
     * the queue decision below only reads fields this class owns.
     *
     * Every branch that acts on the *loaded track* checks [currentUrl] first:
     * after [stop] the transport is empty, and a callback that was already in
     * flight must not resurrect a position, an error, or auto-advance the queue.
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
                // A live PLAYING for the failed session means the load made it
                // after all; keeping the error would make the UI offer a retry
                // that restarts a track already playing.
                if (event.state == PlaybackState.PLAYING) clearErrorFor(event.session)
                isPlaying = event.state == PlaybackState.PLAYING
                if (event.state != PlaybackState.PLAYING) {
                    isLoading = false
                    // A user pause while stalled must not keep advertising a
                    // stall; mpv does not clear paused-for-cache for it.
                    isBuffering = false
                }
            }

            is PlayerEvent.Progress -> {
                if (currentUrl == null) return
                clearErrorFor(event.session)
                currentPosition = event.positionMs
                if (event.durationMs > 0) duration = event.durationMs
                isLoading = false
            }

            is PlayerEvent.ChaptersLoaded -> {
                if (currentUrl == null) return
                // The whole list is replaced, including the empty one a
                // chapterless file reports. The index resets with it: the
                // observed `chapter` property will re-report as playback moves.
                chapters = event.chapters
                currentChapterIndex = -1
            }

            is PlayerEvent.ChapterChanged -> {
                if (currentUrl == null) return
                currentChapterIndex = event.index
            }

            is PlayerEvent.Buffering -> {
                if (currentUrl == null) return
                isBuffering = event.active
            }

            is PlayerEvent.TrackCompleted -> {
                if (currentUrl == null) return
                // No timestamp arithmetic: the engine already proved this event
                // belongs to the track the user is listening to.
                Logger.i(TAG, "track completed, advancing")
                isPlaying = false
                isLoading = false
                isBuffering = false
                playNext()
            }

            is PlayerEvent.TrackFailed -> {
                if (currentUrl == null) return
                // The engine deliberately does not emit StateChanged here, so the
                // queue cannot auto-advance past an episode that simply failed.
                Logger.e(TAG, "playback failed: ${event.category}")
                isPlaying = false
                isLoading = false
                isBuffering = false
                error = event.category
                errorSession = event.session
            }
        }
    }

    /**
     * Clears [error] when [session] is the load it was reported for.
     *
     * An error from an *older* session is not cleared here: the stale-event
     * guard above already prevents one from being installed, and clearing on an
     * unrelated session would hide a real failure on the current track.
     */
    private fun clearErrorFor(session: Long) {
        if (errorSession == session) {
            error = null
            errorSession = null
        }
    }

    /** Forgets any recorded failure; used whenever a new load takes over. */
    private fun clearError() {
        error = null
        errorSession = null
    }

    fun play(url: String, title: String? = null, subtitle: String? = null, artworkUrl: String? = null, podcastArtworkUrl: String? = null, durationMs: Long = 0L, episodeId: String? = null, fallbackUrl: String? = null) {
        Logger.i(TAG, "play() title=$title, url=$url, durationMs=$durationMs")
        currentUrl = url
        currentTitle = title
        currentSubtitle = subtitle
        currentArtworkUrl = artworkUrl ?: podcastArtworkUrl
        currentEpisodeId = episodeId
        isLoading = true
        isBuffering = false
        clearError()
        // Reflect the intent immediately: the user pressed play, so the
        // transport should show "pause" without waiting for the engine
        // round-trip. The engine's StateChanged(PLAYING) confirms it and
        // TrackFailed clears it, which is the same contract the old callbacks
        // provided — only now delivered as ordered events on one channel.
        isPlaying = true
        // Timing belongs to the episode being replaced, not the new one. The
        // load starts at zero (see the open() call below), and mpv reports the
        // real duration only once the file is ready; until then a half-minute
        // progress heartbeat would write the *previous* track's position under
        // the new episode id. At 95% of the old episode that marks the new one
        // finished, permanently. playFromQueue already resets for the same
        // reason.
        currentPosition = 0L
        duration = durationMs.coerceAtLeast(0L)
        // The engine re-reports chapters on FILE_LOADED, but the old file's
        // list must not survive the load window either.
        chapters = emptyList()
        currentChapterIndex = -1

        synchronized(queueLock) {
            val existingIndex = queue.indexOfFirst { sameEpisode(it, url, episodeId) }
            if (existingIndex >= 0) {
                // Same episode. Its URL can differ — playing a downloaded copy
                // swaps the stream for a local path — so update the existing entry
                // in place. Appending would give the drawer two items with the same
                // key, which makes LazyColumn throw "Key was already used".
                val existing = queue[existingIndex]
                queueIndex = existingIndex
                queue[existingIndex] = existing.copy(
                    url = url,
                    title = title ?: existing.title,
                    subtitle = subtitle ?: existing.subtitle,
                    artworkUrl = artworkUrl ?: existing.artworkUrl,
                    podcastArtworkUrl = podcastArtworkUrl ?: existing.podcastArtworkUrl,
                    episodeId = episodeId ?: existing.episodeId,
                    // Keep the stream URL on the entry that now points at a
                    // download: if the file is deleted mid-session, playNext
                    // resolves this very item and needs the fallback.
                    fallbackUrl = fallbackUrl ?: existing.fallbackUrl
                )
            } else {
                queue.add(QueueItem(url, title ?: "Unknown", subtitle = subtitle, artworkUrl = currentArtworkUrl, podcastArtworkUrl = podcastArtworkUrl, episodeId = episodeId, fallbackUrl = fallbackUrl))
                queueIndex = queue.size - 1
            }
        }

        // Outside the lock: this reaches the engine, and the worker may already
        // be trying to take queueLock from handleEngineEvent.
        // Pass the current speed explicitly. The engine's default is 1.0x and it
        // writes that to the player on every load, so without this a new track
        // silently reverted to 1x while the UI still displayed the old speed.
        startLoad(url, durationMs)
        // The override lands right after the load: a show with a stored speed
        // plays at it from the first moments, one indexed query later.
        applyPodcastSpeedOverride(episodeId)
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
        targetHistoryId: Int? = null,
        fallbackUrl: String? = null
    ) {
        Logger.i(TAG, "playWithContext() targetUrl=$targetUrl, contextSize=${context.size}")
        currentUrl = targetUrl
        currentTitle = title
        currentSubtitle = subtitle
        currentArtworkUrl = artworkUrl ?: podcastArtworkUrl
        currentEpisodeId = episodeId
        isLoading = true
        isBuffering = false
        clearError()
        // Reflect the intent immediately: the user pressed play, so the
        // transport should show "pause" without waiting for the engine
        // round-trip. The engine's StateChanged(PLAYING) confirms it and
        // TrackFailed clears it, which is the same contract the old callbacks
        // provided — only now delivered as ordered events on one channel.
        isPlaying = true
        // Same reset as play(): the new load starts at zero, so keeping the old
        // episode's position/duration here let the progress heartbeat record a
        // bogus resume point — or mark the new episode completed.
        currentPosition = 0L
        duration = durationMs.coerceAtLeast(0L)
        chapters = emptyList()
        currentChapterIndex = -1

        synchronized(queueLock) {
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
                queue.add(QueueItem(targetUrl, title ?: "Unknown", subtitle = subtitle, artworkUrl = currentArtworkUrl, podcastArtworkUrl = podcastArtworkUrl, episodeId = episodeId, historyId = targetHistoryId, fallbackUrl = fallbackUrl))
                queueIndex = queue.size - 1
            } else if (fallbackUrl != null && queue[queueIndex].fallbackUrl == null) {
                // The target matched a context row that carries no stream URL
                // (a download path from a list built before this information
                // existed). Record it, so deleting the file while this session
                // is live still falls back to the network on the next play.
                queue[queueIndex] = queue[queueIndex].copy(fallbackUrl = fallbackUrl)
            }
        }

        startLoad(targetUrl, durationMs)
        applyPodcastSpeedOverride(episodeId)
    }

    fun playFromQueue(index: Int) {
        val item = synchronized(queueLock) {
            if (index < 0 || index >= queue.size) {
                Logger.w(TAG, "playFromQueue: invalid index=$index, queueSize=${queue.size}")
                return
            }
            queueIndex = index
            queue[index]
        }
        Logger.i(TAG, "playFromQueue: index=$index, title=${item.title}")
        val playableUrl = resolvePlayableUrl(item)
        currentUrl = playableUrl
        currentTitle = item.title
        currentSubtitle = item.subtitle
        currentArtworkUrl = item.artworkUrl ?: item.podcastArtworkUrl
        currentEpisodeId = item.episodeId
        isLoading = true
        isBuffering = false
        clearError()
        // Same optimistic intent as play(): the transport shows pause at once.
        isPlaying = true
        // Reset the previous track's timing. mpv only reports the new duration
        // once the file loads, and until then `seekForward` would clamp against
        // the old episode's length — jumping past the end of a shorter one.
        currentPosition = 0L
        duration = 0L
        chapters = emptyList()
        currentChapterIndex = -1
        startLoad(playableUrl, 0L)
        // Queue auto-advance lands here too, so each episode plays at its own
        // show's speed as the queue moves between podcasts.
        applyPodcastSpeedOverride(item.episodeId)
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
     * Whether [existing] is the same episode as the ([url], [episodeId]) pair.
     *
     * Episode id wins when both sides carry one: a downloaded copy and its
     * stream are two URLs for one episode, and the queue must hold one row.
     * Otherwise the url decides — that is also the drawer's fallback key when
     * neither side has an episodeId.
     *
     * Every queue-inserting path funnels through this one predicate. When
     * [play] and [addToQueue] disagreed, one episode could enter the queue
     * twice under the same `episodeId ?: url` key, and the drawer's LazyColumn
     * threw `IllegalArgumentException: Key was already used`.
     */
    private fun sameEpisode(existing: QueueItem, url: String, episodeId: String?): Boolean =
        if (episodeId != null && existing.episodeId != null) {
            existing.episodeId == episodeId
        } else {
            existing.url == url
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
        isDownloaded: Boolean = false,
        fallbackUrl: String? = null
    ): Boolean = synchronized(queueLock) {
        if (queue.any { existing -> sameEpisode(existing, url, episodeId) }) {
            Logger.d(TAG, "addToQueue: already queued, ignoring title=$title")
            false
        } else {
            Logger.d(TAG, "addToQueue: title=$title")
            queue.add(QueueItem(url, title, artworkUrl = artworkUrl, podcastArtworkUrl = podcastArtworkUrl, episodeId = episodeId, isDownloaded = isDownloaded, fallbackUrl = fallbackUrl))
            true
        }
    }

    fun removeFromQueue(index: Int) {
        val followUp = synchronized(queueLock) {
            if (index < 0 || index >= queue.size) return
            Logger.d(TAG, "removeFromQueue: index=$index")
            val outcome = PlaybackRules.indexAfterRemoval(
                queueSizeAfterRemoval = queue.size - 1,
                removedIndex = index,
                currentIndex = queueIndex
            )
            queue.removeAt(index)
            queueIndex = outcome.nextIndex
            when {
                !outcome.playbackWasRemoved -> null
                outcome.shouldStopPlayback -> QueueFollowUp.Stop
                else -> QueueFollowUp.Play(outcome.nextIndex)
            }
        }
        // The engine is only touched once the monitor is released: playFromQueue
        // takes queueLock itself, and stop() talks to the engine.
        when (followUp) {
            is QueueFollowUp.Play -> playFromQueue(followUp.index)
            QueueFollowUp.Stop -> stop()
            null -> Unit
        }
    }

    fun playNext() {
        // The decision needs queue and queueIndex to agree; acting on it happens
        // outside the lock through playFromQueue, which is also called directly
        // by the UI.
        val next = synchronized(queueLock) {
            PlaybackRules.nextIndexToPlay(queueIndex, queue.size)
        }
        if (next == null) {
            Logger.d(TAG, "playNext: no more items in queue")
        } else {
            playFromQueue(next)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        synchronized(queueLock) {
            if (fromIndex !in queue.indices || toIndex !in queue.indices) return
            val item = queue.removeAt(fromIndex)
            queue.add(toIndex, item)
            queueIndex = PlaybackRules.indexAfterMove(queueIndex, fromIndex, toIndex)
        }
    }

    fun removeSelectedFromQueue(selectedIndices: Set<Int>) {
        val followUp = synchronized(queueLock) {
            // Only indices that exist in the queue are actually removed, so only
            // those may influence the index arithmetic: an out-of-range selection
            // would otherwise count as an "item before the playing one" and shift
            // the playhead onto its neighbour.
            val removed = selectedIndices.filter { it in queue.indices }.toSet()
            if (removed.isEmpty()) return
            val outcome = PlaybackRules.indexAfterBatchRemoval(
                queueSizeAfterRemoval = queue.size - removed.size,
                removedIndices = removed,
                currentIndex = queueIndex
            )
            for (index in removed.sortedDescending()) {
                queue.removeAt(index)
            }
            queueIndex = outcome.nextIndex
            when {
                !outcome.playbackWasRemoved -> null
                outcome.shouldStopPlayback -> QueueFollowUp.Stop
                else -> QueueFollowUp.Play(outcome.nextIndex)
            }
        }
        when (followUp) {
            is QueueFollowUp.Play -> playFromQueue(followUp.index)
            QueueFollowUp.Stop -> stop()
            null -> Unit
        }
    }

    fun clearQueue() {
        synchronized(queueLock) {
            queue.clear()
            queueIndex = -1
        }
        stop()
    }

    fun playPrevious() {
        // Snapshot the position and the playhead together: the engine thread can
        // advance the queue between this decision and the action below.
        val action = synchronized(queueLock) {
            PlaybackRules.decidePrevious(currentPosition, queueIndex)
        }
        when (action) {
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
        // Nothing loaded. Unpausing an engine that holds no file still produces
        // a `pause=false` property event, which flips the transport to "pause"
        // for audio that does not exist. Start the queued track instead when
        // there is one, and do nothing when there is not.
        if (currentUrl == null) {
            val queuedIndex = synchronized(queueLock) {
                if (queueIndex in queue.indices) queueIndex else -1
            }
            if (queuedIndex >= 0) playFromQueue(queuedIndex)
            return
        }
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
        // Invalidate the session: an event the engine had already queued when
        // stop() was called must not be treated as belonging to what is playing.
        // Without this a late TrackCompleted auto-advanced the queue — the user
        // pressed stop and got the next episode — and a late TrackFailed put the
        // error back. Session 0 is the "nothing loaded" value no real session
        // ever uses, so every in-flight event now fails the stale check above.
        activeSession = 0L
        errorSession = null
        // The engine now reports this too, but the state holder owns the field:
        // if a future engine stops silently, `isPlaying` must still end up
        // false or the play button stays inverted and disabled forever.
        isPlaying = false
        currentUrl = null
        currentTitle = null
        // The subtitle is the show name, and the players draw it as a second
        // line under the title. Leaving it made a stopped player read "no
        // title / still that show".
        currentSubtitle = null
        currentArtworkUrl = null
        currentEpisodeId = null
        error = null
        isLoading = false
        isBuffering = false
        // Position/duration belong to the episode that was playing; leaving
        // them set made the next track inherit the previous one's length.
        currentPosition = 0L
        duration = 0L
        chapters = emptyList()
        currentChapterIndex = -1
    }

    fun seek(positionMs: Long) {
        player.seek(positionMs)
    }

    fun seekBack(incrementMs: Long = 10000L) {
        val newPos = (currentPosition - incrementMs).coerceAtLeast(0L)
        seek(newPos)
    }

    fun seekForward(incrementMs: Long = 10000L) {
        val target = currentPosition + incrementMs
        // `duration` is 0 from the moment a track starts until the engine
        // reports one, and clamping against it turned "forward 10 s" into
        // "back to the start" for the whole load window.
        seek(if (duration > 0L) target.coerceAtMost(duration) else target)
    }

    @Suppress("FunctionName")
    fun changeVolume(vol: Int) {
        // Clamped here as well as in the engine: the UI shows this field, and a
        // restored 250 from a corrupt session row would display a volume the
        // engine is not using.
        val clamped = vol.coerceIn(0, 100)
        player.setVolume(clamped)
        volume = clamped
        if (clamped > 0) lastAudibleVolume = clamped
    }

    fun toggleMute() {
        if (volume > 0) {
            lastAudibleVolume = volume
            changeVolume(0)
        } else {
            changeVolume(if (lastAudibleVolume > 0) lastAudibleVolume else DEFAULT_VOLUME)
        }
    }

    /**
     * Flips Skip Silence and remembers the choice.
     *
     * Same-value calls do nothing, so a double-click cannot queue two
     * identical filter writes; the engine skips them anyway, but the state
     * layer owning the guard keeps the persistence write off the hot path too.
     *
     * Named `change`, not `set`, like [changeVolume] and
     * [changePlaybackSpeed]: a delegated `var` already generates a private
     * `setSkipSilence`, and the JVM signatures would clash.
     */
    @Suppress("FunctionName")
    fun changeSkipSilence(enabled: Boolean) {
        if (skipSilence == enabled) return
        skipSilence = enabled
        Settings.setSkipSilence(enabled)
        pushAudioFilters()
        Logger.d(TAG, "changeSkipSilence: $enabled")
    }

    /** Flips Loudness Boost and remembers the choice; see [changeSkipSilence]. */
    @Suppress("FunctionName")
    fun changeLoudnessBoost(enabled: Boolean) {
        if (loudnessBoost == enabled) return
        loudnessBoost = enabled
        Settings.setLoudnessBoost(enabled)
        pushAudioFilters()
        Logger.d(TAG, "changeLoudnessBoost: $enabled")
    }

    /**
     * Sends both toggles to the engine, never just the one that changed.
     *
     * The `af` property is overwritten from the pair, so the engine can apply
     * it as one canonical string — the idempotence guarantee lives there.
     */
    private fun pushAudioFilters() {
        player.setAudioFilters(skipSilence, loudnessBoost)
    }

    /**
     * Sends [speed] to the engine and mirrors it in the UI state.
     *
     * Everything that decides a speed value funnels through here: a user pick,
     * a keyboard step, a restored session, and an applied podcast override.
     */
    private fun applySpeed(speed: Float) {
        player.setSpeed(speed)
        playbackSpeed = speed
        Logger.d(TAG, "applySpeed: speed=$speed")
    }

    @Suppress("FunctionName")
    fun changePlaybackSpeed(speed: Float) {
        applySpeed(speed)
        // A manual speed change is the "this show always plays at this speed"
        // gesture: store it on the loaded episode's podcast. Nothing loaded —
        // nothing to attribute the choice to, so nothing is written.
        val episodeId = currentEpisodeId ?: return
        scope.launch {
            try {
                speedOverrideWriter(episodeId, speed)
            } catch (e: Exception) {
                Logger.e(TAG, "speed override write failed for $episodeId", e)
            }
        }
    }

    /**
     * Applies the loaded episode's podcast speed override, when it has one.
     *
     * Runs after every load ([play], [playWithContext], [playFromQueue],
     * [restoreSession]): a show with a stored override plays at that speed —
     * via [applySpeed], the engine's existing `setSpeed` path. A show without
     * one keeps the session speed, which is exactly the pre-override behaviour.
     *
     * The lookup suspends (SQLite), so it cannot run before [startLoad]: the
     * engine opens at the previous speed and is retargeted the moment the row
     * is read — a window of one indexed query.
     *
     * The episode is captured and re-checked after the lookup: if the user has
     * moved on to another episode, the override belongs to a show that is no
     * longer playing and must not clobber the new one's speed.
     */
    private fun applyPodcastSpeedOverride(episodeId: String?) {
        if (episodeId == null) return
        scope.launch {
            val override = try {
                speedOverrideLookup(episodeId)
            } catch (e: Exception) {
                Logger.e(TAG, "speed override lookup failed for $episodeId", e)
                null
            } ?: return@launch
            if (currentEpisodeId != episodeId) return@launch
            applySpeed(override)
        }
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
        // Nearest, not "first preset at or above": the latter rounded up, so a
        // session restored at 1.1x stepped to 1.5x and skipped 1.25x entirely.
        val current = SPEED_PRESETS.indices.minByOrNull { index ->
            kotlin.math.abs(SPEED_PRESETS[index] - playbackSpeed)
        } ?: 0
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
     *
     * The queue is copied under [queueLock] and written without it: the DAO
     * calls suspend, and a monitor held across a suspension point would block
     * the UI thread's next queue mutation for as long as SQLite takes.
     */
    suspend fun saveSession(database: AppDatabase) {
        val (rows, savedQueueIndex) = synchronized(queueLock) {
            val snapshot = queue.mapIndexed { index, item ->
                PlayerQueueRow(
                    queueOrder = index,
                    url = item.url,
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUrl = item.artworkUrl,
                    podcastArtworkUrl = item.podcastArtworkUrl,
                    episodeId = item.episodeId,
                    isDownloaded = item.isDownloaded,
                    // Persisted so a deleted download can still fall back to the
                    // stream after a restart, not only in the running session.
                    fallbackUrl = item.fallbackUrl
                )
            }
            snapshot to queueIndex
        }
        Logger.d(TAG, "saveSession: saving queue (${rows.size} items), queueIndex=$savedQueueIndex, pos=${currentPosition}ms")
        database.playerQueue.saveQueue(rows)
        database.playerSession.saveSession(
            queueIndex = savedQueueIndex,
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
        // Snapshot everything before the first suspension point. This used to
        // read `episodeId` up front and `currentPosition`/`duration` only after
        // `getById` suspended, so a track switch in between wrote the *new*
        // episode's position under the *old* episode id.
        val episodeId = currentEpisodeId ?: return
        val position = currentPosition
        val duration = duration
        if (position <= 0L && duration <= 0L) return
        val origin = database.episodes.getById(episodeId)?.origin ?: return
        // The player may have moved on while the row was being read. A write now
        // would describe an episode that is no longer loaded, so drop it; the
        // next heartbeat records whichever episode is current under its own id.
        if (currentEpisodeId != episodeId) {
            Logger.d(TAG, "saveEpisodeProgress: episode changed to ${currentEpisodeId}, discarding progress for $episodeId")
            return
        }
        database.progress.record(
            episodeId = episodeId,
            origin = origin,
            positionMs = position,
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

        // Restore speed and volume regardless. applySpeed, not
        // changePlaybackSpeed: a restore is not a user gesture, and writing the
        // speed back would pin whatever the session happened to hold onto the
        // show the user is about to resume.
        applySpeed(session.playbackSpeed)
        volume = session.volume
        changeVolume(session.volume)

        if (rows.isEmpty()) {
            Logger.d(TAG, "restoreSession: empty queue, nothing to restore")
            return
        }

        // Rebuild the queue under the monitor: the engine thread may still be
        // advancing a queue from a previous restore while this one loads.
        val restored = synchronized(queueLock) {
            queue.clear()
            rows.forEach { row ->
                queue.add(QueueItem(
                    url = row.url,
                    title = row.title,
                    subtitle = row.subtitle,
                    artworkUrl = row.artworkUrl,
                    podcastArtworkUrl = row.podcastArtworkUrl,
                    episodeId = row.episodeId,
                    isDownloaded = row.isDownloaded,
                    // Carried through the session so a download deleted while the
                    // app was closed still falls back to its stream on restart,
                    // instead of failing with a FILE error (see resolvePlayableUrl).
                    fallbackUrl = row.fallbackUrl
                ))
            }
            val restoredIndex = PlaybackRules.clampRestoredIndex(queue.size, session.queueIndex)
            queueIndex = restoredIndex
            restoredIndex to (if (restoredIndex in queue.indices) queue[restoredIndex] else null)
        }
        val idx = restored.first
        val item = restored.second
        if (idx < 0 || item == null) {
            // The queue was persisted but nothing was ever playing (the saved
            // index is the -1 sentinel). Restore the queue and leave the
            // transport empty instead of opening the first item: coercing the
            // sentinel to 0 would resume an episode the user never started.
            Logger.d(TAG, "restoreSession: queue restored without a playing item (queueIndex=${session.queueIndex})")
            currentUrl = null
            currentTitle = null
            currentSubtitle = null
            currentArtworkUrl = null
            currentEpisodeId = null
            isPlaying = false
            return
        }

        // Restore current display state. The restored row may point at a
        // download that no longer exists, so resolve before displaying or
        // loading: otherwise the transport would name a file that cannot play
        // and the load would fail with a FILE error although the stream works.
        val playableUrl = resolvePlayableUrl(item)
        currentUrl = playableUrl
        currentTitle = item.title
        currentSubtitle = item.subtitle
        currentArtworkUrl = item.artworkUrl ?: item.podcastArtworkUrl
        // The queue rows and the session row are written by different code
        // paths, so a crash between them can leave them describing different
        // episodes. When they disagree, trust the queue row for identity and
        // drop the position — resuming the wrong episode half-way through is a
        // worse outcome than starting the right one over.
        val sessionEpisodeId = session.currentEpisodeId
        val queueEpisodeId = item.episodeId
        val sameEpisode = queueEpisodeId == null ||
            sessionEpisodeId == null ||
            queueEpisodeId == sessionEpisodeId
        val restorePositionMs = if (sameEpisode) session.currentPositionMs else 0L
        currentEpisodeId = queueEpisodeId ?: sessionEpisodeId

        // Load into the engine at the saved position, then pause.
        //
        // open() with a non-zero start position already leaves mpv paused (see
        // MpvAudioPlayerEngine.applyOpen), so this is belt-and-braces for engines
        // that do not: it is what guarantees a restored episode never makes a
        // sound on launch. The engine applies commands in submission order, so
        // `pause` lands after `loadfile` regardless of how long the load takes.
        Logger.i(TAG, "restoreSession: restoring playback at idx=$idx, pos=${restorePositionMs}ms")
        // Show the resume point before the engine reports one: without this the
        // transport read 0:00 until mpv's first progress event.
        currentPosition = restorePositionMs
        startLoad(playableUrl, 0L, startPositionMs = restorePositionMs)
        // A resumed show with a stored speed must not fall back to the session
        // speed just because the app restarted — this load path is how most
        // listening sessions begin.
        applyPodcastSpeedOverride(currentEpisodeId)
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
        database.playerSession.updatePosition(
            currentPositionMs = currentPosition,
            queueIndex = queueIndex,
            currentEpisodeId = currentEpisodeId
        )
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

        /** The volume a fresh install starts at, and what un-muting falls back to. */
        const val DEFAULT_VOLUME = 100
    }
}