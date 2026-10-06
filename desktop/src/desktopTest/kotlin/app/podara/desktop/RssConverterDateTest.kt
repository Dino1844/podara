package app.podara.desktop

import app.podara.util.RssConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RSS pubDate parsing.
 *
 * The old implementation tried exactly two SimpleDateFormats and silently
 * returned 0 for everything else — named zones, numeric ISO offsets, fractional
 * seconds, bare dates. A 0 pubDate doesn't crash anything; it just makes
 * "recent update" sorting silently wrong for the whole podcast.
 */
class RssConverterDateTest {

    @Test
    fun testRfc822WithNumericZone() {
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("Mon, 01 Jan 2024 00:00:00 +0000")
        )
    }

    @Test
    fun testRfc822WithNamedZone() {
        // The reported gap: GMT parsed as nothing.
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("Mon, 01 Jan 2024 00:00:00 GMT")
        )
    }

    @Test
    fun testIso8601WithZulu() {
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("2024-01-01T00:00:00Z")
        )
    }

    @Test
    fun testIso8601WithOffset() {
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("2024-01-01T08:00:00+08:00")
        )
    }

    @Test
    fun testIso8601WithFractionalSeconds() {
        assertEquals(
            1704067200123L,
            RssConverter.parseDate("2024-01-01T00:00:00.123Z")
        )
    }

    @Test
    fun testDateTimeWithoutZoneAssumesUtc() {
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("2024-01-01T00:00:00")
        )
    }

    @Test
    fun testBareDate() {
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("2024-01-01")
        )
    }

    @Test
    fun testSurroundingWhitespaceIsTolerated() {
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("  Mon, 01 Jan 2024 00:00:00 GMT  ")
        )
    }

    @Test
    fun testNullAndBlankAndGarbage() {
        assertEquals(0L, RssConverter.parseDate(null))
        assertEquals(0L, RssConverter.parseDate(""))
        assertEquals(0L, RssConverter.parseDate("   "))
        assertEquals(0L, RssConverter.parseDate("yesterday, probably"))
    }

    @Test
    fun testNonZeroResultsAreSane() {
        // Guard against a parser quietly succeeding with the wrong century or
        // calendar: a 2024 date must land inside 2024.
        val parsed = RssConverter.parseDate("Mon, 01 Jan 2024 00:00:00 GMT")
        assertTrue(parsed in 1704067200000L..1735689599999L, "got $parsed")
    }
}