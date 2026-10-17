package app.podara.data.model

/**
 * A resume point for one episode.
 *
 * [playerSession] holds a single row describing whatever the player has loaded
 * right now, so moving to another episode overwrote the previous position. This
 * table keeps one row per episode instead, which is what "continue listening",
 * per-episode progress bars and "play the next unfinished episode" all need.
 *
 * [completed] is derived at write time rather than compared on every read: an
 * episode counts as finished once playback passes [COMPLETION_THRESHOLD] of its
 * duration, and it stays finished even if the user later seeks backwards —
 * rewinding to re-hear something does not un-finish the episode.
 */
data class EpisodeProgress(
    val episodeId: String,
    val origin: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    val lastPlayedAt: Long = 0
) {
    companion object {
        /** Share of the episode after which it counts as listened to. */
        const val COMPLETION_THRESHOLD = 0.95
    }

    /** Fraction played, clamped to 0..1. Zero when the duration is unknown. */
    val fraction: Float
        get() = if (durationMs <= 0L) 0f
        else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Whether this row represents something worth offering to resume. */
    val isResumable: Boolean
        get() = !completed && positionMs > 0L
}
