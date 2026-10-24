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
    fun testSpaceSeparatedDateTimeWithoutZone() {
        // The shape the audit found: ISO-ish, but with a space instead of `T`.
        // LocalDateTime's built-in parser rejects it, so it used to be 0.
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("2024-01-01 00:00:00")
        )
    }

    @Test
    fun testSpaceSeparatedDateTimeWithFractionalSeconds() {
        assertEquals(
            1704067200123L,
            RssConverter.parseDate("2024-01-01 00:00:00.123")
        )
    }

    @Test
    fun testRfc822WithTwoDigitYearIsExpanded() {
        // RFC_1123_DATE_TIME reads "24" as year 24, which sorted the episode
        // ~2000 years into the past. RFC 2822 puts two-digit years in 1950-2049.
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("Mon, 01 Jan 24 00:00:00 GMT")
        )
        assertEquals(
            java.time.LocalDate.of(1995, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli(),
            RssConverter.parseDate("Sun, 01 Jan 95 00:00:00 GMT")
        )
    }

    @Test
    fun testWrongDayOfWeekDoesNotLoseTheDate() {
        // java.time validates the day-of-week against the resolved date and
        // rejects the whole string on a mismatch. Feeds get it wrong routinely,
        // and 01 Jan 1995 was a Sunday, not a Monday.
        assertEquals(
            java.time.LocalDate.of(1995, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli(),
            RssConverter.parseDate("Mon, 01 Jan 95 00:00:00 GMT")
        )
        assertEquals(
            1704067200000L,
            RssConverter.parseDate("Fri, 01 Jan 2024 00:00:00 GMT")
        )
    }

    @Test
    fun testRfc822WithUsZoneAbbreviation() {
        // EST is UTC-5. The named zone used to make the whole date unparseable
        // (0); resolving it through java.time's zone database would instead
        // treat it as America/New_York, i.e. -4 for a summer date.
        assertEquals(
            1704085200000L,
            RssConverter.parseDate("Mon, 01 Jan 2024 00:00:00 EST")
        )
        // CST is UTC-6, PST is UTC-8, and the RFC 822 abbreviations keep their
        // standard offsets year-round.
        assertEquals(
            1704088800000L,
            RssConverter.parseDate("Mon, 01 Jan 2024 00:00:00 CST")
        )
        assertEquals(
            1704096000000L,
            RssConverter.parseDate("Mon, 01 Jan 2024 00:00:00 PST")
        )
    }

    @Test
    fun testNamedZoneKeepsStandardOffsetInSummer() {
        // 2024-07-01 is inside US daylight saving time, but a date that says
        // "EST" means UTC-5 regardless — mapping the abbreviation to a region
        // would have produced -4 and shifted the episode by an hour.
        val parsed = RssConverter.parseDate("Mon, 01 Jul 2024 00:00:00 EST")
        assertEquals(
            java.time.LocalDateTime.of(2024, 7, 1, 5, 0)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
            parsed
        )
    }

    @Test
    fun testDatesOutsidePlausibleRangeAreRejected() {
        // Before 1970 means a misparse, not a publish date.
        assertEquals(0L, RssConverter.parseDate("Wed, 01 Jan 1969 00:00:00 GMT"), "pre-1970 must be a failure")
        assertEquals(0L, RssConverter.parseDate("1969-12-31T23:59:59Z"), "pre-1970 must be a failure")
        // More than ten years in the future is a feed bug (or a misparse).
        assertEquals(0L, RssConverter.parseDate("01 Jan 2100 00:00:00 GMT"))
    }

    @Test
    fun testNonZeroResultsAreSane() {
        // Guard against a parser quietly succeeding with the wrong century or
        // calendar: a 2024 date must land inside 2024.
        val parsed = RssConverter.parseDate("Mon, 01 Jan 2024 00:00:00 GMT")
        assertTrue(parsed in 1704067200000L..1735689599999L, "got $parsed")
    }
}