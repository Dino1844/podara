package app.podara.player

import app.podara.util.Logger
import com.sun.jna.Pointer
import kotlin.concurrent.thread

private const val TAG = "MpvAudioPlayerEngine"

// ── mpv event / error constants (client.h) ──
internal const val MPV_EVENT_NONE = 0
internal const val MPV_EVENT_SHUTDOWN = 1
internal const val MPV_EVENT_END_FILE = 7
// mpv_event_end_file.reason values
internal const val MPV_END_FILE_REASON_ERROR = 4
// mpv_error codes (subset)
internal const val MPV_ERROR_LOADING_FAILED = -13
internal const val MPV_ERROR_NOTHING_TO_PLAY = -16
internal const val MPV_ERROR_UNKNOWN_FORMAT = -17
internal const val MPV_ERROR_UNSUPPORTED = -18
internal const val MPV_ERROR_NOT_IMPLEMENTED = -19
internal const val MPV_ERROR_GENERIC = -20

/**
 * Maps an mpv error code plus the failing URL to a [PlaybackErrorCategory].
 * LOADING_FAILED is the common case (missing local file, dead network URL);
 * the URL scheme picks the explanation that matches what actually broke.
 * Visible to tests without loading native mpv.
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

class MpvAudioPlayerEngine : AudioPlayerEngine {

    // Written by release() on the caller's thread and read by the poll thread
    // on every tick. Without @Volatile the poll loop could keep reading a
    // cached non-zero handle and call into mpv after it had been destroyed.
    @Volatile private var mpvHandle: Long = 0L

    @Volatile private var pollThread: Thread? = null

    /** Set before interrupting the poll thread; the loop exits as soon as it sees this. */
    @Volatile private var shuttingDown = false

    private var currentSpeed = 1.0f

    @Volatile private var currentUrl: String? = null

    @Volatile override var isPlaying = false; private set
    @Volatile override var currentPosition = 0L; private set
    @Volatile override var duration = 0L; private set
    @Volatile override var playbackState: PlaybackState = PlaybackState.IDLE; private set
    @Volatile override var metadata: PlayerMetadata? = null; private set

    override var onPlayStateChanged: ((Boolean) -> Unit)? = null
    override var onPositionChanged: ((Long, Long) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    init {
        val loaded = MpvNativeLoader.load()
        if (!loaded) {
            throw IllegalStateException("mpv-1.dll not found. Place it in libs/ directory.")
        }
        mpvHandle = MpvApi.INSTANCE.mpv_create()

        MpvApi.INSTANCE.mpv_set_option_string(mpvHandle, "terminal", "no")
        MpvApi.INSTANCE.mpv_set_option_string(mpvHandle, "audio-only", "yes")
        MpvApi.INSTANCE.mpv_set_option_string(mpvHandle, "video", "no")
        MpvApi.INSTANCE.mpv_set_option_string(mpvHandle, "ao", "wasapi")
        // Keep the file loaded after EOF so eof-reached=yes can be reliably
        // detected on the next poll tick before mpv unloads the file.
        MpvApi.INSTANCE.mpv_set_option_string(mpvHandle, "keep-open", "yes")

        val initResult = MpvApi.INSTANCE.mpv_initialize(mpvHandle)
        if (initResult < 0) {
            Logger.e(TAG, "mpv_initialize failed: $initResult")
            throw IllegalStateException("mpv initialization failed with code $initResult")
        }

        startPolling()
        Logger.i(TAG, "mpv engine initialized")
    }

    private fun startPolling() {
        pollThread = thread(name = "mpv-poll", isDaemon = true) {
            while (!shuttingDown && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(250)
                    // Re-check after the sleep: release() may have flipped this
                    // while we were parked, and touching mpv now would be a
                    // use-after-free.
                    if (shuttingDown) break
                    drainEvents()
                    pollProperties()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!shuttingDown && !Thread.currentThread().isInterrupted) {
                        Logger.e(TAG, "Poll error: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Drains mpv's event queue (non-blocking) and reports load/playback failures.
     *
     * mpv_event layout (client.h, 64-bit): event_id (int) @0, error (int) @4,
     * reply_userdata (uint64) @8, data (pointer) @16. The event is owned by mpv
     * and must NOT be freed; its data stays valid until the next mpv_wait_event
     * call, and the fields we need are read immediately.
     *
     * On failure the engine does NOT invoke onPlayStateChanged — see the
     * contract on AudioPlayerEngine.onError.
     */
    private fun drainEvents() {
        if (mpvHandle == 0L) return
        while (true) {
            val event = MpvApi.INSTANCE.mpv_wait_event(mpvHandle, 0.0)
            if (event == Pointer.NULL) return
            when (event.getInt(0)) {
                MPV_EVENT_NONE -> return
                MPV_EVENT_SHUTDOWN -> return
                MPV_EVENT_END_FILE -> {
                    val data = event.getPointer(16)
                    // JNA returns either null or a zero-peer Pointer for a NULL
                    // struct field, depending on version — guard both.
                    if (data == null || data == Pointer.NULL) continue
                    if (data.getInt(0) != MPV_END_FILE_REASON_ERROR) continue
                    val category = playbackErrorCategory(data.getInt(4), currentUrl)
                    Logger.e(TAG, "Playback failed: mpv error code ${data.getInt(4)}, category=$category, url=$currentUrl")
                    isPlaying = false
                    playbackState = PlaybackState.ERROR
                    onError?.invoke(category)
                }
                else -> Unit
            }
        }
    }

    private fun pollProperties() {
        if (mpvHandle == 0L) return

        val posPtr = MpvApi.INSTANCE.mpv_get_property_string(mpvHandle, "time-pos")
        if (posPtr != Pointer.NULL) {
            try {
                val posStr = posPtr.getString(0)
                val posSec = posStr.toDoubleOrNull()
                if (posSec != null) {
                    val newPos = (posSec * 1000).toLong()
                    if (newPos != currentPosition) {
                        currentPosition = newPos
                        onPositionChanged?.invoke(currentPosition, duration)
                    }
                }
            } finally {
                MpvApi.INSTANCE.mpv_free(posPtr)
            }
        }

        val durPtr = MpvApi.INSTANCE.mpv_get_property_string(mpvHandle, "duration")
        if (durPtr != Pointer.NULL) {
            try {
                val durStr = durPtr.getString(0)
                val durSec = durStr.toDoubleOrNull()
                if (durSec != null) {
                    val newDur = (durSec * 1000).toLong()
                    if (newDur != duration) {
                        duration = newDur
                        metadata = metadata?.copy(durationMs = duration)
                            ?: PlayerMetadata(url = currentUrl ?: "", durationMs = duration)
                        onPositionChanged?.invoke(currentPosition, duration)
                    }
                }
            } finally {
                MpvApi.INSTANCE.mpv_free(durPtr)
            }
        }

        val eofPtr = MpvApi.INSTANCE.mpv_get_property_string(mpvHandle, "eof-reached")
        if (eofPtr != Pointer.NULL) {
            try {
                val eofStr = eofPtr.getString(0)
                if (eofStr == "yes" && isPlaying) {
                    Logger.i(TAG, "EOF detected, calling playNext()")
                    isPlaying = false
                    playbackState = PlaybackState.STOPPED
                    onPlayStateChanged?.invoke(false)
                }
            } finally {
                MpvApi.INSTANCE.mpv_free(eofPtr)
            }
        }

        val pausePtr = MpvApi.INSTANCE.mpv_get_property_string(mpvHandle, "pause")
        if (pausePtr != Pointer.NULL) {
            try {
                val pauseStr = pausePtr.getString(0)
                val paused = pauseStr == "yes"
                if (paused && isPlaying) {
                    isPlaying = false
                    playbackState = PlaybackState.PAUSED
                    onPlayStateChanged?.invoke(false)
                } else if (!paused && !isPlaying && playbackState == PlaybackState.PLAYING) {
                    isPlaying = true
                    onPlayStateChanged?.invoke(true)
                } else if (!paused && !isPlaying && playbackState == PlaybackState.PAUSED) {
                    // mpv has resumed (e.g. after the brief pause=yes during initial loading
                    // that set playbackState to PAUSED). Restore isPlaying and notify the
                    // callback so the EOF handler can fire later.
                    isPlaying = true
                    playbackState = PlaybackState.PLAYING
                    onPlayStateChanged?.invoke(true)
                }
            } finally {
                MpvApi.INSTANCE.mpv_free(pausePtr)
            }
        }
    }

    /**
     * Runs [block] with the live mpv handle, or does nothing after [release].
     *
     * Every command path funnels through here: the UI can still deliver a
     * click after the engine has been torn down (a queued coroutine, a tray
     * menu item), and calling into a destroyed handle is a native crash.
     */
    private inline fun withHandle(block: (Long) -> Unit) {
        val handle = mpvHandle
        if (handle == 0L || shuttingDown) return
        block(handle)
    }

    override fun play(url: String, speed: Float, startPositionMs: Long, durationMs: Long) {
        Logger.i(TAG, "play() url=$url, speed=$speed, startPositionMs=$startPositionMs")
        currentUrl = url
        currentSpeed = speed
        playbackState = PlaybackState.LOADING
        metadata = PlayerMetadata(url = url, durationMs = durationMs)

        if (durationMs > 0) {
            duration = durationMs
        }

        val handle = mpvHandle
        if (handle == 0L || shuttingDown) {
            Logger.w(TAG, "play() ignored — engine has been released")
            return
        }

        // mpv_command returns before the load attempt starts; a negative result
        // here means the command itself was rejected (e.g. a blank URL) and no
        // END_FILE event will ever arrive, so the failure is reported here
        // instead of waiting for the event drain.
        val loadArgs = arrayOfNulls<String>(4)
        loadArgs[0] = "loadfile"
        loadArgs[1] = url
        loadArgs[2] = "replace"
        val loadResult = MpvApi.INSTANCE.mpv_command(handle, loadArgs)
        if (loadResult < 0) {
            val category = playbackErrorCategory(loadResult, url)
            Logger.e(TAG, "loadfile rejected: mpv error code $loadResult, category=$category, url=$url")
            isPlaying = false
            playbackState = PlaybackState.ERROR
            onError?.invoke(category)
            return
        }
        MpvApi.INSTANCE.mpv_set_property_string(handle, "pause", "no")
        MpvApi.INSTANCE.mpv_set_property_string(handle, "speed", speed.toString())

        // Always set start (even to "0") to clear any stale value from a previous
        // play() — otherwise mpv persists the option into subsequent loadfile calls.
        MpvApi.INSTANCE.mpv_set_property_string(handle, "start", "${startPositionMs / 1000.0}")

        isPlaying = true
        playbackState = PlaybackState.PLAYING
        onPlayStateChanged?.invoke(true)
    }

    override fun pause() {
        Logger.d(TAG, "pause()")
        withHandle { MpvApi.INSTANCE.mpv_set_property_string(it, "pause", "yes") }
        isPlaying = false
        playbackState = PlaybackState.PAUSED
        onPlayStateChanged?.invoke(false)
    }

    override fun resume() {
        Logger.d(TAG, "resume()")
        withHandle { MpvApi.INSTANCE.mpv_set_property_string(it, "pause", "no") }
        isPlaying = true
        playbackState = PlaybackState.PLAYING
        onPlayStateChanged?.invoke(true)
    }

    /**
     * Stops playback and reports the state change.
     *
     * The callback matters: [app.podara.player.MediaPlayerState.stop] relies on
     * the engine to drive `isPlaying`, and the poll loop cannot recover a
     * STOPPED state on its own (its pause branch only reacts to PLAYING and
     * PAUSED). Without this notification the UI was left showing "paused" with
     * a disabled play button and no way back.
     */
    override fun stop() {
        Logger.d(TAG, "stop()")
        withHandle { MpvApi.command(it, "stop") }
        isPlaying = false
        playbackState = PlaybackState.STOPPED
        onPlayStateChanged?.invoke(false)
    }

    override fun seek(positionMs: Long) {
        Logger.d(TAG, "seek(${positionMs}ms)")
        // `absolute+exact` rather than writing the time-pos property: the exact
        // flag makes mpv land precisely on the requested position instead of
        // snapping to the nearest keyframe, which is what a scrub bar implies.
        withHandle { MpvApi.command(it, "seek", "${positionMs / 1000.0}", "absolute+exact") }
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        Logger.d(TAG, "setSpeed($clamped)")
        currentSpeed = clamped
        withHandle { MpvApi.INSTANCE.mpv_set_property_string(it, "speed", clamped.toString()) }
    }

    override fun setVolume(vol: Int) {
        val clamped = vol.coerceIn(0, 100)
        Logger.d(TAG, "setVolume($clamped)")
        withHandle { MpvApi.INSTANCE.mpv_set_property_string(it, "volume", clamped.toString()) }
    }

    /**
     * Stops the poll thread and destroys the mpv handle.
     *
     * The ordering here is the whole point. `interrupt()` only breaks the
     * thread out of `Thread.sleep`; if it happens to be inside
     * `mpv_get_property_string` or `mpv_wait_event`, the interrupt does
     * nothing and it keeps running. Destroying the handle while that call is
     * in flight is a use-after-free inside libmpv, which surfaces as a native
     * crash with no Kotlin stack.
     *
     * So: raise the flag, interrupt to hurry it along, then *join* — only once
     * the thread has actually left its loop is it safe to tear the handle down.
     */
    override fun release() {
        Logger.d(TAG, "release()")
        shuttingDown = true

        val threadToJoin = pollThread
        pollThread = null
        threadToJoin?.interrupt()
        if (threadToJoin != null) {
            try {
                // The poll loop sleeps in 250 ms slices, so this is generous.
                // If it somehow does not finish, log it and still tear down —
                // leaking the handle would be worse than the small risk.
                threadToJoin.join(POLL_THREAD_JOIN_TIMEOUT_MS)
                if (threadToJoin.isAlive) {
                    Logger.w(TAG, "Poll thread did not stop within ${POLL_THREAD_JOIN_TIMEOUT_MS}ms")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Logger.w(TAG, "Interrupted while waiting for the poll thread to stop")
            }
        }

        val handle = mpvHandle
        if (handle != 0L) {
            mpvHandle = 0L
            MpvApi.command(handle, "quit")
            MpvApi.INSTANCE.mpv_terminate_destroy(handle)
        }
        isPlaying = false
        playbackState = PlaybackState.IDLE
        metadata = null
    }

    private companion object {
        /** Upper bound on waiting for the poll thread; it sleeps 250 ms per tick. */
        const val POLL_THREAD_JOIN_TIMEOUT_MS = 2_000L
    }
}
