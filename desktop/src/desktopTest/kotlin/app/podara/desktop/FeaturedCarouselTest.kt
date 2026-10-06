package app.podara.desktop

import app.podara.screen.FeaturedCarousel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Discover featured-card carousel cursor.
 *
 * This is a regression test for a real bug, not a restatement of the
 * implementation. The carousel used to carry its index inline as
 * `featuredIndex % 5` fed to a Crossfade. With 50 podcasts that meant:
 *
 * - indices 5..49 all rendered as podcasts[0..4], so "next" from the fifth card
 *   jumped backwards to the first;
 * - podcasts 6..50 could never be featured;
 * - two distinct positions produced one crossfade target, so the transition
 *   appeared to run the wrong way.
 *
 * The first assertion is the one that would have caught it: it requires every
 * index to resolve to a distinct item, which a hardcoded modulo cannot satisfy
 * once the list is longer than the modulus.
 */
class FeaturedCarouselTest {

    @Test
    fun testEveryIndexResolvesToADistinctItem() {
        val count = 50
        val carousel = FeaturedCarousel(count)
        val seen = mutableSetOf<Int>()
        repeat(count) {
            seen += carousel.current
            carousel.next()
        }
        assertEquals(
            count,
            seen.size,
            "every carousel position must be a different podcast, got ${seen.size} distinct"
        )
    }

    @Test
    fun testSteppingForwardVisitsEveryItemExactlyOnce() {
        val count = 50
        val carousel = FeaturedCarousel(count)
        val visited = buildList {
            repeat(count) {
                add(carousel.current)
                carousel.next()
            }
        }
        assertEquals((0 until count).toList(), visited)
        assertEquals(0, carousel.current, "a full lap should return to the start")
    }

    @Test
    fun testNextWrapsForwardAndPreviousWrapsBack() {
        val carousel = FeaturedCarousel(3)
        assertEquals(0, carousel.current)
        carousel.next()
        assertEquals(1, carousel.current)
        carousel.previous()
        assertEquals(0, carousel.current)
        carousel.previous()
        assertEquals(2, carousel.current, "previous from the first wraps to the last")
    }

    @Test
    fun testSingleItemCarouselNeverMoves() {
        val carousel = FeaturedCarousel(1)
        carousel.next()
        assertEquals(0, carousel.current)
        carousel.previous()
        assertEquals(0, carousel.current)
    }

    @Test
    fun testEmptyCarouselStaysInBounds() {
        // The list can be empty during loading; the cursor must still be safe
        // to read, otherwise the carousel renders an out-of-bounds item.
        val carousel = FeaturedCarousel(0)
        assertEquals(0, carousel.current)
        carousel.next()
        assertEquals(0, carousel.current)
    }

    @Test
    fun testShrinkingListReclampsTheCursor() {
        val carousel = FeaturedCarousel(10)
        repeat(9) { carousel.next() }
        assertEquals(9, carousel.current)

        carousel.onListChanged(3)
        assertEquals(0, carousel.current, "index 9 is past the end of a 3-item list")
        assertEquals(3, carousel.size)
    }

    @Test
    fun testGrowingListKeepsTheCurrentPositionInRange() {
        val carousel = FeaturedCarousel(3)
        carousel.onListChanged(10)
        assertEquals(0, carousel.current)
        assertEquals(10, carousel.size)
        // The new items are reachable immediately.
        carousel.next()
        assertEquals(1, carousel.current)
    }

    @Test
    fun testClampingToEmptyListIsSafe() {
        val carousel = FeaturedCarousel(5)
        carousel.onListChanged(0)
        assertEquals(0, carousel.current)
        assertEquals(1, carousel.size)
    }
}