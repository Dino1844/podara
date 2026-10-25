package app.podara.desktop
import app.podara.screen.DiscoverScreen
import app.podara.screen.EpisodeRow
import app.podara.screen.SectionHeader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import app.podara.api.apple.ApplePodcastClient
import app.podara.api.model.PodcastPreviewModel
import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import app.podara.data.AppDatabase
import app.podara.manager.PodcastManager
import app.podara.manager.SubscriptionManager
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverScreenTest {

    private lateinit var database: AppDatabase
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var podcastManager: PodcastManager
    private lateinit var appleClient: ApplePodcastClient
    private lateinit var testDbFile: File
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podium_discover_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
        // Injected rather than constructed inside the screen, so the test drives
        // the same shared-client wiring the app uses.
        val fetchClient = FakeFetchPodcastClient()
        appleClient = ApplePodcastClient()
        subscriptionManager = SubscriptionManager(database, fetchClient)
        podcastManager = PodcastManager(database, fetchClient, appleClient)
    }

    @After
    fun teardown() {
        database.close()
        testDbFile.delete()
        Dispatchers.resetMain()
    }

    @Test
    fun testDiscoverScreenDisplaysTitle() {
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(database = database, subscriptionManager = subscriptionManager, podcastManager = podcastManager, appleClient = appleClient, onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {}, onShowDetail = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["discover_title"]).assertIsDisplayed()
    }

    @Test
    fun testDiscoverScreenShowsSubtitle() {
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(database = database, subscriptionManager = subscriptionManager, podcastManager = podcastManager, appleClient = appleClient, onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {}, onShowDetail = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["discover_subtitle"]).assertIsDisplayed()
    }

    @Test
    fun testDiscoverScreenHasSearchField() {
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(database = database, subscriptionManager = subscriptionManager, podcastManager = podcastManager, appleClient = appleClient, onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {}, onShowDetail = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["discover_search_placeholder"]).assertIsDisplayed()
    }

    @Test
    fun testDiscoverScreenAcceptsRefreshKey() {
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(database = database, subscriptionManager = subscriptionManager, podcastManager = podcastManager, appleClient = appleClient, discoverRefreshKey = 42, onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {}, onShowDetail = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["discover_title"]).assertIsDisplayed()
    }

    @Test
    fun testEpisodeRowFiresOnShowDetailWhenClicked() {
        val podcast = PodcastPreviewModel(
            fetchUrl = "https://example.com/feed.xml",
            link = "https://example.com",
            title = "Test Podcast",
            description = "A test podcast description",
            author = "Test Author",
            imageUrl = "https://example.com/image.jpg",
            languageCode = "en"
        )
        var detailClicked = false
        composeTestRule.setContent {
            PodaraTheme {
                EpisodeRow(
                    podcast = podcast,
                    isSubscribed = false,
                    isSubscribing = false,
                    onSubscribe = {},
                    onShowDetail = { detailClicked = true }
                )
            }
        }
        composeTestRule.onNodeWithText("Test Podcast").performClick()
        composeTestRule.waitForIdle()
        assert(detailClicked) { "onShowDetail should have been called when clicking the episode row" }
    }

    // ── SectionHeader ──

    @Test
    fun testSectionHeaderShowsTitleWithoutShowAll() {
        composeTestRule.setContent {
            PodaraTheme {
                SectionHeader(title = "Test")
            }
        }
        composeTestRule.onNodeWithText("Test").assertIsDisplayed()
        // The Show All link was removed: the list under the header already shows
        // every podcast, and the link had a hand cursor but no click handler.
        composeTestRule.onNodeWithText(Strings["discover_show_all"]).assertDoesNotExist()
    }

    // ── Search, driven end to end ──

    /**
     * Serves Discover's two Apple endpoints from fixtures, offline.
     *
     * The charts feed is empty on purpose: this harness exercises the search
     * path, and an empty chart means no per-entry JSON to keep in step with the
     * response models. Any other URL (batch lookup) answers with an empty
     * result set, which [app.podara.api.apple.route.Lookup.batchLookupFeedUrls]
     * short-circuits before calling for an empty id list anyway.
     */
    private fun discoverClientWith(searchPayload: String): ApplePodcastClient {
        val chartsPayload = """
            {"feed":{
              "author":{"name":{"label":"Apple Podcasts"},"uri":{"label":"https://itunes.apple.com/webapp/rss/bestpodcasts"}},
              "entry":[],
              "updated":{"label":"October 23, 2026"},
              "rights":{"label":"Apple Inc."},
              "title":{"label":"Top Podcasts"},
              "icon":{"label":"https://example.com/icon.png"},
              "link":[{"attributes":{"rel":"self","href":"https://itunes.apple.com/us/rss/toppodcasts/json","type":"application/json"}}],
              "id":{"label":"https://itunes.apple.com/us/rss/toppodcasts"}
            }}
        """.trimIndent()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            val body = when {
                url.contains("/search") -> searchPayload
                url.contains("toppodcasts") -> chartsPayload
                else -> """{"resultCount":0,"results":[]}"""
            }
            respond(content = body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return ApplePodcastClient(httpClient = HttpClient(engine) { expectSuccess = false })
    }

    /** Two distinct catalogue entries pointing at one feed — verbatim iTunes shape. */
    private val duplicateFeedSearchPayload = """
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

    /**
     * The text-field node inside Discover's search bar.
     *
     * `onNodeWithText(placeholder)` resolves to the *merged* node, which carries
     * the placeholder's text but not the focus target, so typing through it
     * fails with a missing `RequestFocus` action even though the click focused
     * the field. The field itself is the node that owns both `EditableText` and
     * the focus action — see DiscoverScreenTest's focus dump for that split.
     */
    private val searchFieldNode = SemanticsMatcher("editableTextWithFocus") { node: androidx.compose.ui.semantics.SemanticsNode ->
        node.config.contains(SemanticsProperties.EditableText) &&
            node.config.contains(SemanticsActions.RequestFocus)
    }

    /** Types [query] into Discover's search field and submits it with Enter. */
    @OptIn(ExperimentalTestApi::class)
    private fun performSearch(query: String) {
        composeTestRule.onNodeWithText(Strings["discover_search_placeholder"]).performClick()
        // A click requests focus, and the request lands on a later frame. Typing
        // before it settles makes the test infra look for a focus action it
        // never gets to use, so let the click finish first.
        composeTestRule.waitForIdle()
        val field = composeTestRule.onNode(searchFieldNode)
        field.performTextInput(query)
        field.performKeyInput { pressKey(Key.Enter) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun testSearchRendersResultsWithDuplicateFeedsWithoutCrashing() {
        // iTunes returns two entries for one feed (observed live for `npr`,
        // `radiolab` and `history`), and the results LazyColumn keys on fetchUrl.
        // Without deduplication Compose throws `Key was already used` here and
        // the screen never renders — the exact reported "search does nothing".
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(
                    database = database,
                    subscriptionManager = subscriptionManager,
                    podcastManager = podcastManager,
                    appleClient = discoverClientWith(duplicateFeedSearchPayload),
                    onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {}, onShowDetail = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        performSearch("npr")

        composeTestRule.onNodeWithText("Planet Money").assertIsDisplayed()
        assertEquals(
            1,
            composeTestRule.onAllNodesWithText("Planet Money").fetchSemanticsNodes().size,
            "one feed must produce one row; a duplicate key takes the whole screen down"
        )
    }

    @Test
    fun testSearchWithNoMatchesSaysSoInsteadOfRepeatingThePlaceholder() {
        // A query that found nothing used to render the same "Search podcasts"
        // hint shown before anyone typed, so running a search that matched
        // nothing looked exactly like the search had never run.
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(
                    database = database,
                    subscriptionManager = subscriptionManager,
                    podcastManager = podcastManager,
                    appleClient = discoverClientWith("""{"resultCount":0,"results":[]}"""),
                    onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {}, onShowDetail = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        performSearch("zzzz-no-such-show")

        composeTestRule.onNodeWithText(Strings["discover_no_results"]).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(Strings.get("discover_no_results_hint", "zzzz-no-such-show"))
            .assertIsDisplayed()
        // The pre-search placeholder must not double as the empty-state message.
        composeTestRule.onNodeWithText(Strings["discover_search_hint"], substring = true)
            .assertDoesNotExist()
    }

    // ── Pasted feed URL: subscribe directly, bypassing the iTunes search ──

    /**
     * A feed fetcher that always fails, for driving Discover's URL error path
     * offline. A network error and a URL that is not RSS both surface as add
     * failures through [app.podara.manager.PodcastManager.addPodcast].
     */
    private class FailingFetchPodcastClient : FetchPodcastClient() {
        override suspend fun fetchNoCache(origin: String): FetchPodcastClientResult =
            FetchPodcastClientResult.Failure(java.io.IOException("connection refused"))
    }

    @Test
    fun testPastedFeedUrlSubscribesThroughFeedFetchInsteadOfItunesSearch() {
        var shown: PodcastPreviewModel? = null
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(
                    database = database,
                    subscriptionManager = subscriptionManager,
                    // The real manager over the fake fetcher: if the screen
                    // routed the URL to the iTunes search instead, the row
                    // would carry the search fixture's title, not the parsed
                    // feed's.
                    podcastManager = PodcastManager(database, FakeFetchPodcastClient(), appleClient),
                    appleClient = discoverClientWith(duplicateFeedSearchPayload),
                    onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {},
                    onShowDetail = { shown = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        performSearch("https://example.com/feed.xml")

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Fake Podcast").fetchSemanticsNodes().isNotEmpty()
        }
        // The result area shows the fetched feed's own podcast…
        composeTestRule.onNodeWithText("Fake Podcast").assertIsDisplayed()
        // …not the payload the mock iTunes search endpoint serves.
        composeTestRule.onNodeWithText("Planet Money").assertDoesNotExist()
        // And success lands on the same detail route the search results use.
        assertEquals("https://example.com/feed.xml", shown?.fetchUrl)
        assertEquals("Fake Podcast", shown?.title)
        // The subscription row exists — the library updated, not just the screen.
        runBlocking {
            assertTrue(
                database.subscriptions.getByOriginSync("https://example.com/feed.xml") != null,
                "pasting a feed URL must subscribe, not only render"
            )
        }
    }

    @Test
    fun testBareDomainInputIsCompletedToHttpsAndSubscribed() {
        var shown: PodcastPreviewModel? = null
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(
                    database = database,
                    subscriptionManager = subscriptionManager,
                    podcastManager = PodcastManager(database, FakeFetchPodcastClient(), appleClient),
                    appleClient = discoverClientWith(duplicateFeedSearchPayload),
                    onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {},
                    onShowDetail = { shown = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        performSearch("example.com/feed.xml")

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Fake Podcast").fetchSemanticsNodes().isNotEmpty()
        }
        // The scheme-less paste reached the feed fetcher as https://.
        assertEquals("https://example.com/feed.xml", shown?.fetchUrl)
    }

    @Test
    fun testFailingFeedUrlShowsErrorStateInsteadOfBlanking() {
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(
                    database = database,
                    subscriptionManager = subscriptionManager,
                    podcastManager = PodcastManager(database, FailingFetchPodcastClient(), appleClient),
                    appleClient = discoverClientWith(duplicateFeedSearchPayload),
                    onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {}, onShowDetail = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        performSearch("https://example.com/feed.xml")

        // The error banner takes the content area — the same state a failed
        // keyword search uses, with Retry and Close — never a blank screen.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(Strings["discover_retry"]).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(Strings["discover_retry"]).assertIsDisplayed()
        composeTestRule.onNodeWithText(Strings["discover_error_close"]).assertIsDisplayed()
        // A URL failure reports through the add-failure wording (it was a
        // subscription attempt, not a search) with the underlying cause.
        composeTestRule.onNodeWithText("connection refused", substring = true).assertIsDisplayed()
    }

}
