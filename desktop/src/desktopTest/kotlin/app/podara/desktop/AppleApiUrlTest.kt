package app.podara.desktop

import app.podara.api.apple.route.buildSearchUrl
import app.podara.api.apple.route.extractTrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * URL handling for the iTunes API routes.
 *
 * Both bugs were silent drops: a podcast vanished from a batch result, or a
 * search quietly queried the wrong term, and nothing anywhere said why.
 */
class AppleApiUrlTest {

    // ── Lookup.extractTrackId ──

    @Test
    fun testExtractTrackIdFromPlainUrl() {
        assertEquals(
            123456L,
            extractTrackId("https://podcasts.apple.com/us/podcast/some-show/id123456")
        )
    }

    @Test
    fun testExtractTrackIdWithQueryString() {
        // The reported bug: `?i=789` made the whole id unparseable, and the
        // podcast was silently dropped from the batch map.
        assertEquals(
            123456L,
            extractTrackId("https://podcasts.apple.com/us/podcast/some-show/id123456?i=789")
        )
    }

    @Test
    fun testExtractTrackIdWithTrailingSlash() {
        assertEquals(
            123456L,
            extractTrackId("https://podcasts.apple.com/us/podcast/some-show/id123456/")
        )
    }

    @Test
    fun testExtractTrackIdWithQueryAndSlash() {
        assertEquals(
            123456L,
            extractTrackId("https://podcasts.apple.com/us/podcast/some-show/id123456/?i=789")
        )
    }

    @Test
    fun testExtractTrackIdRejectsGarbage() {
        assertNull(extractTrackId("https://podcasts.apple.com/us/podcast/some-show/"))
        assertNull(extractTrackId("not a url"))
        assertNull(extractTrackId("https://podcasts.apple.com/us/podcast/some-show/idabc"))
    }

    // ── Search.buildSearchUrl ──

    @Test
    fun testSearchUrlLeavesPlainTermsAlone() {
        assertEquals(
            "https://itunes.apple.com/search?media=podcast&country=US&term=serial",
            buildSearchUrl("serial", "US")
        )
    }

    @Test
    fun testSearchUrlEncodesAmpersand() {
        // The reported bug: `&` split the term into an extra parameter.
        val url = buildSearchUrl("cats & dogs", "US")
        assertTrue(url.contains("term=cats+%26+dogs"), "term must be one parameter: $url")
        assertTrue(url.contains("country=US"), "country must survive: $url")
    }

    @Test
    fun testSearchUrlEncodesEqualsAndHash() {
        val url = buildSearchUrl("a=b#c", "US")
        assertTrue(url.contains("term=a%3Db%23c"), url)
    }

    @Test
    fun testSearchUrlEncodesNonAscii() {
        // Chinese (and any non-ASCII) must be percent-encoded, not sent raw.
        val url = buildSearchUrl("中文 播客", "CN")
        assertTrue(!url.contains("中文"), url)
        assertTrue(url.contains("country=CN"), url)
    }
}