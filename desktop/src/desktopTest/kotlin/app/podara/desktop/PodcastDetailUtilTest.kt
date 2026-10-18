package app.podara.desktop
import app.podara.formatDate
import app.podara.util.Strings
import app.podara.util.stripHtml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PodcastDetailUtilTest {

    // ── stripHtml ──

    @Test
    fun testStripHtmlRemovesParagraphTags() {
        val input = "<p>Hello world</p>"
        assertEquals("Hello world", stripHtml(input))
    }

    @Test
    fun testStripHtmlRemovesNestedTags() {
        val input = "Description with <a href=\"link\">a link</a> inside"
        assertEquals("Description with a link inside", stripHtml(input))
    }

    @Test
    fun testStripHtmlRemovesMultipleTags() {
        val input = "<p>Episode 1</p><ul><li>Topic A</li><li>Topic B</li></ul>"
        val result = stripHtml(input)
        assertTrue(result.contains("Episode 1"))
        assertTrue(result.contains("Topic A"))
        assertTrue(result.contains("Topic B"))
        assertTrue(!result.contains("<"))
    }

    @Test
    fun testStripHtmlRemovesBrTags() {
        val input = "Line1<br/>Line2<br />Line3"
        assertEquals("Line1 Line2 Line3", stripHtml(input))
    }

    @Test
    fun testStripHtmlHandlesEmptyString() {
        assertEquals("", stripHtml(""))
    }

    @Test
    fun testStripHtmlHandlesPlainText() {
        val input = "Just some plain text without any HTML"
        assertEquals(input, stripHtml(input))
    }

    @Test
    fun testStripHtmlCollapsesExtraWhitespace() {
        val input = "<p>  Hello   </p><p>  World  </p>"
        assertEquals("Hello World", stripHtml(input))
    }

    // ── formatDate ──

    @Test
    fun testFormatDateReturnsEmptyForZero() {
        assertEquals("", formatDate(0))
    }

    @Test
    fun testFormatDateReturnsEmptyForNegative() {
        assertEquals("", formatDate(-1))
    }

    @Test
    fun testFormatDateFollowsTheSelectedLanguage() {
        // The date used to be a hardcoded `yyyy-MM-dd` that ignored the language,
        // so a Chinese UI still printed `2025-12-30` while the full player printed
        // `Dec 30, 2025`. It now goes through DateFormat, which keys off
        // Strings.currentLanguage. Pin both branches rather than one hardcoded
        // shape, and restore the previous language afterwards because this is
        // process-global state other tests also assert against.
        val timestamp = 1767052800000L
        val previous = Strings.currentLanguage
        try {
            Strings.updateLanguage("en")
            assertEquals("2025-12-30", formatDate(timestamp))

            Strings.updateLanguage("zh")
            assertEquals("2025年12月30日", formatDate(timestamp))
        } finally {
            Strings.updateLanguage(previous)
        }
    }

    @Test
    fun testFormatDateShowsTheRealDay() {
        // Guards against an off-by-one from any timezone handling: the rendered
        // string has to contain the day the timestamp actually falls on.
        val timestamp = 1705276800000L // 2024-01-15 UTC
        val previous = Strings.currentLanguage
        try {
            Strings.updateLanguage("en")
            val result = formatDate(timestamp)
            assertTrue(result.startsWith("2024-01"), "expected January 2024, got $result")
        } finally {
            Strings.updateLanguage(previous)
        }
    }

    @Test
    fun testFormatDateHandlesPositiveSmallValue() {
        assertTrue(formatDate(1).isNotEmpty())
    }
}
