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

    private var mpvHandle: Long = 0L
    private var pollThread: Thread? = null
    private var currentSpeed = 1.0f
    private var currentUrl: String? = null

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
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(250)
                    drainEvents()
                    pollProperties()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
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

    override fun play(url: String, speed: Float, startPositionMs: Long, durationMs: Long) {
        Logger.i(TAG, "play() url=$url, speed=$speed, startPositionMs=$startPositionMs")
        currentUrl = url
        currentSpeed = speed
        playbackState = PlaybackState.LOADING
        metadata = PlayerMetadata(url = url, durationMs = durationMs)

        if (durationMs > 0) {
            duration = durationMs
        }

        // mpv_command returns before the load attempt starts; a negative result
        // here means the command itself was rejected (e.g. a blank URL) and no
        // END_FILE event will ever arrive, so the failure is reported here
        // instead of waiting for the event drain.
        val loadArgs = arrayOfNulls<String>(4)
        loadArgs[0] = "loadfile"
        loadArgs[1] = url
        loadArgs[2] = "replace"
        val loadResult = MpvApi.INSTANCE.mpv_command(mpvHandle, loadArgs)
        if (loadResult < 0) {
            val category = playbackErrorCategory(loadResult, url)
            Logger.e(TAG, "loadfile rejected: mpv error code $loadResult, category=$category, url=$url")
            isPlaying = false
            playbackState = PlaybackState.ERROR
            onError?.invoke(category)
            return
        }
        MpvApi.INSTANCE.mpv_set_property_string(mpvHandle, "pause", "no")
        MpvApi.INSTANCE.mpv_set_property_string(mpvHandle, "speed", speed.toString())

        // Always set start (even to "0") to clear any stale value from a previous
        // play() — otherwise mpv persists the option into subsequent loadfile calls.
        MpvApi.INSTANCE.mpv_set_property_string(mpvHandle, "start", "${startPositionMs / 1000.0}")

        isPlaying = true
        playbackState = PlaybackState.PLAYING
        onPlayStateChanged?.invoke(true)
    }

    override fun pause() {
        Logger.d(TAG, "pause()")
        MpvApi.INSTANCE.mpv_set_property_string(mpvHandle, "pause", "yes")
        isPlaying = false
        playbackState = PlaybackState.PAUSED
        onPlayStateChanged?.invoke(false)
    }

    override fun resume() {
        Logger.d(TAG, "resume()")
        MpvApi.INSTANCE.mpv_set_property_string(mpvHandle, "pause", "no")
        isPlaying = true
        playbackState = PlaybackState.PLAYING
        onPlayStateChanged?.invoke(true)
    }

    override fun stop() {
        Logger.d(TAG, "stop()")
        MpvApi.command(mpvHandle, "stop")
        isPlaying = false
        playbackState = PlaybackState.STOPPED
    }

    override fun seek(positionMs: Long) {
        Logger.d(TAG, "seek(${positionMs}ms)")
        MpvApi.INSTANCE.mpv_set_property_string(mpvHandle, "time-pos", "${positionMs / 1000.0}")
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        Logger.d(TAG, "setSpeed($clamped)")
        currentSpeed = clamped
        MpvApi.INSTANCE.mpv_set_property_string(mpvHandle, "speed", clamped.toString())
    }

    override fun setVolume(vol: Int) {
        val clamped = vol.coerceIn(0, 100)
        Logger.d(TAG, "setVolume($clamped)")
        MpvApi.INSTANCE.mpv_set_property_string(mpvHandle, "volume", clamped.toString())
    }

    override fun release() {
        Logger.d(TAG, "release()")
        pollThread?.interrupt()
        pollThread = null
        if (mpvHandle != 0L) {
            MpvApi.command(mpvHandle, "quit")
            MpvApi.INSTANCE.mpv_terminate_destroy(mpvHandle)
            mpvHandle = 0L
        }
        isPlaying = false
        playbackState = PlaybackState.IDLE
        metadata = null
    }
}
