package app.podara.player

enum class PlaybackState {
    IDLE, LOADING, PLAYING, PAUSED, STOPPED, ERROR
}

/**
 * Stable failure categories an engine reports through [AudioPlayerEngine.onError].
 * The engine only decides *why* playback failed; the UI maps these to
 * human-readable copy via Strings, so the strings are contract, not prose.
 */
object PlaybackErrorCategory {
    /** A remote (http/https) source could not be opened or dropped mid-stream. */
    const val NETWORK = "network"

    /** A local file could not be opened (moved, deleted, unreadable). */
    const val FILE = "file"

    /** The source was found but is not decodable audio. */
    const val UNSUPPORTED = "unsupported"

    /** Anything that is neither of the above (device errors, unknown causes). */
    const val GENERIC = "generic"
}

data class PlayerMetadata(
    val url: String,
    val title: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L
)

interface AudioPlayerEngine {
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    val playbackState: PlaybackState
    val metadata: PlayerMetadata?

    var onPlayStateChanged: ((Boolean) -> Unit)?
    var onPositionChanged: ((Long, Long) -> Unit)?

    /**
     * Invoked when playback of the current source fails. The argument is one of
     * the [PlaybackErrorCategory] constants. Engines must not invoke
     * [onPlayStateChanged] for a failure: the state layer owns the transition
     * (a failure must not trigger queue auto-advance).
     */
    var onError: ((String) -> Unit)?

    fun play(url: String, speed: Float = 1.0f, startPositionMs: Long = 0L, durationMs: Long = 0L)
    fun pause()
    fun resume()
    fun stop()
    fun seek(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setVolume(vol: Int)
    fun release()
}
