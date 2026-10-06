package app.podara.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Cursor for the Discover featured-card carousel.
 *
 * The index arithmetic used to be inline in the composable, written as
 * `featuredIndex % 5`. That made the carousel structurally incapable of reaching
 * podcasts past the fifth: every index 5..size-1 resolved back to podcasts[0..4],
 * so "next" from the fifth travelled backwards and the rest of the list was never
 * featured. It also fed that same index to a Crossfade, so two different positions
 * produced one target and the transition appeared to run the wrong way.
 *
 * Holding the arithmetic here keeps it testable on its own and makes the cycle
 * explicit rather than a modulo scattered across callbacks.
 */
internal class FeaturedCarousel(count: Int) {

    private var backingCount: Int = count.coerceAtLeast(1)

    var index: Int by mutableIntStateOf(0)
        private set

    /** The index into the backing list that should currently be shown. */
    val current: Int get() = index % backingCount

    /** How many items the carousel can reach. Always at least 1. */
    val size: Int get() = backingCount

    fun next() {
        index = (index + 1) % backingCount
    }

    fun previous() {
        index = if (index > 0) index - 1 else backingCount - 1
    }

    /**
     * Re-clamps after the backing list changes.
     *
     * The result set can grow or shrink between refreshes, and a stale index can
     * then point past the end — or, after the list grows, leave the carousel
     * stuck short of the newly available items.
     */
    fun onListChanged(newCount: Int) {
        backingCount = newCount.coerceAtLeast(1)
        index = current
    }
}