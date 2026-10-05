package app.podara.player

/**
 * Pure playback decision rules.
 *
 * These functions carry the actual playback *policy* — when a stop transition
 * means "the track ended" versus "this is a stale callback", how the current
 * queue index shifts when items are removed or reordered, and what "previous"
 * should do. [MediaPlayerState] used to inline all of this, which meant the
 * rules could only be exercised by constructing Compose state and a real (or
 * faked) audio engine.
 *
 * Nothing here imports Compose, the audio engine, or the database. That is the
 * point: the rules are testable on their own, and the state holder is reduced
 * to performing the decided action.
 */

/** A stop transition reported by the audio engine. */
sealed interface StopTransition {
    /** Too soon after [MediaPlayerState.play] to be a real end-of-track; ignore it. */
    data object Ignore : StopTransition

    /** The track genuinely finished; advance to the next queue item. */
    data object AdvanceToNext : StopTransition

    /** The user paused; stay where we are. */
    data object StayPaused : StopTransition
}

/** What "previous" should do, given playback position and queue position. */
sealed interface PreviousAction {
    /** Restart the current track from the beginning. */
    data object RestartCurrent : PreviousAction

    /** Start the queue item at [index]. */
    data class PlayIndex(val index: Int) : PreviousAction

    /** Already at the start of the queue; nothing to do. */
    data object AtBeginning : PreviousAction
}

/** The queue index to hold after a removal, and what should happen to playback. */
data class RemovalOutcome(
    val nextIndex: Int,
    /** True when the removed item was the playing one, so playback must move or stop. */
    val playbackWasRemoved: Boolean,
    /** True when playback should stop entirely (the queue is now empty). */
    val shouldStopPlayback: Boolean
)

object PlaybackRules {

    /**
     * Transitions arriving within this window of starting playback are treated
     * as stale — mpv can emit a `false` state while still loading a file, or
     * report EOF left over from the previous file after [MediaPlayerState.playNext].
     */
    const val STALE_TRANSITION_WINDOW_MS = 3000L

    /**
     * "Previous" restarts the current track when playback is further along than
     * this, matching the conventional player behaviour of a first press being
     * "go back to the start" rather than "previous episode".
     */
    const val RESTART_WINDOW_MS = 3000L

    /**
     * Classify a `false` play-state transition.
     *
     * @param elapsedSincePlayStartMs wall-clock ms since the last [MediaPlayerState.play].
     * @param isUserPaused whether the user explicitly paused.
     */
    fun decideStopTransition(elapsedSincePlayStartMs: Long, isUserPaused: Boolean): StopTransition = when {
        elapsedSincePlayStartMs < STALE_TRANSITION_WINDOW_MS -> StopTransition.Ignore
        isUserPaused -> StopTransition.StayPaused
        else -> StopTransition.AdvanceToNext
    }

    /** The index [MediaPlayerState.playNext] should move to, or `null` at the end of the queue. */
    fun nextIndexToPlay(currentIndex: Int, queueSize: Int): Int? =
        if (currentIndex + 1 < queueSize) currentIndex + 1 else null

    /** Decide what "previous" means right now. */
    fun decidePrevious(currentPositionMs: Long, currentIndex: Int): PreviousAction = when {
        currentPositionMs > RESTART_WINDOW_MS -> PreviousAction.RestartCurrent
        currentIndex > 0 -> PreviousAction.PlayIndex(currentIndex - 1)
        else -> PreviousAction.AtBeginning
    }

    /**
     * Queue index to hold after removing the single item at [removedIndex].
     *
     * Removing the playing item hands playback to whatever now sits at that
     * position (clamped to the new last item), or stops when nothing is left.
     * Removing an item ahead of the playing one shifts the index back; removing
     * one behind it leaves the index alone.
     *
     * @param queueSizeAfterRemoval size of the queue once [removedIndex] is gone.
     */
    fun indexAfterRemoval(
        queueSizeAfterRemoval: Int,
        removedIndex: Int,
        currentIndex: Int
    ): RemovalOutcome = when {
        removedIndex == currentIndex && queueSizeAfterRemoval > 0 -> RemovalOutcome(
            nextIndex = removedIndex.coerceIn(0, queueSizeAfterRemoval - 1),
            playbackWasRemoved = true,
            shouldStopPlayback = false
        )

        removedIndex == currentIndex -> RemovalOutcome(
            nextIndex = -1,
            playbackWasRemoved = true,
            shouldStopPlayback = true
        )

        queueSizeAfterRemoval == 0 -> RemovalOutcome(
            nextIndex = -1,
            playbackWasRemoved = false,
            shouldStopPlayback = false
        )

        else -> RemovalOutcome(
            nextIndex = (if (removedIndex < currentIndex) currentIndex - 1 else currentIndex)
                .coerceIn(0, queueSizeAfterRemoval - 1),
            playbackWasRemoved = false,
            shouldStopPlayback = false
        )
    }

    /**
     * Queue index to hold after removing several items at once
     * (see [MediaPlayerState.removeSelectedFromQueue]).
     *
     * When the playing item is among those removed, playback continues from the
     * same clamped position. Otherwise the index is only clamped, never shifted —
     * a batch removal can close the gap on both sides, so a shift would be wrong.
     *
     * @param queueSizeAfterRemoval size of the queue once all of [removedIndices] are gone.
     */
    fun indexAfterBatchRemoval(
        queueSizeAfterRemoval: Int,
        removedIndices: Set<Int>,
        currentIndex: Int
    ): RemovalOutcome {
        val playbackWasRemoved = currentIndex in removedIndices
        if (queueSizeAfterRemoval == 0) {
            return RemovalOutcome(
                nextIndex = -1,
                playbackWasRemoved = playbackWasRemoved,
                shouldStopPlayback = playbackWasRemoved
            )
        }
        return RemovalOutcome(
            nextIndex = currentIndex.coerceIn(0, queueSizeAfterRemoval - 1),
            playbackWasRemoved = playbackWasRemoved,
            shouldStopPlayback = false
        )
    }

    /**
     * Queue index to hold after moving an item during a drag-to-reorder.
     *
     * Dragging the playing item moves the playhead with it. Dragging an item
     * across the playhead shifts it by one, so it keeps pointing at the same
     * *track* rather than jumping to its neighbour.
     */
    fun indexAfterMove(currentIndex: Int, fromIndex: Int, toIndex: Int): Int = when {
        currentIndex == fromIndex -> toIndex
        fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
        fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
        else -> currentIndex
    }

    /** Clamp a restored session index into the bounds of the queue that was loaded. */
    fun clampRestoredIndex(queueSize: Int, storedIndex: Int): Int =
        if (queueSize == 0) -1 else storedIndex.coerceIn(0, queueSize - 1)

    /**
     * The wall-clock time a sleep timer set for [minutes] should fire at, or
     * `null` when the timer is being cleared.
     */
    fun sleepTimerDeadline(nowMs: Long, minutes: Int?): Long? {
        if (minutes == null || minutes <= 0) return null
        return nowMs + minutes * 60_000L
    }
}