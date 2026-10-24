package app.podara.desktop

import app.podara.util.artworkSeedFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The colour seed behind the artwork placeholder.
 *
 * `imageSeedColor` had been in the schema and in every model since the first
 * version, but every write path passed 0 — so the placeholder was the same
 * surface for the whole library and the field meant nothing. These tests pin the
 * two properties the field was always supposed to have: stable across launches,
 * and actually different between shows.
 */
class ArtworkSeedTest {

    @Test
    fun testTheSameIdentityAlwaysGetsTheSameSeed() {
        assertEquals(artworkSeedFor("https://example.com/feed.xml"), artworkSeedFor("https://example.com/feed.xml"))
    }

    @Test
    fun testDifferentFeedsGetDifferentSeeds() {
        val seeds = listOf(
            "https://example.com/a.xml",
            "https://example.com/b.xml",
            "https://other.example.org/a.xml",
            "https://example.com/A.xml"
        ).map { artworkSeedFor(it) }

        assertEquals(seeds.size, seeds.toSet().size, "four different feeds must not collapse onto one colour: $seeds")
    }

    @Test
    fun testTheSeedIsNeverTheNoSeedSentinel() {
        // 0 is what the UI reads as "this row predates the seed", so a podcast
        // whose identity hashed to 0 would silently lose its colour.
        val identities = (0 until 512).map { "https://example.com/feed-$it.xml" }
        assertTrue(identities.none { artworkSeedFor(it) == 0 }, "no identity may hash to the 0 sentinel")
    }

    @Test
    fun testAdjacentPartsDoNotCollide() {
        // Joined with a separator that cannot occur in a URL; plain concatenation
        // would make these two the same key.
        assertNotEquals(artworkSeedFor("ab", "c"), artworkSeedFor("a", "bc"))
    }

    @Test
    fun testSeedsSpreadAcrossTheHueRange() {
        // The placeholder takes its hue from the low 16 bits, so a set of seeds
        // that all landed in one bucket would be no better than a constant.
        val buckets = (0 until 64).map { (artworkSeedFor("https://example.com/feed-$it.xml") and 0xFFFF) * 6 / 0x10000 }
        assertTrue(buckets.toSet().size >= 4, "64 feeds should not cluster into fewer than 4 of 6 hue buckets: $buckets")
    }
}
