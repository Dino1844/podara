package app.podara.desktop

import app.podara.api.rss.detectCharset
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Charset selection for feed bodies.
 *
 * `decodeToString()` used to force UTF-8 on every feed, so a GBK or
 * ISO-8859-1 feed produced mojibake titles with no error anywhere — the only
 * symptom was Chinese or accented text rendering as garbage.
 *
 * The precedence tested here is HTTP header, then XML declaration, then UTF-8.
 */
class FeedCharsetDetectionTest {

    @Test
    fun testContentTypeCharsetWins() {
        assertEquals(
            charset("ISO-8859-1"),
            detectCharset("application/rss+xml; charset=ISO-8859-1", "<rss/>".toByteArray())
        )
    }

    @Test
    fun testContentTypeCharsetIsCaseInsensitiveAndToleratesQuotes() {
        assertEquals(
            charset("windows-1252"),
            detectCharset("text/xml; Charset=\"Windows-1252\"", "<rss/>".toByteArray())
        )
    }

    @Test
    fun testXmlDeclarationEncodingIsUsedWhenContentTypeSaysNothing() {
        val feed = """<?xml version="1.0" encoding="ISO-8859-1"?><rss/>"""

        assertEquals(
            charset("ISO-8859-1"),
            detectCharset("application/rss+xml", feed.toByteArray(Charsets.UTF_8))
        )
        assertEquals(
            charset("ISO-8859-1"),
            detectCharset(null, feed.toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun testContentTypeBeatsXmlDeclaration() {
        // A server that labels the bytes itself outranks a stale declaration
        // left in the file by the publishing tool.
        val feed = """<?xml version="1.0" encoding="ISO-8859-1"?><rss/>"""

        assertEquals(
            charset("GBK"),
            detectCharset("application/rss+xml; charset=GBK", feed.toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun testFallsBackToUtf8WhenNothingDeclaresAnEncoding() {
        assertEquals(
            Charsets.UTF_8,
            detectCharset("application/rss+xml", "<rss><channel/></rss>".toByteArray())
        )
        assertEquals(Charsets.UTF_8, detectCharset(null, ByteArray(0)))
    }

    @Test
    fun testIllegalCharsetNameFallsBackToUtf8() {
        // `Charset.forName` throws on unknown names; a feed must still parse.
        assertEquals(
            Charsets.UTF_8,
            detectCharset("application/rss+xml; charset=not-a-real-charset", "<rss/>".toByteArray())
        )
        assertEquals(
            Charsets.UTF_8,
            detectCharset(null, """<?xml version="1.0" encoding="klingon-8"?><rss/>""".toByteArray())
        )
    }

    @Test
    fun testXmlEncodingOutsideTheDeclarationIsIgnored() {
        // An `encoding=` inside an episode description must not be mistaken for
        // the document's declaration.
        val feed = "<?xml version=\"1.0\"?><rss><channel><description>encoding=\"GBK\"</description></channel></rss>"

        assertEquals(Charsets.UTF_8, detectCharset(null, feed.toByteArray()))
    }

    @Test
    fun testXmlDeclarationIsOnlyScannedInTheFirst200Bytes() {
        val padding = " ".repeat(300)
        val feed = "${padding}<?xml version=\"1.0\" encoding=\"GBK\"?><rss/>"

        assertEquals(
            Charsets.UTF_8,
            detectCharset(null, feed.toByteArray()),
            "a declaration this deep in the body is not a declaration"
        )
    }

    @Test
    fun testGbkFeedBytesDecodeToChineseText() {
        val title = "中文播客"
        val bytes = title.toByteArray(charset("GBK"))
        val charset = detectCharset("text/xml; charset=gb2312", bytes)

        assertEquals(title, String(bytes, charset))
    }

    private fun charset(name: String): Charset = Charset.forName(name)
}
