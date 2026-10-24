package app.podara.player

/**
 * Pure playback decision rules.
 *
 * These functions carry the playback *policy* — how the current queue index
 * shifts when items are removed or reordered, and what "previous" should do.
 * [MediaPlayerState] used to inline all of this, which meant the rules could
 * only be exercised by constructing Compose state and a real (or faked) audio
 * engine.
 *
 * Nothing here imports Compose, the audio engine, or the database. That is the
 * point: the rules are testable on their own, and the state holder is reduced
 * to performing the decided action.
 *
 * End-of-track classification used to live here too, as
 * `decideStopTransition(elapsedSincePlay, isUserPaused)`. It is gone: the engine
 * now reports completion as an explicit [PlayerEvent.TrackCompleted] carrying a
 * session id, so "is this real or a leftover callback" is answered by identity
 * instead of by comparing wall-clock timestamps against a three-second window.
 */

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
     * "Previous" restarts the current track when playback is further along than
     * this, matching the conventional player behaviour of a first press being
     * "go back to the start" rather than "previous episode".
     */
    const val RESTART_WINDOW_MS = 3000L

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
     * A negative [currentIndex] is the "queue loaded, nothing ever played"
     * sentinel, not an index: it must stay `-1` so the UI keeps showing the
     * transport as idle instead of silently pointing at the first item.
     *
     * @param queueSizeAfterRemoval size of the queue once [removedIndex] is gone.
     */
    fun indexAfterRemoval(
        queueSizeAfterRemoval: Int,
        removedIndex: Int,
        currentIndex: Int
    ): RemovalOutcome = when {
        // Removing an item cannot start playback, so the idle sentinel survives.
        currentIndex < 0 -> RemovalOutcome(
            nextIndex = -1,
            playbackWasRemoved = false,
            shouldStopPlayback = false
        )

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
     * The index tracks the *track*, not the position: every removed item that
     * sat before the playing item moves it down by one, exactly as the
     * single-item [indexAfterRemoval] does. Clamping alone is wrong — with six
     * items playing number four (index 3), deleting the first two leaves a
     * four-item queue where that same episode is at index 1; a clamp would keep
     * 3 and point at a different episode.
     *
     * When the playing item is itself removed, the same shift lands on the item
     * that slid into its place, which is where playback should continue.
     *
     * A negative [currentIndex] is the idle sentinel and is preserved.
     *
     * @param queueSizeAfterRemoval size of the queue once all of [removedIndices] are gone.
     */
    fun indexAfterBatchRemoval(
        queueSizeAfterRemoval: Int,
        removedIndices: Set<Int>,
        currentIndex: Int
    ): RemovalOutcome {
        val playbackWasRemoved = currentIndex in removedIndices
        if (queueSizeAfterRemoval <= 0) {
            return RemovalOutcome(
                nextIndex = -1,
                playbackWasRemoved = playbackWasRemoved,
                shouldStopPlayback = playbackWasRemoved
            )
        }
        if (currentIndex < 0) {
            return RemovalOutcome(
                nextIndex = -1,
                playbackWasRemoved = false,
                shouldStopPlayback = false
            )
        }
        val removedBeforeCurrent = removedIndices.count { it < currentIndex }
        return RemovalOutcome(
            nextIndex = (currentIndex - removedBeforeCurrent)
                .coerceIn(0, queueSizeAfterRemoval - 1),
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

    /**
     * Clamp a restored session index into the bounds of the queue that was loaded.
     *
     * The saved index can be the `-1` idle sentinel (a queue was persisted but
     * the user never pressed play). Coercing it into `0..size-1` would resurrect
     * a track the user never started, so a negative index stays negative and the
     * caller restores the queue without loading anything.
     */
    fun clampRestoredIndex(queueSize: Int, storedIndex: Int): Int = when {
        queueSize <= 0 -> -1
        storedIndex < 0 -> -1
        else -> storedIndex.coerceIn(0, queueSize - 1)
    }

    /**
     * The wall-clock time a sleep timer set for [minutes] should fire at, or
     * `null` when the timer is being cleared.
     */
    fun sleepTimerDeadline(nowMs: Long, minutes: Int?): Long? {
        if (minutes == null || minutes <= 0) return null
        return nowMs + minutes * 60_000L
    }
}