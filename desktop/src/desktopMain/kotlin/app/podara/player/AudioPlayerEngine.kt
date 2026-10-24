package app.podara.player

enum class PlaybackState {
    IDLE, LOADING, PLAYING, PAUSED, STOPPED, ERROR
}

/**
 * Stable failure categories an engine reports through [PlayerEvent.TrackFailed].
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

/**
 * The audio backend.
 *
 * ## Threading contract
 *
 * Every method may be called from any thread. Implementations own a single
 * worker thread and must apply commands in submission order. Nothing in this
 * interface exposes a mutable field: readers observe state through
 * [snapshot] and are notified through [events], both of which are safe to touch
 * from any thread.
 *
 * This replaced a design where the caller read and wrote `isPlaying`,
 * `currentPosition` and friends directly while a polling thread mutated them.
 * That worked only because every field happened to be `@Volatile`, and it left
 * the caller responsible for deciding whether a `false` play-state was a real
 * end-of-track or a leftover callback from the previous file.
 *
 * ## Identity
 *
 * [open] returns a session id. Every subsequent event carries the session it
 * belongs to, and the engine never emits an event for a superseded session.
 * Callers can therefore act on [PlayerEvent.TrackCompleted] unconditionally
 * instead of comparing wall-clock timestamps against a heuristic window.
 */
interface AudioPlayerEngine {

    /**
     * The most recent state the engine published.
     *
     * A snapshot rather than a set of individual fields so a reader cannot see
     * a new position paired with the previous track's duration.
     */
    val snapshot: PlayerSnapshot

    /**
     * Registers the single event sink.
     *
     * Called on the engine thread. Implementations must not block here; a slow
     * handler delays playback progress delivery. Exactly one handler is allowed
     * because the state layer is the only legitimate consumer — a second one
     * would have to make the same decisions twice, which is how the two copies
     * drift apart.
     */
    fun setEventHandler(handler: (PlayerEvent) -> Unit)

    /**
     * Loads [url] and starts playing it.
     *
     * [session] is assigned by the *caller*, not by the engine. The state layer
     * has to know the id before the load starts: events can be delivered
     * synchronously from inside this call, and an engine-assigned id would mean
     * those first events arrived before the caller had stored the id to compare
     * them against — so they were dropped as stale, including a fast failure,
     * which left the spinner up forever. Allocating the id first removes the
     * window entirely.
     *
     * @param speed applied to the new track explicitly. The backend has its own
     *   default, so an omitted speed silently reverts the track to 1x while the
     *   UI keeps showing the user's chosen speed.
     * @param durationMs the known duration from the feed, used until the backend
     *   reports its own; 0 when unknown.
     * @return [session], the id every event for this load will carry.
     */
    fun open(session: Long, url: String, speed: Float, startPositionMs: Long, durationMs: Long): Long

    fun setPaused(paused: Boolean)
    fun stop()
    fun seek(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setVolume(volume: Int)

    /**
     * Tears the backend down.
     *
     * Blocks until the worker thread has exited, so that a caller which then
     * exits the process cannot race the native teardown. Implementations must
     * be idempotent: the tray quit path and the window close path can both land
     * here.
     */
    fun release()
}
