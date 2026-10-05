package app.podara.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.podara.player.TIMESTAMP_ANNOTATION
import app.podara.player.URL_ANNOTATION
import app.podara.player.parseSimpleHtml
import app.podara.player.parseTimestampMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Episode show-note parsing.
 *
 * Two of these are regression tests for defects that made the notes unusable:
 * `<strong>`/`<em>` never matched their own closing tag (the parser searched for
 * `</s` and `</e`), and the `<a>` branch popped its style stack one time too
 * often when an href was present, leaking link styling into the rest of the
 * notes. Links were also styled but unclickable, and chapter timestamps were
 * not recognised at all.
 */
class EpisodeNotesTextTest {

    private val linkColor = Color(0xFF0071E3)
    private val timestampColor = Color(0xFFFA2D48)

    private fun parse(html: String): AnnotatedString =
        parseSimpleHtml(html, linkColor = linkColor, timestampColor = timestampColor)

    private fun AnnotatedString.slice(range: AnnotatedString.Range<*>): String =
        text.substring(range.start, range.end)

    private fun AnnotatedString.allAnnotations(): List<AnnotatedString.Range<*>> =
        urlAnnotations() + timestampAnnotations()

    private fun AnnotatedString.urlAnnotations(): List<AnnotatedString.Range<String>> =
        getStringAnnotations(URL_ANNOTATION, 0, text.length)

    private fun AnnotatedString.timestampAnnotations(): List<AnnotatedString.Range<String>> =
        getStringAnnotations(TIMESTAMP_ANNOTATION, 0, text.length)

    // ── Structural tags ──

    @Test
    fun testStrongTagMatchesItsOwnClosingTag() {
        // Previously searched for "</s", so bold never applied.
        val result = parse("<p>plain <strong>bolded</strong> plain</p>")
        assertTrue(result.text.startsWith("plain bolded plain"))

        val bold = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, bold.size, "expected the <strong> span to be applied")
        assertEquals("bolded", result.slice(bold.first()))
    }

    @Test
    fun testEmTagMatchesItsOwnClosingTag() {
        val result = parse("plain <em>slanted</em> plain")
        val italic = result.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italic.size, "expected the <em> span to be applied")
        assertEquals("slanted", result.slice(italic.first()))
    }

    @Test
    fun testStyleStackIsBalancedAcrossLinksAndTimestamps() {
        // The <a> branch pushed a style plus an annotation but only popped the
        // annotation conditionally, so link styling bled past the link.
        val result = parse(
            """<p><a href="https://example.com">link</a> then <strong>bold</strong> """ +
                """then <a href="https://example.org">another</a> tail</p>"""
        )

        val urls = result.urlAnnotations()
        assertEquals(2, urls.size)
        assertEquals("https://example.com", urls[0].item)
        assertEquals("https://example.org", urls[1].item)
        assertEquals("link", result.slice(urls[0]))
        assertEquals("another", result.slice(urls[1]))

        // "tail" comes after the last link and must not carry link styling.
        val tailStart = result.text.indexOf("tail")
        assertTrue(tailStart >= 0)
        assertTrue(
            result.getStringAnnotations(URL_ANNOTATION, tailStart, tailStart + 1).isEmpty(),
            "text after the last link must not be annotated as a link"
        )
    }

    @Test
    fun testUnclosedTagsDoNotCorruptRemainingText() {
        val result = parse("before <strong> never closed")
        assertTrue(result.text.contains("before"))
        assertTrue(result.text.contains("never closed"))
    }

    // ── Links ──

    @Test
    fun testLinkHrefIsPreservedAndEntitiesDecoded() {
        val result = parse("""visit <a href="https://example.com/path?a=1&amp;b=2">this</a> now""")
        val urls = result.urlAnnotations()
        assertEquals(1, urls.size)
        // The entity inside the attribute is decoded, not left raw.
        assertEquals("https://example.com/path?a=1&b=2", urls.single().item)
        assertEquals("this", result.slice(urls.single()))
    }

    @Test
    fun testAnchorWithoutHrefStillRendersItsText() {
        val result = parse("""<a>bare anchor</a>""")
        assertEquals("bare anchor", result.text.trim())
        assertTrue(result.urlAnnotations().isEmpty())
    }

    @Test
    fun testNestedMarkupInsideLinkIsPreserved() {
        val result = parse("""<a href="https://example.com"><strong>bold link</strong></a>""")
        val urls = result.urlAnnotations()
        assertEquals(1, urls.size)
        assertEquals("bold link", result.slice(urls.single()))

        val bold = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, bold.size, "bold inside a link must still apply")
        assertEquals("bold link", result.slice(bold.single()))
    }

    // ── Timestamps ──

    @Test
    fun testTimestampMarkerParsing() {
        assertEquals(0L, parseTimestampMarker("00:00"))
        assertEquals(34_000L, parseTimestampMarker("0:34"))
        assertEquals(754_000L, parseTimestampMarker("12:34"))
        assertEquals(3_723_000L, parseTimestampMarker("1:02:03"))
        assertEquals(3_723_000L, parseTimestampMarker("[1:02:03]"))
        assertEquals(3_723_000L, parseTimestampMarker("(1:02:03)"))
    }

    @Test
    fun testTimestampMarkerRejectsInvalidValues() {
        assertNull(parseTimestampMarker("99:99"))
        assertNull(parseTimestampMarker("12:75"))
        assertNull(parseTimestampMarker("abc"))
        assertNull(parseTimestampMarker(""))
        assertNull(parseTimestampMarker("12"))
    }

    @Test
    fun testChapterTimestampsAreAnnotated() {
        val result = parse("<p>[00:00] Intro</p><p>[1:02:03] The main topic</p>")
        val stamps = result.timestampAnnotations()
        assertEquals(2, stamps.size)
        assertEquals("0", stamps[0].item)
        assertEquals("3723000", stamps[1].item)
        assertEquals("[00:00]", result.slice(stamps[0]))
        assertEquals("[1:02:03]", result.slice(stamps[1]))
    }

    @Test
    fun testProseIsNotMistakenForTimestamps() {
        // Ordinary text that merely contains colons must not become clickable.
        val result = parse("<p>Released at 10:30am, version 2:1, ratio 16:9</p>")
        val stamps = result.timestampAnnotations()
        assertTrue(stamps.isEmpty(), "unexpected timestamps: ${stamps.map { result.slice(it) }}")
    }

    @Test
    fun testTimestampAnnotationIsScopedToItsOwnMarker() {
        val result = parse("<p>[00:15] Segment one</p>")
        val stamp = result.timestampAnnotations().single()
        assertEquals("[00:15]", result.slice(stamp))

        // Text past the marker must not be annotated.
        val after = stamp.end + 2
        assertTrue(
            result.getStringAnnotations(TIMESTAMP_ANNOTATION, after, after + 6).isEmpty(),
            "annotation must not extend past the marker"
        )
    }

    @Test
    fun testTimestampAnnotationsNestInsideLinksWithoutEscaping() {
        val result = parse("""<a href="https://example.com">[01:23] chapter</a>""")
        val url = result.urlAnnotations().single()
        val stamp = result.timestampAnnotations().single()
        assertEquals("https://example.com", url.item)
        assertEquals(83_000L, stamp.item.toLong())
        assertTrue(stamp.start >= url.start && stamp.end <= url.end, "timestamp should sit inside the link")
    }

    // ── Entities and structure ──

    @Test
    fun testHtmlEntitiesAreDecoded() {
        assertEquals("a & b <c> d e", parse("a &amp; b &lt;c&gt; d&nbsp;e").text)
    }

    @Test
    fun testListItemsBecomeBullets() {
        val result = parse("<ul><li>first</li><li>second</li></ul>")
        assertTrue(result.text.contains("• first"))
        assertTrue(result.text.contains("• second"))
    }

    @Test
    fun testUnmatchedClosingTagDoesNotBreakFollowingText() {
        val result = parse("<p>alpha</p></strong> omega")
        assertTrue(result.text.contains("alpha"))
        assertTrue(result.text.contains("omega"))
    }

    @Test
    fun testBrProducesLineBreak() {
        val result = parse("one<br/>two")
        assertTrue(result.text.contains("\n"))
        assertTrue(result.text.contains("one"))
        assertTrue(result.text.contains("two"))
    }

    @Test
    fun testAnnotationRangesAreInBounds() {
        val result = parse("""<p>[00:00] Intro <a href="https://example.com">link</a> [12:34] Middle</p>""")
        val all = result.allAnnotations()
        assertTrue(all.isNotEmpty())
        for (annotation in all) {
            assertTrue(annotation.start >= 0, "start out of bounds: ${annotation.start}")
            assertTrue(annotation.end <= result.text.length, "end out of bounds: ${annotation.end}")
            assertTrue(annotation.start < annotation.end)
        }
    }

    @Test
    fun testEmptyAndBlankInputIsSafe() {
        assertEquals("", parse("").text)
        assertEquals("", parse("   ").text)
        assertNotNull(parse("<p></p>").text)
    }

    @Test
    fun testSelfClosingAndVoidTagsDoNotSwallowFollowingText() {
        val result = parse("""<p>before</p><img src="x.jpg"/><p>after</p>""")
        assertTrue(result.text.contains("before"))
        assertTrue(result.text.contains("after"))
    }
}
