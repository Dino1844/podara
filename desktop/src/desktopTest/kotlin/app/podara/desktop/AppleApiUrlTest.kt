package app.podara.desktop

import app.podara.api.apple.ApplePodcastClient
import app.podara.api.apple.route.buildSearchUrl
import app.podara.api.apple.route.dedupeByFeedUrl
import app.podara.api.apple.route.extractTrackId
import app.podara.api.model.PodcastPreviewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
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
    fun testExtractTrackIdIsCaseInsensitive() {
        assertEquals(
            123456L,
            extractTrackId("https://podcasts.apple.com/us/podcast/some-show/ID123456")
        )
    }

    @Test
    fun testExtractTrackIdIgnoresCountryCodeIdSegment() {
        // Indonesia's country code is `id`; it must not be mistaken for the id segment.
        assertEquals(
            123456L,
            extractTrackId("https://podcasts.apple.com/id/podcast/some-show/id123456")
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

    @Test
    fun testSearchUrlEncodesPlusSign() {
        // A literal `+` means space in form-urlencoded data, so it must be
        // escaped; spaces themselves become `+`.
        val url = buildSearchUrl("c++ basics", "US")
        assertTrue(url.contains("term=c%2B%2B+basics"), url)
    }

    // ── Search.dedupeByFeedUrl ──

    private fun preview(fetchUrl: String, title: String = "Show") = PodcastPreviewModel(
        fetchUrl = fetchUrl,
        link = "https://podcasts.apple.com/us/podcast/x/id1",
        title = title,
        description = "",
        author = "author",
        imageUrl = "https://example.com/a.jpg",
        languageCode = "us"
    )

    @Test
    fun testSearchResultsAreDeduplicatedByFeedUrl() {
        // Observed live: iTunes returns two catalogue entries for one feed —
        // `npr` yields two "Planet Money" rows both pointing at
        // feeds.npr.org/510289/podcast.xml, and `history` yields six such pairs.
        // The results list is keyed on fetchUrl, so the second copy made Compose
        // throw `Key was already used` and blanked Discover entirely.
        val results = dedupeByFeedUrl(
            listOf(
                preview("https://feeds.npr.org/510289/podcast.xml", "Planet Money"),
                preview("https://feeds.npr.org/510289/podcast.xml", "Planet Money"),
                preview("https://feeds.example.com/other.xml", "Other")
            )
        )
        assertEquals(2, results.size, "the duplicate feed must collapse to one row")
        assertEquals("Planet Money", results[0].title, "the first (best-ranked) hit must win")
        assertEquals("Other", results[1].title)
    }

    @Test
    fun testDedupeKeepsDistinctFeedsWithTheSameTitle() {
        // Two different shows can share a title; only the feed identifies a show.
        val results = dedupeByFeedUrl(
            listOf(
                preview("https://a.example.com/feed.xml", "Radio Lab"),
                preview("https://b.example.com/feed.xml", "Radio Lab")
            )
        )
        assertEquals(2, results.size)
    }

    @Test
    fun testDedupeIgnoresUrlCaseAndSurroundingSpace() {
        // A scheme/host differing only in case is the same feed. Without
        // normalisation the duplicate survives and the crash comes back.
        val results = dedupeByFeedUrl(
            listOf(
                preview("https://Feeds.Example.com/show.xml"),
                preview("  HTTPS://feeds.example.com/SHOW.xml  ")
            )
        )
        assertEquals(1, results.size, "feed URLs must compare case-insensitively")
    }

    @Test
    fun testDedupeLeavesUniqueInputUntouched() {
        val input = listOf(
            preview("https://one.example.com/feed.xml", "One"),
            preview("https://two.example.com/feed.xml", "Two"),
            preview("https://three.example.com/feed.xml", "Three")
        )
        assertEquals(input, dedupeByFeedUrl(input), "ordering must be preserved")
        assertEquals(emptyList(), dedupeByFeedUrl(emptyList()))
    }

    @Test
    fun testSearchCallItselfReturnsNoDuplicateFeedUrls() = runBlocking {
        // The wiring test. The dedupe helper above can be correct while
        // `Search.search` forgets to call it, and that regression reaches the
        // screen as a crash — so the duplicate is fed through the real route,
        // real JSON decoding included.
        val payload = """
            {"resultCount":2,"results":[
              {"wrapperType":"track","kind":"podcast","collectionId":290783428,
               "trackId":290783428,"artistName":"NPR","collectionName":"Planet Money",
               "trackName":"Planet Money","collectionCensoredName":"Planet Money",
               "trackCensoredName":"Planet Money",
               "collectionViewUrl":"https://podcasts.apple.com/us/podcast/planet-money/id290783428",
               "feedUrl":"https://feeds.npr.org/510289/podcast.xml",
               "trackViewUrl":"https://podcasts.apple.com/us/podcast/planet-money/id290783428",
               "artworkUrl30":"https://example.com/30.jpg",
               "artworkUrl60":"https://example.com/60.jpg",
               "artworkUrl100":"https://example.com/100.jpg",
               "collectionPrice":0.0,"trackPrice":0.0,"collectionHdPrice":0,
               "collectionExplicitness":"notExplicit","trackExplicitness":"cleaned",
               "trackCount":1000,"country":"USA","currency":"USD",
               "primaryGenreName":"Daily News",
               "artworkUrl600":"https://example.com/600.jpg",
               "genreIds":["1526"],"genres":["Daily News"]},
              {"wrapperType":"track","kind":"podcast","collectionId":1896802722,
               "trackId":1896802722,"artistName":"NPR","collectionName":"Planet Money",
               "trackName":"Planet Money","collectionCensoredName":"Planet Money",
               "trackCensoredName":"Planet Money",
               "collectionViewUrl":"https://podcasts.apple.com/us/podcast/planet-money/id1896802722",
               "feedUrl":"https://feeds.npr.org/510289/podcast.xml",
               "trackViewUrl":"https://podcasts.apple.com/us/podcast/planet-money/id1896802722",
               "artworkUrl30":"https://example.com/30.jpg",
               "artworkUrl60":"https://example.com/60.jpg",
               "artworkUrl100":"https://example.com/100.jpg",
               "collectionPrice":0.0,"trackPrice":0.0,"collectionHdPrice":0,
               "collectionExplicitness":"notExplicit","trackExplicitness":"cleaned",
               "trackCount":1000,"country":"USA","currency":"USD",
               "primaryGenreName":"Daily News",
               "artworkUrl600":"https://example.com/600.jpg",
               "genreIds":["1526"],"genres":["Daily News"]}
            ]}
        """.trimIndent()

        val engine = MockEngine {
            respond(
                content = payload,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apple = ApplePodcastClient(httpClient = HttpClient(engine) { expectSuccess = false })

        val results = apple.search.search("npr")

        assertEquals(1, results.size, "the route must collapse duplicate feeds itself")
        assertEquals("https://feeds.npr.org/510289/podcast.xml", results[0].fetchUrl)
        // The crash this prevents, stated as the invariant the screen relies on.
        assertEquals(
            results.size,
            results.map { it.fetchUrl }.distinct().size,
            "Discover keys its list on fetchUrl, so the route must never return it twice"
        )
    }
}
