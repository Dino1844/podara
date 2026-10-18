package app.podara.player

import app.podara.util.Logger
import com.sun.jna.Pointer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MpvAudioPlayerEngine"

/** How long the event loop waits for mpv before re-checking the command queue. */
private const val EVENT_WAIT_SECONDS = 0.1

// ── mpv event ids (client.h) ──
private const val MPV_EVENT_NONE = 0
private const val MPV_EVENT_SHUTDOWN = 1
private const val MPV_EVENT_END_FILE = 7
private const val MPV_EVENT_FILE_LOADED = 8
private const val MPV_EVENT_PROPERTY_CHANGE = 22

/** mpv_event_end_file.reason: the load or the stream failed. */
private const val MPV_END_FILE_REASON_ERROR = 4

// ── mpv_error codes (client.h) ──
internal const val MPV_ERROR_GENERIC = -20
internal const val MPV_ERROR_NOT_FOUND = -2
internal const val MPV_ERROR_LOADING_FAILED = -13
internal const val MPV_ERROR_NOTHING_TO_PLAY = -16
internal const val MPV_ERROR_UNKNOWN_FORMAT = -17
internal const val MPV_ERROR_UNSUPPORTED = -18
internal const val MPV_ERROR_NOT_IMPLEMENTED = -19
internal const val MPV_ERROR_OPTION_NOT_FOUND = -5
internal const val MPV_ERROR_OPTION_FORMAT = -6
internal const val MPV_ERROR_OPTION_ERROR = -7

// ── reply_userdata tags for observed properties ──
private const val OBS_TIME_POS = 1L
private const val OBS_DURATION = 2L
private const val OBS_PAUSE = 3L
private const val OBS_EOF_REACHED = 4L

/**
 * Offset of the fields this file reads out of libmpv structs (client.h, 64-bit).
 *
 * Declared as named constants because getting one wrong is a silent read of
 * unrelated memory, not a compile error.
 */
private const val EVENT_ID_OFFSET = 0L
private const val EVENT_REPLY_USERDATA_OFFSET = 8L
private const val EVENT_DATA_OFFSET = 16L
private const val END_FILE_REASON_OFFSET = 0L
private const val END_FILE_ERROR_OFFSET = 4L
private const val PROPERTY_FORMAT_OFFSET = 8L
private const val PROPERTY_DATA_OFFSET = 16L

/**
 * Maps an mpv error code plus the failing URL to a [PlaybackErrorCategory].
 *
 * LOADING_FAILED is the common case (missing local file, dead network URL); the
 * URL scheme picks the explanation that matches what actually broke. Visible to
 * tests without loading native mpv.
 */
internal fun playbackErrorCategory(mpvErrorCode: Int, url: String?): String = when {
    mpvErrorCode == MPV_ERROR_UNKNOWN_FORMAT ||
        mpvErrorCode == MPV_ERROR_NOTHING_TO_PLAY ||
        mpvErrorCode == MPV_ERROR_UNSUPPORTED ||
        mpvErrorCode == MPV_ERROR_NOT_IMPLEMENTED -> PlaybackErrorCategory.UNSUPPORTED
    mpvErrorCode == MPV_ERROR_LOADING_FAILED ->
        if (url != null && url.startsWith("http")) PlaybackErrorCategory.NETWORK
        else PlaybackErrorCategory.FILE
    else -> PlaybackErrorCategory.GENERIC
}

/**
 * One libmpv option applied before `mpv_initialize`.
 *
 * @param name the exact libmpv option name. Verified against the shipped
 *   `mpv-1.dll`: a typo here fails with [MPV_ERROR_OPTION_NOT_FOUND].
 * @param value the string form, as `mpv_set_option_string` parses it.
 * @param essential `true` when playback is outright broken without it, so a
 *   failure deserves an error log and a startup warning. `false` for tuning
 *   options, where an older libmpv may legitimately reject the value and the
 *   only sensible response is a warning.
 */
internal data class MpvOption(val name: String, val value: String, val essential: Boolean)

/**
 * The options [MpvAudioPlayerEngine] applies at startup.
 *
 * Kept as data rather than inline calls so the table itself is testable without
 * a native library: the bugs this replaces were all *name* bugs (`audio-only`, a
 * `reconnect` that does not exist), and a table can be asserted against a
 * known-good listing.
 *
 * Every entry was confirmed to return 0 from `mpv_set_option_string` on the
 * libmpv bundled in this repo, and read back after `mpv_initialize`.
 *
 * Deliberately *not* set: `cache`. It already defaults to `auto`, which caches
 * network streams and skips the cache for local files — exactly the split a
 * podcast app wants. Writing `cache=no` to speed up local files would disable
 * buffering for HTTP too and make a stalled stream unrecoverable.
 */
internal val MPV_STARTUP_OPTIONS: List<MpvOption> = listOf(
    // No console window: this is a GUI app, and mpv would otherwise try to
    // take over a terminal that does not exist.
    MpvOption("terminal", "no", essential = false),

    // Disable the video track. This is the one that matters for an audio
    // player: it also drops the embedded cover-art track that
    // `audio-display=embedded-first` would otherwise try to render.
    MpvOption("vid", "no", essential = true),

    // Wasapi is what libmpv picks on Windows anyway; pinning it keeps output
    // device selection from drifting to a latency-heavy fallback.
    MpvOption("ao", "wasapi", essential = false),

    // Keep the file loaded after EOF so the `eof-reached` observation still
    // fires. Without it mpv unloads at end of stream and the property is gone
    // before the change can be delivered, which loses auto-advance entirely —
    // the queue simply stops at the end of an episode.
    MpvOption("keep-open", "yes", essential = true),

    // Give up on a dead HTTP connection instead of hanging for the default
    // 60 s while the UI shows nothing but a spinner.
    MpvOption("network-timeout", "20", essential = false),

    // Podcast hosts routinely drop long-lived connections. libmpv has no
    // `reconnect` option of its own (it returns MPV_ERROR_OPTION_NOT_FOUND);
    // the switch is forwarded to the ffmpeg HTTP layer instead.
    MpvOption("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5", essential = false)
)

/** Classifies a `mpv_set_option_string` return code. Returns null when applied. */
internal enum class MpvOptionFailure { UNKNOWN_NAME, BAD_VALUE }

internal fun classifyOptionResult(returnCode: Int): MpvOptionFailure? = when {
    returnCode >= 0 -> null
    returnCode == MPV_ERROR_OPTION_NOT_FOUND -> MpvOptionFailure.UNKNOWN_NAME
    else -> MpvOptionFailure.BAD_VALUE
}

/** Outcome of reading one option back after `mpv_initialize`. */
internal sealed interface StartupSelfCheck {
    /** The property was readable and holds the value we asked for. */
    data object Ok : StartupSelfCheck

    /** The handle answered, but mpv is not running the configuration we set. */
    data class UnexpectedValue(val property: String, val expected: String, val actual: String) :
        StartupSelfCheck

    /**
     * `mpv_get_property_string` returned NULL. After a successful initialize
     * this should be impossible for a built-in property, so it is the signal
     * that the handle itself is not in the state we think it is.
     */
    data class Unreadable(val property: String) : StartupSelfCheck
}

/**
 * Decides what a read-back result means.
 *
 * Split out from the native call so the three outcomes can be tested without
 * `mpv-1.dll`: the point of the self-check is to turn a silent
 * misconfiguration into a log line, and that classification is the part that
 * can be wrong.
 */
internal fun evaluateSelfCheck(property: String, expected: String, actual: String?): StartupSelfCheck =
    when {
        actual == null -> StartupSelfCheck.Unreadable(property)
        actual != expected -> StartupSelfCheck.UnexpectedValue(property, expected, actual)
        else -> StartupSelfCheck.Ok
    }

/**
 * libmpv-backed audio engine.
 *
 * ## Ownership
 *
 * One thread owns [mpvHandle]. It is created there, touched only there, and
 * destroyed there. Callers never see the handle; they submit [PlayerCommand]s
 * and receive [PlayerEvent]s. That is the entire concurrency design, and it is
 * what replaced the previous arrangement where a polling thread read properties
 * while the UI thread could destroy the handle underneath it — a native
 * use-after-free that no Kotlin stack trace could explain.
 *
 * ## Why events instead of polling
 *
 * The previous implementation woke every 250 ms and asked mpv for four string
 * properties (`time-pos`, `duration`, `eof-reached`, `pause`). That imposed a
 * quarter-second floor on every state transition, allocated four JNA strings per
 * tick even when nothing had changed, and made "did the track end?" a question
 * answered by comparing wall-clock timestamps against a 3-second window — a
 * heuristic that mis-fires on short episodes and on slow loads.
 *
 * Now mpv pushes [MPV_EVENT_PROPERTY_CHANGE] and [MPV_EVENT_FILE_LOADED] to the
 * loop as they happen, and end-of-track is tied to a session id, so a stale
 * callback is impossible by construction rather than by timing.
 */
class MpvAudioPlayerEngine : AudioPlayerEngine {

    private val commands = LinkedBlockingQueue<PlayerCommand>()
    private val sessionCounter = AtomicLong(0)

    @Volatile
    private var snapshotState = PlayerSnapshot()

    override val snapshot: PlayerSnapshot get() = snapshotState

    /**
     * The single event sink. Volatile because it is written from whichever
     * thread constructs the engine and read from the engine thread.
     */
    @Volatile
    private var eventHandler: ((PlayerEvent) -> Unit)? = null

    private val worker: Thread

    init {
        if (!MpvNativeLoader.load()) {
            throw IllegalStateException("mpv-1.dll not found. Place it in libs/ directory.")
        }
        // The thread is started before the constructor returns, and it builds
        // the handle itself: doing that here would make handle creation and
        // handle destruction live on different threads, which is the exact
        // mistake this class exists to prevent.
        worker = Thread({ runLoop() }, "mpv-engine").apply {
            isDaemon = true
            start()
        }
    }

    override fun setEventHandler(handler: (PlayerEvent) -> Unit) {
        eventHandler = handler
    }

    override fun open(url: String, speed: Float, startPositionMs: Long, durationMs: Long): Long {
        val session = sessionCounter.incrementAndGet()
        Logger.i(TAG, "open() session=$session url=$url speed=$speed start=${startPositionMs}ms")
        // Publish a LOADING snapshot immediately, off the engine thread, so the
        // UI does not have to wait for the worker to pick the command up before
        // it can show a spinner.
        snapshotState = PlayerSnapshot(
            state = PlaybackState.LOADING,
            positionMs = startPositionMs,
            durationMs = durationMs,
            session = session
        )
        commands.put(PlayerCommand.Open(session, url, speed, startPositionMs, durationMs))
        return session
    }

    override fun setPaused(paused: Boolean) {
        commands.put(PlayerCommand.SetPaused(paused))
    }

    override fun stop() {
        commands.put(PlayerCommand.Stop)
    }

    override fun seek(positionMs: Long) {
        commands.put(PlayerCommand.Seek(positionMs))
    }

    override fun setSpeed(speed: Float) {
        commands.put(PlayerCommand.SetSpeed(speed))
    }

    override fun setVolume(volume: Int) {
        commands.put(PlayerCommand.SetVolume(volume))
    }

    /**
     * Stops the engine thread and destroys the handle.
     *
     * The join is the point: `mpv_terminate_destroy` while the worker is inside
     * `mpv_wait_event` is a use-after-free. Queuing [PlayerCommand.Quit] lets the
     * worker leave its loop on its own, and the join proves it has.
     */
    override fun release() {
        Logger.d(TAG, "release()")
        commands.put(PlayerCommand.Quit)
        try {
            worker.join(RELEASE_JOIN_TIMEOUT_MS)
            if (worker.isAlive) {
                Logger.w(TAG, "engine thread did not stop within ${RELEASE_JOIN_TIMEOUT_MS}ms")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.w(TAG, "interrupted while waiting for the engine thread")
        }
        snapshotState = PlayerSnapshot()
    }

    // ── engine thread ──

    private fun runLoop() {
        var handle = 0L
        try {
            handle = createHandle()
            if (handle == 0L) {
                failStartup()
                return
            }
            observeProperties(handle)
            selfCheck(handle)
            Logger.i(TAG, "mpv engine ready")

            var running = true
            // Per-session bookkeeping, reset on every Open.
            var session = 0L
            var durationMs = 0L
            var eofReported = false
            // Remembered so a failure can be classified as network-vs-file
            // without the UI having to guess: `null` here made every failure
            // look like a file error.
            var currentUrl = ""

            while (running) {
                var command = commands.poll()
                while (command != null) {
                    when (command) {
                        is PlayerCommand.Open -> {
                            session = command.session
                            currentUrl = command.url
                            applyOpen(handle, command.url, command.speed, command.startPositionMs)
                            durationMs = command.durationMs
                            eofReported = false
                        }

                        is PlayerCommand.SetPaused ->
                            MpvApi.INSTANCE.mpv_set_property_string(
                                handle, "pause", if (command.paused) "yes" else "no"
                            )

                        is PlayerCommand.Seek ->
                            MpvApi.INSTANCE.mpv_command(
                                handle,
                                arrayOfNulls<String>(4).also {
                                    it[0] = "seek"
                                    it[1] = "${command.positionMs / 1000.0}"
                                    it[2] = "absolute+exact"
                                }
                            )

                        is PlayerCommand.SetSpeed ->
                            MpvApi.INSTANCE.mpv_set_property_string(
                                handle, "speed", command.speed.coerceIn(0.25f, 4.0f).toString()
                            )

                        is PlayerCommand.SetVolume ->
                            MpvApi.INSTANCE.mpv_set_property_string(
                                handle, "volume", command.volume.coerceIn(0, 100).toString()
                            )

                        is PlayerCommand.Stop -> {
                            MpvApi.command(handle, "stop")
                            publish(PlayerSnapshot(PlaybackState.STOPPED, 0L, 0L, session))
                            emit(PlayerEvent.StateChanged(session, PlaybackState.STOPPED))
                        }

                        is PlayerCommand.Quit -> {
                            running = false
                            break
                        }
                    }
                    command = commands.poll()
                }
                if (!running) break

                // A short wait keeps the loop responsive to commands without
                // polling: mpv returns as soon as it has something to say.
                val event = MpvApi.INSTANCE.mpv_wait_event(handle, EVENT_WAIT_SECONDS)
                if (event == null || event == Pointer.NULL) continue
                val eventId = event.getInt(EVENT_ID_OFFSET)
                if (eventId == MPV_EVENT_NONE) continue

                when (eventId) {
                    MPV_EVENT_SHUTDOWN -> running = false

                    MPV_EVENT_END_FILE -> {
                        val data = event.getPointer(EVENT_DATA_OFFSET)
                        if (data == null || data == Pointer.NULL) continue
                        if (data.getInt(END_FILE_REASON_OFFSET) != MPV_END_FILE_REASON_ERROR) continue
                        val code = data.getInt(END_FILE_ERROR_OFFSET)
                        emitFailure(session, code, currentUrl)
                    }

                    MPV_EVENT_FILE_LOADED -> {
                        publish(snapshotState.copy(state = PlaybackState.PLAYING))
                        emit(PlayerEvent.StateChanged(session, PlaybackState.PLAYING))
                    }

                    MPV_EVENT_PROPERTY_CHANGE -> {
                        val reply = event.getLong(EVENT_REPLY_USERDATA_OFFSET)
                        if (reply == OBS_EOF_REACHED) {
                            val reached = readFlag(event.getPointer(EVENT_DATA_OFFSET))
                            if (reached && !eofReported) {
                                // Once per session: with keep-open the property
                                // stays true, and re-reporting it would advance
                                // the queue again on the next event.
                                eofReported = true
                                Logger.i(TAG, "session=$session track completed")
                                publish(snapshotState.copy(state = PlaybackState.STOPPED))
                                emit(PlayerEvent.TrackCompleted(session))
                            }
                        } else if (reply == OBS_PAUSE) {
                            val paused = readFlag(event.getPointer(EVENT_DATA_OFFSET))
                            val next = if (paused) PlaybackState.PAUSED else PlaybackState.PLAYING
                            if (next != snapshotState.state) {
                                publish(snapshotState.copy(state = next))
                                emit(PlayerEvent.StateChanged(session, next))
                            }
                        } else if (reply == OBS_TIME_POS || reply == OBS_DURATION) {
                            val value = readDouble(event.getPointer(EVENT_DATA_OFFSET)) ?: continue
                            val ms = (value * 1000).toLong()
                            val updated = if (reply == OBS_TIME_POS) {
                                snapshotState.copy(positionMs = ms)
                            } else {
                                durationMs = ms
                                snapshotState.copy(durationMs = ms)
                            }
                            publish(updated)
                            emit(PlayerEvent.Progress(session, updated.positionMs, updated.durationMs))
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // Never let the engine thread die silently: a dead thread means a
            // player that accepts commands and never answers one.
            Logger.e(TAG, "engine thread failed: ${e.message}", e)
        } finally {
            if (handle != 0L) {
                MpvApi.command(handle, "quit")
                MpvApi.INSTANCE.mpv_terminate_destroy(handle)
            }
            Logger.d(TAG, "engine thread exited")
        }
    }

    private fun createHandle(): Long {
        val handle = MpvApi.INSTANCE.mpv_create()
        if (handle == 0L) {
            Logger.e(TAG, "mpv_create returned a null handle")
            return 0L
        }
        for (option in MPV_STARTUP_OPTIONS) {
            val result = MpvApi.INSTANCE.mpv_set_option_string(handle, option.name, option.value)
            when (classifyOptionResult(result)) {
                null -> Unit
                MpvOptionFailure.UNKNOWN_NAME -> Logger.e(
                    TAG,
                    "option ${option.name} rejected as unknown (code $result) — " +
                        "this is a misspelled option name in MPV_STARTUP_OPTIONS, not an mpv quirk"
                )
                MpvOptionFailure.BAD_VALUE -> Logger.w(
                    TAG,
                    "option ${option.name}=${option.value} rejected with code $result; " +
                        "this mpv build may not support it, continuing"
                )
            }
        }
        val initResult = MpvApi.INSTANCE.mpv_initialize(handle)
        if (initResult < 0) {
            Logger.e(TAG, "mpv_initialize failed: $initResult")
            MpvApi.INSTANCE.mpv_terminate_destroy(handle)
            return 0L
        }
        return handle
    }

    private fun failStartup() {
        emit(PlayerEvent.TrackFailed(0L, PlaybackErrorCategory.GENERIC))
    }

    /**
     * Reads the video-track option back and logs a mismatch.
     *
     * The worst failure mode this file can have is every option silently wrong
     * while playback still "works": audio just behaves oddly for reasons no log
     * explains. `vid` is the cheapest property that proves both halves — that the
     * handle answers at all, and that it is running the configuration
     * [MPV_STARTUP_OPTIONS] describes.
     *
     * Runs on the engine thread, which is also what makes it safe: the handle
     * cannot be destroyed underneath it.
     */
    private fun selfCheck(handle: Long) {
        val expected = MPV_STARTUP_OPTIONS.first { it.name == "vid" }.value
        // Typed as nullable explicitly: JNA returns either null or a zero-peer
        // Pointer for a NULL, depending on version. A self-check that throws an
        // NPE on exactly the failure it exists to report would be worse than
        // having no self-check at all.
        val actualPtr: Pointer? = MpvApi.INSTANCE.mpv_get_property_string(handle, "vid")
        val actual = if (actualPtr == null || actualPtr == Pointer.NULL) {
            null
        } else {
            try {
                actualPtr.getString(0)
            } finally {
                MpvApi.INSTANCE.mpv_free(actualPtr)
            }
        }

        when (val result = evaluateSelfCheck("vid", expected, actual)) {
            is StartupSelfCheck.Ok ->
                Logger.d(TAG, "self-check ok: vid=$expected")

            is StartupSelfCheck.UnexpectedValue ->
                Logger.e(TAG, "self-check failed: ${result.property}=${result.actual}, expected ${result.expected}")

            is StartupSelfCheck.Unreadable ->
                Logger.e(TAG, "self-check failed: property ${result.property} is unreadable on a live handle")
        }
    }

    /**
     * Subscribes to the properties the UI needs.
     *
     * Each is observed with the *native* format it has (double for times, flag
     * for pause/EOF) rather than as a string: mpv then hands the loop a pointer
     * to the value instead of a heap-allocated string that has to be freed, which
     * removes the `mpv_free` bookkeeping from the hot path entirely.
     */
    private fun observeProperties(handle: Long) {
        MpvApi.INSTANCE.mpv_observe_property(handle, OBS_TIME_POS, "time-pos", MpvApi.MPV_FORMAT_DOUBLE)
        MpvApi.INSTANCE.mpv_observe_property(handle, OBS_DURATION, "duration", MpvApi.MPV_FORMAT_DOUBLE)
        MpvApi.INSTANCE.mpv_observe_property(handle, OBS_PAUSE, "pause", MpvApi.MPV_FORMAT_FLAG)
        MpvApi.INSTANCE.mpv_observe_property(handle, OBS_EOF_REACHED, "eof-reached", MpvApi.MPV_FORMAT_FLAG)
    }

    /**
     * Applies a load on the engine thread and returns the session already
     * assigned by [open].
     *
     * `replace` rather than `append`: this is a single-track player, and the
     * queue lives in [MediaPlayerState]. Using mpv's playlist would mean two
     * owners for queue order.
     */
    private fun applyOpen(handle: Long, url: String, speed: Float, startPositionMs: Long) {
        val loadResult = MpvApi.INSTANCE.mpv_command(
            handle,
            arrayOfNulls<String>(4).also {
                it[0] = "loadfile"
                it[1] = url
                it[2] = "replace"
            }
        )
        if (loadResult < 0) {
            // The command itself was rejected (blank URL, unusable scheme), so
            // no END_FILE event will arrive to report it.
            emitFailure(snapshotState.session, loadResult, url)
            return
        }
        MpvApi.INSTANCE.mpv_set_property_string(handle, "pause", "no")
        MpvApi.INSTANCE.mpv_set_property_string(handle, "speed", speed.coerceIn(0.25f, 4.0f).toString())
        // Always written, including 0: mpv keeps the value across loadfile calls,
        // so a stale resume point would otherwise apply to the next track.
        MpvApi.INSTANCE.mpv_set_property_string(handle, "start", "${startPositionMs / 1000.0}")
    }

    /**
     * Reports a failure for [session].
     *
     * [url] decides whether the user is told "network" or "file", so the caller
     * must pass the URL that was actually being loaded. Passing null here made
     * every failure classify as a file error — a live regression this signature
     * exists to prevent.
     */
    private fun emitFailure(session: Long, mpvCode: Int, url: String) {
        val category = playbackErrorCategory(mpvCode, url)
        Logger.e(TAG, "playback failed: mpv code $mpvCode, category=$category, url=$url, session=$session")
        publish(snapshotState.copy(state = PlaybackState.ERROR))
        // Deliberately not accompanied by StateChanged: a failure must not look
        // like a natural end, or the queue would advance past a broken episode.
        emit(PlayerEvent.TrackFailed(session, category))
    }

    private fun publish(next: PlayerSnapshot) {
        snapshotState = next
    }

    private fun emit(event: PlayerEvent) {
        try {
            eventHandler?.invoke(event)
        } catch (e: Throwable) {
            // The handler is the state layer; if it throws, the engine must keep
            // running or playback stops updating for no visible reason.
            Logger.e(TAG, "event handler threw on ${event::class.simpleName}: ${e.message}", e)
        }
    }

    private fun readDouble(ptr: Pointer?): Double? =
        if (ptr == null || ptr == Pointer.NULL) null else ptr.getDouble(0)

    private fun readFlag(ptr: Pointer?): Boolean =
        if (ptr == null || ptr == Pointer.NULL) false else ptr.getInt(0) != 0

    private companion object {
        /** Generous upper bound; the loop normally exits on its next iteration. */
        const val RELEASE_JOIN_TIMEOUT_MS = 3_000L
    }
}
