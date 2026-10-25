package app.podara.desktop

import app.podara.util.searchInputAsFeedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The feed-URL check behind Discover's search box. Everything the screen
 * branches on is decided here, so the cases are exactly the ones a search box
 * sees: pasted links (with and without scheme, with query strings) must be
 * recognised, and keywords — English, Chinese, dotted, multi-word — must
 * never be mistaken for one.
 */
class SearchInputFeedUrlTest {

    // ── Real URLs ──

    @Test
    fun testHttpsUrlIsRecognizedVerbatim() {
        val url = "https://example.com/feed.xml"
        assertEquals(url, searchInputAsFeedUrl(url))
    }

    @Test
    fun testHttpsUrlWithQueryParametersIsRecognizedVerbatim() {
        val url = "https://example.com/feed.xml?format=xml&season=2"
        assertEquals(url, searchInputAsFeedUrl(url))
    }

    @Test
    fun testHttpUrlIsRecognizedVerbatim() {
        val url = "http://example.com/feed.xml"
        assertEquals(url, searchInputAsFeedUrl(url))
    }

    @Test
    fun testSchemeAndHostCaseIsAccepted() {
        val url = "HTTPS://Example.com/Feed.xml"
        assertEquals(url, searchInputAsFeedUrl(url))
    }

    @Test
    fun testSurroundingWhitespaceIsTrimmed() {
        assertEquals(
            "https://example.com/feed.xml",
            searchInputAsFeedUrl("  https://example.com/feed.xml \n")
        )
    }

    @Test
    fun testSchemeWithoutHostIsNotAUrl() {
        assertNull(searchInputAsFeedUrl("https://"))
    }

    // ── Bare-host tolerance: no scheme, completed with https:// ──

    @Test
    fun testBareHostWithPathGetsHttpsPrefix() {
        assertEquals("https://example.com/feed.xml", searchInputAsFeedUrl("example.com/feed.xml"))
    }

    @Test
    fun testBareHostWithQueryGetsHttpsPrefix() {
        assertEquals("https://example.com/feed.xml?id=1", searchInputAsFeedUrl("example.com/feed.xml?id=1"))
    }

    @Test
    fun testBareHostWithoutPathGetsHttpsPrefix() {
        assertEquals("https://example.com", searchInputAsFeedUrl("example.com"))
    }

    @Test
    fun testBareHostWithSurroundingWhitespaceGetsHttpsPrefix() {
        assertEquals("https://example.com/feed.xml", searchInputAsFeedUrl("\texample.com/feed.xml "))
    }

    // ── Keywords must not be misjudged ──

    @Test
    fun testPlainKeywordIsNotAUrl() {
        assertNull(searchInputAsFeedUrl("npr"))
    }

    @Test
    fun testChineseKeywordIsNotAUrl() {
        assertNull(searchInputAsFeedUrl("中文播客"))
    }

    @Test
    fun testMultiWordKeywordIsNotAUrl() {
        assertNull(searchInputAsFeedUrl("planet money"))
    }

    @Test
    fun testDottedKeywordWithSpaceIsNotAUrl() {
        assertNull(searchInputAsFeedUrl("u.s. news"))
    }

    @Test
    fun testDottedNumericKeywordIsNotAUrl() {
        assertNull(searchInputAsFeedUrl("3.5"))
    }

    @Test
    fun testKeywordWithSurroundingWhitespaceIsNotAUrl() {
        assertNull(searchInputAsFeedUrl("  npr  "))
    }

    @Test
    fun testBlankInputIsNotAUrl() {
        assertNull(searchInputAsFeedUrl(""))
        assertNull(searchInputAsFeedUrl("   "))
    }
}
