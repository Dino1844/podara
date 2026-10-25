package app.podara.player

import app.podara.util.Logger
import com.sun.jna.Pointer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MpvAudioPlayerEngine"

/** How long the event loop waits for mpv before re-checking the command queue. */
private const val EVENT_WAIT_SECONDS = 0.1

/**
 * How many queued events a drain is allowed to discard before giving up.
 *
 * `mpv_wait_event(handle, 0)` never blocks, so the loop normally exits on the
 * first MPV_EVENT_NONE. The bound exists only so a pathological event storm
 * cannot spin the engine thread forever instead of getting on with the load it
 * was asked for.
 */
private const val DRAIN_MAX_EVENTS = 256

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
private const val OBS_CHAPTER = 5L
private const val OBS_PAUSED_FOR_CACHE = 6L

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

/**
 * Offsets inside `mpv_event_property`, which is what an observed property's
 * `mpv_event.data` points at — *not* the value itself.
 *
 * ```
 * typedef struct mpv_event_property {
 *     const char *name;    // +0
 *     mpv_format  format;  // +8
 *     void       *data;    // +16  -> points at the value
 * } mpv_event_property;
 * ```
 *
 * Reading the event's own `data` pointer as the value silently yielded 0 for
 * every position and duration, because what was being read was this header.
 */
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

// ── Audio enhancement filters (the `af` property) ──
//
// Both enhancements are libavfilter filters mpv already ships, applied through
// the runtime-settable `af` property — no rebuild of the stream, no new
// dependency. They are kept as named data rather than inline literals for the
// same reason MPV_STARTUP_OPTIONS is: a wrong *name* here is a silent no-op,
// and a table can be asserted against.

/**
 * Skip Silence, as one libavfilter graph segment.
 *
 * `stop_periods=-1` is the documented way to trim silence from the *middle* of
 * a stream (positive values only trim the tail); `stop_duration=0.05` keeps
 * natural pauses shorter than 50 ms, so speech rhythm survives; the cutoff is
 * `stop_threshold=-30dB` — anything quieter counts as silence. The parameter is
 * `stop_threshold`, not `threshold`: the short form does not exist in
 * af_silenceremove and would fail filter init (proven against the shipped dll
 * by RealPlaybackIntegrationTest, which reads the chain back from mpv).
 *
 * Order matters: this runs *before* the loudness boost so "what is silence" is
 * judged on the original signal, not on an amplified one.
 */
internal const val SKIP_SILENCE_FILTER = "silenceremove=stop_periods=-1:stop_duration=0.05:stop_threshold=-30dB"

/**
 * Loudness Boost, as one libavfilter graph segment.
 *
 * Chosen over `loudnorm` deliberately: loudnorm is a dynamic processor — it
 * rides the gain up and down, adds a latency window and can pump on speech,
 * which is exactly the material podcasts are made of. A fixed +6 dB lift is
 * predictable, has no state and cannot misbehave; the one trade-off is that
 * already-loud passages can clip at the output, acceptable for conservative
 * podcast mastering and reversible with one click.
 */
internal const val LOUDNESS_BOOST_FILTER = "volume=6dB"

/**
 * Builds the complete `af` property value for the two enhancement toggles.
 *
 * Each filter carries an `@label:` prefix, so the chain is addressable and —
 * because the whole property is *overwritten* with this canonical string on
 * every change rather than appended to — toggling can never stack a second
 * copy of a filter that is already there. Both toggles off yields the empty
 * string, which is mpv's own "no filters" value.
 *
 * Pure and deterministic on its inputs, which is what makes the toggle
 * combination idempotent by construction.
 */
internal fun buildAudioFilterChain(skipSilence: Boolean, loudnessBoost: Boolean): String {
    val filters = mutableListOf<String>()
    if (skipSilence) filters += "@silence:$SKIP_SILENCE_FILTER"
    if (loudnessBoost) filters += "@boost:$LOUDNESS_BOOST_FILTER"
    return filters.joinToString(",")
}

/**
 * The audio output driver used when the engine is constructed silent.
 *
 * `null` is mpv's own discarding output: it decodes normally, so `time-pos`,
 * `duration`, `pause` and `eof-reached` all behave exactly as they do with a
 * real device, but nothing is ever sent to the speakers. Tests use it because
 * they play real files — an integration test that proves the progress bar works
 * should not also play two seconds of tone into someone's headphones on every
 * run of the suite.
 */
internal const val NULL_AUDIO_OUTPUT = "null"

/**
 * Whether engines should be built silent by default.
 *
 * Set by the test task in `build.gradle.kts` rather than only by explicit
 * constructor arguments, because the engine is also created indirectly —
 * `MediaPlayerState()` builds one for every UI test — and those paths would
 * each have to remember to ask for silence. A property makes "tests never make
 * a sound" a property of the test run, not of thirteen call sites.
 */
internal fun silentAudioByDefault(): Boolean =
    System.getProperty("podara.test.silentAudio")?.toBoolean() == true

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
class MpvAudioPlayerEngine(
    /**
     * Play without an audio device.
     *
     * Set by tests so a playback assertion does not also emit sound: the
     * default output driver really reaches the speakers, and a suite that opens
     * real files would otherwise play a burst of tone on every run. Everything
     * observable through [snapshot] and the event stream is unaffected — the
     * `null` driver still decodes, so position, duration, pause and EOF behave
     * identically.
     *
     * Defaults to [silentAudioByDefault], so a test that builds the engine
     * indirectly (through `MediaPlayerState`, or from a UI test) is silent too
     * without every call site having to ask.
     */
    private val silent: Boolean = silentAudioByDefault()
) : AudioPlayerEngine {

    private val commands = LinkedBlockingQueue<PlayerCommand>()

    /** Backs the convenience [open] overload used by engine-level callers. */
    private val standaloneSessionCounter = AtomicLong(0)

    /**
     * Set once the worker thread is gone for good.
     *
     * Without it a dead engine kept accepting commands into a queue nobody
     * reads: `open` returned a session, published LOADING, and then nothing ever
     * happened — no event, no error, a spinner for ever. Failing fast is the
     * only honest answer.
     */
    @Volatile
    private var workerDead = false

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

    override fun open(session: Long, url: String, speed: Float, startPositionMs: Long, durationMs: Long): Long {
        Logger.i(TAG, "open() session=$session url=$url speed=$speed start=${startPositionMs}ms")
        if (workerDead) {
            // Nothing will consume the command, so say so now instead of leaving
            // the caller waiting on events that cannot arrive.
            Logger.e(TAG, "open() rejected: the engine thread is gone")
            snapshotState = PlayerSnapshot(PlaybackState.ERROR, 0L, durationMs, session)
            emit(PlayerEvent.TrackFailed(session, PlaybackErrorCategory.GENERIC))
            return session
        }
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

    /**
     * Loads [url] with an id allocated here rather than by the caller.
     *
     * The interface asks the caller to allocate because the state layer has to
     * know the id before the first event can arrive. Callers that do not — the
     * engine-level tests, which drive the engine directly — can let it allocate,
     * and get the same strictly-increasing ids the engine used to hand out.
     */
    fun open(url: String, speed: Float, startPositionMs: Long, durationMs: Long): Long =
        open(standaloneSessionCounter.incrementAndGet(), url, speed, startPositionMs, durationMs)

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

    override fun setAudioFilters(skipSilence: Boolean, loudnessBoost: Boolean) {
        commands.put(PlayerCommand.SetAudioFilters(skipSilence, loudnessBoost))
    }

    /**
     * Stops the engine thread and destroys the handle.
     *
     * `mpv_terminate_destroy` while the worker is inside `mpv_wait_event` is a
     * use-after-free. Queuing [PlayerCommand.Quit] lets the worker leave its
     * loop on its own; [awaitThreadStop] then proves it has, escalating to an
     * interrupt only if the polite wait timed out. The old code ignored the
     * result of a bare `join`, so a caller went on to tear the process down —
     * and the native handle — while the worker was still alive.
     *
     * Never throws: this runs on the window-close and tray-quit paths, where an
     * exception would skip whatever teardown follows.
     */
    override fun release() {
        Logger.d(TAG, "release()")
        commands.put(PlayerCommand.Quit)
        val stopped = awaitThreadStop(worker, RELEASE_JOIN_TIMEOUT_MS, RELEASE_JOIN_TIMEOUT_MS)
        // The engine is gone as far as callers are concerned even when the
        // thread refused to die: accepting commands into a queue nobody reads
        // would leave every later open() waiting for events that never come.
        workerDead = true
        if (!stopped) {
            // Error, not warning: the native handle's owner is still running, so
            // anything that follows can be a use-after-free. The stack says what
            // the worker was doing, which is the only way to diagnose it from a
            // crash log.
            val stack = worker.stackTrace.joinToString("\n") { frame -> "    at $frame" }
            Logger.e(
                TAG,
                "engine thread did not stop after ${RELEASE_JOIN_TIMEOUT_MS * 2}ms; " +
                    "the mpv handle may still be in use. Worker stack:\n$stack"
            )
        }
        snapshotState = PlayerSnapshot()
    }

    // ── engine thread ──

    private fun runLoop() {
        var handle = 0L
        // Declared out here so the catch below can report the failure against the
        // load that was in progress. Inside the try it would be out of scope, and
        // a crashed thread would leave a silent player.
        var session = 0L
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
            var durationMs = 0L
            var eofReported = false
            // Remembered so a failure can be classified as network-vs-file
            // without the UI having to guess: `null` here made every failure
            // look like a file error.
            var currentUrl = ""

            // The af chain currently on the handle, or null before the first
            // write. The guard keeps an unchanged chain from being rewritten:
            // mpv rebuilds the filter graph when `af` is set, so flipping one
            // toggle twice (or toggling the other one) must not re-init the
            // chain the user is listening to for no audible reason. Null, not
            // "": a fresh handle's af is empty, but the first SetAudioFilters
            // must still be written so the applied state is never assumed.
            var appliedAudioFilterChain: String? = null

            while (running) {
                var command = commands.poll()
                while (command != null) {
                    when (command) {
                        is PlayerCommand.Open -> {
                            // Throw away anything mpv already queued for the file
                            // we are about to replace. Those events belong to the
                            // previous session, and reading them after `session`
                            // has moved on attributed a dead file's failure or
                            // end to the new one — a phrase of the new episode
                            // would report as failed, or the queue would skip a
                            // track, while it was playing fine.
                            if (handle != 0L) drainQueuedEvents(handle)
                            session = command.session
                            currentUrl = command.url
                            eofReported = false
                            applyOpen(handle, command.session, command.url, command.speed, command.startPositionMs)
                            durationMs = command.durationMs
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

                        is PlayerCommand.SetAudioFilters -> {
                            // Overwrite, not append: the chain is rebuilt from
                            // both toggles every time, so no sequence of flips
                            // can stack a filter that is already present. The
                            // change is applied to the running stream — `af` is
                            // a runtime property, so the file is never reloaded.
                            val chain = buildAudioFilterChain(command.skipSilence, command.loudnessBoost)
                            if (chain != appliedAudioFilterChain) {
                                val result = MpvApi.INSTANCE.mpv_set_property_string(handle, "af", chain)
                                if (result < 0) {
                                    // Not remembered as applied, so the next
                                    // toggle retries; an older libmpv that does
                                    // not know one of these filters degrades to
                                    // plain playback instead of failing the file.
                                    Logger.w(
                                        TAG,
                                        "af=\"$chain\" rejected by mpv (code $result); continuing without it"
                                    )
                                } else {
                                    appliedAudioFilterChain = chain
                                }
                            }
                        }

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
                        // FILE_LOADED says the file opened, not that the user
                        // wants it playing. A restored session is loaded paused
                        // on purpose, and announcing PLAYING here flipped the
                        // transport back to "pause" for an episode that was not
                        // making a sound. The observed `pause` property is the
                        // authority on this; only report PLAYING when mpv really
                        // is unpaused.
                        val paused = readBoolProperty(handle, "pause") ?: false
                        val next = if (paused) PlaybackState.PAUSED else PlaybackState.PLAYING
                        if (next != snapshotState.state) {
                            publish(snapshotState.copy(state = next))
                            emit(PlayerEvent.StateChanged(session, next))
                        }
                        // Chapters are only known once the file is parsed. The
                        // list is read here, on the engine thread that owns the
                        // handle, and shipped to the state layer as an event;
                        // chapterIndex resets with it and is rebuilt by the
                        // observed `chapter` property as playback moves.
                        val chapters = readChapterList(handle)
                        publish(snapshotState.copy(chapters = chapters, chapterIndex = -1))
                        emit(PlayerEvent.ChaptersLoaded(session, chapters))
                    }

                    MPV_EVENT_PROPERTY_CHANGE -> {
                        val reply = event.getLong(EVENT_REPLY_USERDATA_OFFSET)
                        // An observed property arrives wrapped in mpv_event_property;
                        // the value lives behind its own `data` pointer. Reading
                        // event.data directly read that wrapper's header instead, so
                        // every position and duration arrived as 0.
                        val property = event.getPointer(EVENT_DATA_OFFSET)
                        if (property == null || property == Pointer.NULL) continue
                        val valuePtr = property.getPointer(PROPERTY_DATA_OFFSET)
                        when (reply) {
                            OBS_EOF_REACHED -> {
                                val reached = readFlag(valuePtr)
                                if (reached && !eofReported) {
                                    // Once per arrival, not once per session: with
                                    // keep-open mpv stays parked on the last frame
                                    // and the property stays true, so re-reporting
                                    // it on every event would advance the queue
                                    // again immediately. Reset on seek/time going
                                    // backwards so replaying the same track
                                    // reports its end a second time.
                                    eofReported = true
                                    Logger.i(TAG, "session=$session track completed")
                                    publish(snapshotState.copy(state = PlaybackState.STOPPED))
                                    emit(PlayerEvent.TrackCompleted(session))
                                } else if (!reached && eofReported) {
                                    // A replay (seek back, then play) clears mpv's
                                    // flag. Older builds never reset this, so the
                                    // second playthrough of an episode ended in
                                    // silence without advancing the queue.
                                    Logger.d(TAG, "session=$session eof-reached cleared by a replay")
                                    eofReported = false
                                }
                            }

                            OBS_PAUSE -> {
                                val paused = readFlag(valuePtr)
                                val next = if (paused) PlaybackState.PAUSED else PlaybackState.PLAYING
                                if (next != snapshotState.state) {
                                    publish(snapshotState.copy(state = next))
                                    emit(PlayerEvent.StateChanged(session, next))
                                }
                            }

                            OBS_TIME_POS, OBS_DURATION -> {
                                val value = readDouble(valuePtr) ?: continue
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

                            OBS_CHAPTER -> {
                                val index = readInt64(valuePtr) ?: continue
                                val chapterIndex = index.toInt()
                                publish(snapshotState.copy(chapterIndex = chapterIndex))
                                emit(PlayerEvent.ChapterChanged(session, chapterIndex))
                            }

                            OBS_PAUSED_FOR_CACHE -> {
                                // D3: a stall, not a failure. No snapshot field —
                                // the position simply stops advancing — so the
                                // event alone carries it.
                                val stalled = readFlag(valuePtr)
                                emit(PlayerEvent.Buffering(session, stalled))
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // Never let the engine thread die silently: a dead thread means a
            // player that accepts commands and never answers one.
            Logger.e(TAG, "engine thread failed: ${e.message}", e)
            workerDead = true
            // Report it to the state layer, otherwise the last load sits in
            // LOADING for ever with no error and no retry offered.
            if (session != 0L) {
                publish(PlayerSnapshot(PlaybackState.ERROR, 0L, 0L, session))
                emit(PlayerEvent.TrackFailed(session, PlaybackErrorCategory.GENERIC))
            }
        } finally {
            workerDead = true
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
        if (silent) {
            // Applied after the table so it wins over the `ao` pinned there.
            val result = MpvApi.INSTANCE.mpv_set_option_string(handle, "ao", NULL_AUDIO_OUTPUT)
            if (result < 0) {
                Logger.w(TAG, "could not select the null audio output ($result); test playback may be audible")
            }
        }
        val initResult = MpvApi.INSTANCE.mpv_initialize(handle)
        if (initResult < 0) {
            Logger.e(TAG, "mpv_initialize failed: $initResult")
            MpvApi.INSTANCE.mpv_terminate_destroy(handle)
            return 0L
        }
        // Read back the output driver. `ao` is the one option whose failure is
        // audible rather than merely cosmetic, and a test run that silently
        // falls back to wasapi plays the test tone into the room — which is how
        // this was originally discovered. Logging the mismatch makes the next
        // occurrence a log line instead of a surprise.
        if (silent) {
            val actual = readStringProperty(handle, "ao")
            when (val check = evaluateSelfCheck("ao", NULL_AUDIO_OUTPUT, actual)) {
                is StartupSelfCheck.Ok -> Logger.d(TAG, "test playback is silent: ao=$NULL_AUDIO_OUTPUT")
                is StartupSelfCheck.UnexpectedValue -> Logger.e(
                    TAG,
                    "test playback is AUDIBLE: ao=${check.actual}, expected ${check.expected}"
                )
                is StartupSelfCheck.Unreadable ->
                    Logger.e(TAG, "could not read ao back; test playback may be audible")
            }
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
     *
     * The return code matters. `pause` and `eof-reached` were observed with the
     * wrong format constant (2, which is MPV_FORMAT_OSD_STRING), and
     * `mpv_observe_property` rejected both with -9 — so no pause or end-of-track
     * event ever arrived and the transport and auto-advance were dead, silently.
     * A failed observation is reported once here rather than disappearing.
     */
    private fun observeProperties(handle: Long) {
        val observations = listOf(
            Triple(OBS_TIME_POS, "time-pos", MpvApi.MPV_FORMAT_DOUBLE),
            Triple(OBS_DURATION, "duration", MpvApi.MPV_FORMAT_DOUBLE),
            Triple(OBS_PAUSE, "pause", MpvApi.MPV_FORMAT_FLAG),
            Triple(OBS_EOF_REACHED, "eof-reached", MpvApi.MPV_FORMAT_FLAG),
            // The currently-active chapter index, -1 when none applies. Observed
            // as its native INT64 like the rest: the value then arrives in the
            // event without a heap allocation to free.
            Triple(OBS_CHAPTER, "chapter", MpvApi.MPV_FORMAT_INT64),
            // D3: mpv pauses internally while its cache refills and `pause`
            // stays false throughout — without this flag a stall and a failure
            // are indistinguishable to the UI.
            Triple(OBS_PAUSED_FOR_CACHE, "paused-for-cache", MpvApi.MPV_FORMAT_FLAG)
        )
        for ((tag, name, format) in observations) {
            val result = MpvApi.INSTANCE.mpv_observe_property(handle, tag, name, format)
            if (result < 0) {
                Logger.e(
                    TAG,
                    "mpv_observe_property($name, format=$format) failed with $result — " +
                        "this property will never be delivered"
                )
            }
        }
    }

    /**
     * Applies a load on the engine thread and returns the session already
     * assigned by [open].
     *
     * `replace` rather than `append`: this is a single-track player, and the
     * queue lives in [MediaPlayerState]. Using mpv's playlist would mean two
     * owners for queue order.
     *
     * A zero [startPositionMs] is the only case where playback is started here
     * explicitly, because mpv begins playing as soon as a file loads. A real
     * resume point is a restored session, which must come back *paused*: issuing
     * `pause=no` unconditionally started the episode playing on launch and then
     * raced the `setPaused(true)` that [MediaPlayerState.restoreSession] sends
     * right after — mpv applies commands in order, but two round trips through
     * two queues meant the resumed episode briefly played audio the user never
     * asked for.
     */
    private fun applyOpen(handle: Long, session: Long, url: String, speed: Float, startPositionMs: Long) {
        // `start` must be set before loadfile: mpv reads it when the file is
        // opened, so writing it afterwards applies to nothing (the position is
        // already past it and it is never consulted again). Always written,
        // including 0 — mpv keeps the value across loads, so a stale resume point
        // would otherwise apply to the next track.
        MpvApi.INSTANCE.mpv_set_property_string(handle, "start", "${startPositionMs / 1000.0}")
        MpvApi.INSTANCE.mpv_set_property_string(handle, "speed", speed.coerceIn(0.25f, 4.0f).toString())

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
            // no END_FILE event will arrive to report it. Reported against the
            // session that was just requested, not against whatever the published
            // snapshot happens to hold — `open()` publishes on the caller thread,
            // so the snapshot may already describe a newer load.
            emitFailure(session, loadResult, url)
            return
        }
        MpvApi.INSTANCE.mpv_set_property_string(
            handle,
            "pause",
            if (startPositionMs > 0L) "yes" else "no"
        )
    }

    /**
     * Discards events mpv has already queued.
     *
     * Called immediately before a new load is issued. Everything still in the
     * queue at that instant describes the file being replaced: `events` carries
     * no session of its own, so once the worker's `session` variable moves on,
     * those events would be reported against the new load. A dead file's
     * END_FILE(ERROR) then shows an error over an episode that is playing
     * perfectly, and a dead file's end-of-file advances the queue past a track
     * the user just started.
     *
     * Timeout 0, so this never waits: it takes what is there and returns.
     */
    private fun drainQueuedEvents(handle: Long) {
        repeat(DRAIN_MAX_EVENTS) {
            val event = MpvApi.INSTANCE.mpv_wait_event(handle, 0.0)
            if (event == null || event == Pointer.NULL) return
            if (event.getInt(EVENT_ID_OFFSET) == MPV_EVENT_NONE) return
        }
        Logger.w(TAG, "gave up draining queued events after $DRAIN_MAX_EVENTS")
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

    /**
     * Reads a string-valued property from the handle, or null when unreadable.
     *
     * Always frees the string mpv allocates: `mpv_get_property_string` returns a
     * heap pointer the caller owns, so a missing `mpv_free` in a read-back is a
     * leak on every call.
     */
    private fun readStringProperty(handle: Long, name: String): String? {
        val ptr: Pointer? = MpvApi.INSTANCE.mpv_get_property_string(handle, name)
        if (ptr == null || ptr == Pointer.NULL) return null
        return try {
            ptr.getString(0)
        } finally {
            MpvApi.INSTANCE.mpv_free(ptr)
        }
    }

    private fun readDouble(ptr: Pointer?): Double? =
        if (ptr == null || ptr == Pointer.NULL) null else ptr.getDouble(0)

    private fun readFlag(ptr: Pointer?): Boolean =
        if (ptr == null || ptr == Pointer.NULL) false else ptr.getInt(0) != 0

    private fun readInt64(ptr: Pointer?): Long? =
        if (ptr == null || ptr == Pointer.NULL) null else ptr.getLong(0)

    /**
     * Reads the loaded file's chapter list off the handle.
     *
     * Returns an empty list for files without chapters — `chapter-list` is
     * unavailable for them, which is a normal state, not a failure. The node
     * mpv fills is freed unconditionally: [parseChapterList] runs while the
     * memory is still owned by us, and `mpv_free_node_contents` then walks and
     * releases the tree. Skipping the free would leak the whole list on every
     * load; freeing before parsing would read freed memory.
     *
     * A parse failure must not take the engine thread down (the loop's catch
     * tears playback down with it), so it degrades to "no chapters".
     */
    private fun readChapterList(handle: Long): List<MpvChapter> {
        val node = MpvNode()
        try {
            val result = MpvApi.INSTANCE.mpv_get_property(
                handle, "chapter-list", MpvApi.MPV_FORMAT_NODE, node.pointer
            )
            if (result < 0) {
                Logger.d(TAG, "chapter-list unavailable (mpv code $result); no chapters")
                return emptyList()
            }
            return try {
                parseChapterList(node)
            } catch (e: Throwable) {
                Logger.e(TAG, "chapter-list parse failed: ${e.message}", e)
                emptyList()
            }
        } finally {
            MpvApi.INSTANCE.mpv_free_node_contents(node.pointer)
        }
    }

    /**
     * Reads a flag-valued property directly from the handle.
     *
     * Used where no property-change event is available — `loadfile` completing
     * does not change `pause`, so observing it says nothing about whether the
     * just-loaded file is meant to be playing. Returns null when the property
     * cannot be read, so the caller can fall back instead of guessing "playing".
     */
    private fun readBoolProperty(handle: Long, name: String): Boolean? {
        val ptr = MpvApi.INSTANCE.mpv_get_property_string(handle, name)
            ?: return null
        if (ptr == Pointer.NULL) return null
        return try {
            when (ptr.getString(0).trim().lowercase()) {
                "yes", "true", "1" -> true
                "no", "false", "0" -> false
                else -> null
            }
        } finally {
            MpvApi.INSTANCE.mpv_free(ptr)
        }
    }

    private companion object {
        /** Generous upper bound; the loop normally exits on its next iteration. */
        const val RELEASE_JOIN_TIMEOUT_MS = 3_000L
    }
}
