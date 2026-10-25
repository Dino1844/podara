package app.podara.player

/**
 * Immutable playback state published by the engine.
 *
 * One object carries everything the UI needs, so a reader can never observe a
 * half-updated pair such as "new position with the previous track's duration".
 * That transient inconsistency was reachable while the old engine assigned
 * `currentPosition`, `duration` and `metadata` as separate fields.
 */
data class PlayerSnapshot(
    val state: PlaybackState = PlaybackState.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /**
     * Identifies the load this snapshot belongs to.
     *
     * Bumped on every [AudioPlayerEngine.open]. Events carrying an older value
     * are discarded by the engine, which is what makes the caller able to trust
     * every event it receives: there is no "is this end-of-track real or left
     * over from the previous file" question any more.
     */
    val session: Long = 0L,
    /**
     * Chapters mpv reported for the loaded file, empty when it has none.
     *
     * Re-read on every [MpvAudioPlayerEngine] file load, so a snapshot from a
     * fresh `open` never carries the previous file's list.
     */
    val chapters: List<MpvChapter> = emptyList(),
    /** mpv's `chapter` property: the index into [chapters], or -1 when none applies. */
    val chapterIndex: Int = -1
)

/**
 * Something the engine reports, in the order the engine observed it.
 *
 * Delivered on the engine thread. Handlers must not block and must not call
 * back into the engine.
 */
sealed interface PlayerEvent {
    /** The load this event belongs to; see [PlayerSnapshot.session]. */
    val session: Long

    data class StateChanged(
        override val session: Long,
        val state: PlaybackState
    ) : PlayerEvent

    data class Progress(
        override val session: Long,
        val positionMs: Long,
        val durationMs: Long
    ) : PlayerEvent

    /**
     * The chapter list mpv reported for the file that just loaded.
     *
     * Emitted once per load on FILE_LOADED, empty when the file has no
     * chapters — the receiver replaces whatever list it held, so a track
     * switch can never leave the previous file's chapters on screen.
     */
    data class ChaptersLoaded(
        override val session: Long,
        val chapters: List<MpvChapter>
    ) : PlayerEvent

    /**
     * mpv's `chapter` property changed: the playback position moved into (or
     * out of) a chapter. [index] is the position in the list from
     * [ChaptersLoaded], or -1 when no chapter applies.
     */
    data class ChapterChanged(
        override val session: Long,
        val index: Int
    ) : PlayerEvent

    /**
     * The track reached its natural end.
     *
     * Emitted only for the current session, so the receiver may advance the
     * queue unconditionally.
     */
    data class TrackCompleted(override val session: Long) : PlayerEvent

    /**
     * Loading or decoding failed.
     *
     * The engine does not emit [StateChanged] alongside this: a failure must not
     * look like a natural end, or the queue would auto-advance past a broken
     * episode. [category] is one of the [PlaybackErrorCategory] constants.
     */
    data class TrackFailed(
        override val session: Long,
        val category: String
    ) : PlayerEvent
}

/** Commands the UI sends to the engine. Consumed on the engine thread only. */
sealed interface PlayerCommand {
    /**
     * Load a track.
     *
     * Carries [session] because the engine thread must stamp every event it
     * emits for this load with the id the *caller* was given by
     * [AudioPlayerEngine.open]. Having the worker allocate its own id would
     * break the correlation the caller relies on.
     */
    data class Open(
        val session: Long,
        val url: String,
        val speed: Float,
        val startPositionMs: Long,
        val durationMs: Long
    ) : PlayerCommand

    data class SetPaused(val paused: Boolean) : PlayerCommand
    data object Stop : PlayerCommand
    data class Seek(val positionMs: Long) : PlayerCommand
    data class SetSpeed(val speed: Float) : PlayerCommand
    data class SetVolume(val volume: Int) : PlayerCommand

    /**
     * Replaces the audio filter chain with the one built from the two
     * enhancement toggles (Skip Silence, Loudness Boost).
     *
     * Carries both flags rather than one delta so the engine can write the
     * whole `af` property as a canonical string — the overwrite is what makes
     * repeated switching idempotent, and it needs the pair, not the change.
     */
    data class SetAudioFilters(val skipSilence: Boolean, val loudnessBoost: Boolean) : PlayerCommand

    /** Tear the handle down and exit the engine thread. */
    data object Quit : PlayerCommand
}
